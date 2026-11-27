package ru.miit.uvp411.checklist.ops.internal;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import ru.miit.uvp411.checklist.ref.RefApi;
import ru.miit.uvp411.checklist.shared.error.ApiException;

@Component
class FinishHandler implements OperationActionHandler {

    private static final int DELAY_THRESHOLD_MINUTES = 10;

    private final OperationQueries queries;
    private final RefApi ref;
    private final OpsAudit audit;
    private final TurnaroundTransitions turnaroundTransitions;

    FinishHandler(OperationQueries queries, RefApi ref, OpsAudit audit, TurnaroundTransitions turnaroundTransitions) {
        this.queries = queries;
        this.ref = ref;
        this.audit = audit;
        this.turnaroundTransitions = turnaroundTransitions;
    }

    @Override
    public OperationAction action() {
        return OperationAction.FINISH;
    }

    @Override
    public void handle(OperationContext context, OperationRequest request) {
        OperationContext.Operation operation = context.operation();
        LocalDateTime actualEnd = queries.currentTime();
        Integer requestedCode = request.delayCodeId();
        if (requestedCode != null && !ref.isActive("delay-codes", requestedCode)) {
            throw ApiException.validation("delayCodeId", requestedCode, "Выберите действующий код задержки");
        }
        Integer delayCodeId = requestedCode != null ? requestedCode : operation.delayCodeId();
        if (actualEnd.isAfter(operation.plannedEnd().plusMinutes(DELAY_THRESHOLD_MINUTES))
                && requestedCode == null && (delayCodeId == null || !ref.isActive("delay-codes", delayCodeId))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, OpsErrors.DELAY_CODE_REQUIRED,
                    "Укажите код задержки при отставании больше " + DELAY_THRESHOLD_MINUTES + " минут");
        }

        int executorId = context.turnaround().technicianId();
        queries.finish(operation.id(), actualEnd, executorId, delayCodeId);
        audit.operation("op_finish", operation.id(), context.turnaround().id(),
                values(operation.status(), operation.actualEnd(), operation.executorId(), operation.delayCodeId()),
                values("done", actualEnd, executorId, delayCodeId));
        if (!Objects.equals(operation.delayCodeId(), delayCodeId)) {
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("delayCodeId", operation.delayCodeId());
            audit.operation("delay_code", operation.id(), context.turnaround().id(),
                    before, Map.of("delayCodeId", delayCodeId));
        }
        turnaroundTransitions.completeWhenMandatoryOperationsDone(context.turnaround().id());
    }

    private static Map<String, Object> values(String status, LocalDateTime actualEnd, Integer executorId,
                                              Integer delayCodeId) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", status);
        values.put("actualEnd", actualEnd);
        values.put("executorId", executorId);
        values.put("delayCodeId", delayCodeId);
        return values;
    }
}
