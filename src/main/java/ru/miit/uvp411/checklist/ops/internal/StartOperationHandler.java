package ru.miit.uvp411.checklist.ops.internal;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import ru.miit.uvp411.checklist.shared.error.ApiError;
import ru.miit.uvp411.checklist.shared.error.ApiException;

@Component
class StartOperationHandler implements OperationActionHandler {

    private final OperationQueries queries;
    private final OpsAudit audit;

    StartOperationHandler(OperationQueries queries, OpsAudit audit) {
        this.queries = queries;
        this.audit = audit;
    }

    @Override
    public OperationAction action() {
        return OperationAction.START;
    }

    @Override
    public void handle(OperationContext context, OperationRequest request) {
        List<OperationContext.Predecessor> blockers = context.predecessors().stream()
                .filter(predecessor -> !predecessor.completed())
                .toList();
        if (!blockers.isEmpty()) {
            String names = blockers.stream().map(OperationContext.Predecessor::name)
                    .collect(Collectors.joining(", "));
            List<ApiError.Detail> details = blockers.stream()
                    .map(predecessor -> new ApiError.Detail(null, predecessor.id(),
                            predecessor.name() + " — операция не завершена"))
                    .toList();
            throw new ApiException(HttpStatus.CONFLICT, OpsErrors.PREDECESSORS_NOT_DONE,
                    "Сначала завершите: " + names, details);
        }
        if (request.delayCodeId() != null) {
            throw ApiException.validation("delayCodeId", request.delayCodeId(),
                    "При начале операции код задержки не указывается");
        }

        OperationContext.Operation operation = context.operation();
        int executorId = context.turnaround().technicianId();
        LocalDateTime actualStart = queries.start(operation.id(), executorId);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("status", operation.status());
        before.put("actualStart", operation.actualStart());
        before.put("executorId", operation.executorId());
        audit.operation("op_start", operation.id(), context.turnaround().id(), before,
                Map.of("status", "in_progress", "actualStart", actualStart, "executorId", executorId));
    }
}
