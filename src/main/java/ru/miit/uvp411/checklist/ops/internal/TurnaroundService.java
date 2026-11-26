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
import ru.miit.uvp411.checklist.auth.AuthApi;
import ru.miit.uvp411.checklist.ref.RefApi;
import ru.miit.uvp411.checklist.shared.error.ApiError;
import ru.miit.uvp411.checklist.shared.error.ApiException;
import ru.miit.uvp411.checklist.shared.security.CurrentUser;

@Service
public class TurnaroundService {

    private final TurnaroundQueries queries;
    private final RefApi refApi;
    private final PlanCalculator planCalculator;
    private final OpsAudit audit;
    private final AuthApi authApi;

    public TurnaroundService(TurnaroundQueries queries, RefApi refApi,
                             PlanCalculator planCalculator, OpsAudit audit, AuthApi authApi) {
        this.queries = queries;
        this.refApi = refApi;
        this.planCalculator = planCalculator;
        this.audit = audit;
        this.authApi = authApi;
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

    @Transactional
    public void update(long id, TurnaroundPatchRequest request) {
        if (request == null) {
            throw unprocessable("VALIDATION_ERROR", "Тело запроса обязательно", "status", "technicianId");
        }
        CurrentUser user = CurrentUser.get();
        if ((request.status() != null || request.technicianId() != null)
                && !user.can("turnarounds.manage")) {
            throw ApiException.forbidden();
        }
        if (request.status() == null && request.technicianId() == null) {
            throw unprocessable("VALIDATION_ERROR", "Укажите status или technicianId", "status", "technicianId");
        }
        if (request.status() != null && !"cancelled".equals(request.status())) {
            throw new ApiException(HttpStatus.CONFLICT, OpsErrors.INVALID_TRANSITION,
                    "Для этого запроса допустима только отмена оборота");
        }
        if (request.status() == null && request.cancelReason() != null) {
            throw unprocessable("VALIDATION_ERROR", "Причина отмены допустима только при отмене", "cancelReason");
        }
        if (request.status() != null
                && (request.cancelReason() == null || request.cancelReason().isBlank()
                || request.cancelReason().codePointCount(0, request.cancelReason().length()) > 300)) {
            throw unprocessable("VALIDATION_ERROR", "Укажите причину отмены длиной от 1 до 300 символов",
                    "cancelReason");
        }
        if (request.technicianId() != null && request.technicianId() <= 0) {
            throw unprocessable("VALIDATION_ERROR", "Идентификатор техника должен быть положительным", "technicianId");
        }

        TurnaroundQueries.LockedTurnaround turnaround = queries.lockById(id)
                .orElseThrow(() -> ApiException.notFound("Оборот"));
        boolean cancelling = "cancelled".equals(request.status());
        if (cancelling && !"planned".equals(turnaround.status())) {
            throw invalidTransition("Отменить можно только запланированный оборот");
        }
        if (request.technicianId() != null
                && ("closed".equals(turnaround.status()) || "cancelled".equals(turnaround.status()))) {
            throw invalidTransition("Нельзя менять техника после закрытия или отмены оборота");
        }

        Integer technicianId = request.technicianId();
        if (technicianId != null && technicianId != turnaround.technicianId()) {
            requireTechnician(technicianId);
            queries.updateTechnician(id, technicianId);
            audit.turnaround("technician_changed", id,
                    Map.of("technicianId", turnaround.technicianId()),
                    Map.of("technicianId", technicianId));
        }
        if (cancelling) {
            queries.cancel(id, request.cancelReason());
            audit.turnaround("turnaround_cancelled", id,
                    Map.of("status", turnaround.status()),
                    Map.of("status", "cancelled", "cancelReason", request.cancelReason()));
        }
    }

    private static ApiException invalidTransition(String message) {
        return new ApiException(HttpStatus.CONFLICT, OpsErrors.INVALID_TRANSITION, message);
    }

    private void requireActive(String dictionary, long id, String field) {
        if (!refApi.isActive(dictionary, id)) {
            throw inactiveReference(field, id);
        }
    }

    private void requireTechnician(int technicianId) {
        if (!queries.technicianActive(technicianId).orElse(false)
                || !authApi.hasPermission(technicianId, "turnarounds.service")) {
            throw inactiveReference("technicianId", technicianId);
        }
    }

    private static Norm toNorm(Map<String, Object> values) {
        Object rawAfter = values.get("after");
        // RefApi отдаёт предшественников списком кодов; строку через запятую тоже понимаем
        List<String> after = rawAfter instanceof java.util.Collection<?> c
                ? c.stream().map(String::valueOf).toList()
                : rawAfter == null || rawAfter.toString().isBlank()
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
