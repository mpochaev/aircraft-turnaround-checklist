package ru.miit.uvp411.checklist.ops.internal;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ru.miit.uvp411.checklist.log.ActionTypes;
import ru.miit.uvp411.checklist.log.AuditLog;

/** Общая запись событий ops в транзакции изменения, без отдельного коммита журнала. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class OpsAudit {

    private final AuditLog auditLog;

    public OpsAudit(AuditLog auditLog) {
        this.auditLog = auditLog;
    }

    /** Принимает любой существующий код ActionTypes; новые коды добавляет команда «Данные». */
    public void record(String actionType, String entity, Long entityId, Long turnaroundId,
                       Map<String, Object> before, Map<String, Object> after) {
        if (!ActionTypes.exists(actionType)) {
            throw new IllegalArgumentException("Неизвестный вид действия журнала: " + actionType);
        }
        auditLog.record(new AuditLog.Entry(actionType, entity, entityId, turnaroundId,
                snapshot(before), snapshot(after)));
    }

    public void flightCreated(long flightId, Map<String, Object> values) {
        record("flight_created", "flight", flightId, null, null, values);
    }

    public void turnaround(String actionType, long turnaroundId,
                           Map<String, Object> before, Map<String, Object> after) {
        record(actionType, "turnaround", turnaroundId, turnaroundId, before, after);
    }

    public void operation(String actionType, long operationId, long turnaroundId,
                          Map<String, Object> before, Map<String, Object> after) {
        record(actionType, "operation_exec", operationId, turnaroundId, before, after);
    }

    private static Map<String, Object> snapshot(Map<String, Object> values) {
        // Фактические времена, исполнитель и комментарий могут быть null.
        return values == null ? null : new LinkedHashMap<>(values);
    }
}
