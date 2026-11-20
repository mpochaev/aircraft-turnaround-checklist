package ru.miit.uvp411.checklist.ops.internal;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.miit.uvp411.checklist.ops.PlanCalculator;
import ru.miit.uvp411.checklist.ops.PlanCalculator.Norm;
import ru.miit.uvp411.checklist.ops.PlanCalculator.PlannedOperation;
import ru.miit.uvp411.checklist.ref.RefApi;
import ru.miit.uvp411.checklist.shared.error.ApiError;
import ru.miit.uvp411.checklist.shared.error.ApiException;

@Service
public class TurnaroundService {

    private final TurnaroundQueries queries;
    private final RefApi refApi;
    private final PlanCalculator planCalculator;
    private final OpsAudit audit;

    public TurnaroundService(TurnaroundQueries queries, RefApi refApi,
                             PlanCalculator planCalculator, OpsAudit audit) {
        this.queries = queries;
        this.refApi = refApi;
        this.planCalculator = planCalculator;
        this.audit = audit;
    }

    @Transactional
    public Map<String, Object> create(TurnaroundRequest request) {
        Map<String, Object> arrival = queries.flight(request.arrFlightId())
                .orElseThrow(() -> inactiveReference("arrFlightId", request.arrFlightId()));
        Map<String, Object> departure = queries.flight(request.depFlightId())
                .orElseThrow(() -> inactiveReference("depFlightId", request.depFlightId()));

        requireActive("aircraft", request.aircraftId(), "aircraftId");
        requireActive("stands", request.standId(), "standId");
        Map<String, Object> aircraftType = queries.aircraftType(request.aircraftId());
        int aircraftTypeId = ((Number) aircraftType.get("id")).intValue();
        requireActive("aircraft-types", aircraftTypeId, "aircraftId");
        requireTechnician(request.technicianId());

        long arrivalAirportId = ((Number) arrival.get("arrAirportId")).longValue();
        long departureAirportId = ((Number) departure.get("depAirportId")).longValue();
        if (arrivalAirportId != departureAirportId
                || arrivalAirportId != queries.standAirport(request.standId())) {
            throw unprocessable(OpsErrors.ROUTE_MISMATCH, "Рейсы и стоянка должны находиться в одном аэропорту",
                    "arrFlightId", "depFlightId", "standId");
        }

        LocalDateTime arrivalAt = (LocalDateTime) arrival.get("sta");
        LocalDateTime departureAt = (LocalDateTime) departure.get("std");
        if (!departureAt.isAfter(arrivalAt)) {
            throw unprocessable(OpsErrors.TIME_ORDER, "Вылет должен быть позже прилёта", "depFlightId");
        }

        if (queries.aircraftBusy(request.aircraftId()) || queries.standBusy(request.standId())) {
            throw conflict(OpsErrors.AIRCRAFT_BUSY, "Борт или стоянка заняты другим оборотом",
                    "aircraftId", "standId");
        }

        List<Map<String, Object>> activeNorms = refApi.activeNorms((String) aircraftType.get("code"));
        if (activeNorms.isEmpty()) {
            throw unprocessable(OpsErrors.NO_NORMS, "Для типа ВС нет активных нормативов", "aircraftId");
        }
        List<Norm> norms = activeNorms.stream().map(TurnaroundService::toNorm).toList();
        List<PlannedOperation> plan = planCalculator.calculate(arrivalAt, norms, Set.of());

        long turnaroundId;
        try {
            turnaroundId = queries.insertTurnaround(request);
        } catch (DataIntegrityViolationException e) {
            throw translateConstraint(e);
        }
        queries.insertOperations(turnaroundId, plan);
        queries.insertDependencies(turnaroundId, plan);

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("arrFlightId", request.arrFlightId());
        values.put("depFlightId", request.depFlightId());
        values.put("aircraftId", request.aircraftId());
        values.put("standId", request.standId());
        values.put("technicianId", request.technicianId());
        values.put("status", "planned");
        audit.turnaround("turnaround_created", turnaroundId, null, values);

        Map<String, Object> result = new LinkedHashMap<>(queries.byId(turnaroundId)
                .orElseThrow(() -> new IllegalStateException("Созданный оборот не найден")));
        result.put("operations", queries.operations(turnaroundId));
        return result;
    }

    private void requireActive(String dictionary, long id, String field) {
        if (!refApi.isActive(dictionary, id)) {
            throw inactiveReference(field, id);
        }
    }

    private void requireTechnician(int technicianId) {
        if (!queries.technicianActive(technicianId).orElse(false)
                || !queries.technicianHasService(technicianId)) {
            throw inactiveReference("technicianId", technicianId);
        }
    }

    private static Norm toNorm(Map<String, Object> values) {
        Object rawAfter = values.get("after");
        List<String> after = rawAfter == null || rawAfter.toString().isBlank()
                ? List.of()
                : List.of(rawAfter.toString().split("\\s*,\\s*"));
        return new Norm(
                ((Number) values.get("id")).longValue(),
                (String) values.get("operationCode"),
                ((Number) values.get("seqNo")).intValue(),
                ((Number) values.get("durationMin")).intValue(),
                (Boolean) values.get("isMandatory"),
                after);
    }

    private static ApiException inactiveReference(String field, Object value) {
        String message = "Ссылка отсутствует или неактивна";
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, OpsErrors.INACTIVE_REFERENCE, message,
                List.of(new ApiError.Detail(field, value, message)));
    }

    private static ApiException unprocessable(String code, String message, String... fields) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message,
                Arrays.stream(fields)
                        .map(field -> new ApiError.Detail(field, null, message))
                        .toList());
    }

    private static ApiException conflict(String code, String message, String... fields) {
        return new ApiException(HttpStatus.CONFLICT, code, message,
                Arrays.stream(fields)
                        .map(field -> new ApiError.Detail(field, null, message))
                        .toList());
    }

    private static RuntimeException translateConstraint(DataIntegrityViolationException exception) {
        String message = exception.getMostSpecificCause().getMessage();
        if (message != null && (message.contains("turnaround_arr_flight_uq")
                || message.contains("turnaround_dep_flight_uq"))) {
            return conflict(OpsErrors.FLIGHT_ALREADY_USED, "Рейс уже связан с другим неотменённым оборотом",
                    "arrFlightId", "depFlightId");
        }
        if (message != null && (message.contains("turnaround_aircraft_busy_uq")
                || message.contains("turnaround_stand_busy_uq"))) {
            return conflict(OpsErrors.AIRCRAFT_BUSY, "Борт или стоянка заняты другим оборотом",
                    "aircraftId", "standId");
        }
        return exception;
    }
}
