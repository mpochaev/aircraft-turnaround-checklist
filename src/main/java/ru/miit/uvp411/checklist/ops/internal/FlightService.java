package ru.miit.uvp411.checklist.ops.internal;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.miit.uvp411.checklist.log.AuditLog;
import ru.miit.uvp411.checklist.ref.RefApi;
import ru.miit.uvp411.checklist.shared.error.ApiException;

@Service
public class FlightService {

    private final FlightQueries queries;
    private final RefApi refApi;
    private final AuditLog auditLog;

    public FlightService(FlightQueries queries, RefApi refApi, AuditLog auditLog) {
        this.queries = queries;
        this.refApi = refApi;
        this.auditLog = auditLog;
    }

    @Transactional
    public Map<String, Object> create(FlightRequest request) {
        requireActive("airlines", request.airlineId(), "airlineId");
        requireActive("airports", request.depAirportId(), "depAirportId");
        requireActive("airports", request.arrAirportId(), "arrAirportId");
        if (request.depAirportId().equals(request.arrAirportId())) {
            throw ApiException.validation("arrAirportId", request.arrAirportId(), "Аэропорты вылета и прилёта должны различаться");
        }

        long id;
        try {
            id = queries.insert(request);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "FLIGHT_EXISTS",
                    "Рейс с таким ключом уже существует");
        }

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("airlineId", request.airlineId());
        values.put("flightNumber", request.flightNumber());
        values.put("letter", request.normalizedLetter());
        values.put("flightDate", request.flightDate());
        values.put("depAirportId", request.depAirportId());
        values.put("arrAirportId", request.arrAirportId());
        values.put("std", request.std());
        values.put("sta", request.sta());
        auditLog.record(new AuditLog.Entry("flight_created", "flight", id, null, null, values));
        return queries.byId(id);
    }

    private void requireActive(String dictionary, int id, String field) {
        if (!refApi.isActive(dictionary, id)) {
            throw ApiException.validation(field, id, "Выберите активную запись справочника");
        }
    }
}
