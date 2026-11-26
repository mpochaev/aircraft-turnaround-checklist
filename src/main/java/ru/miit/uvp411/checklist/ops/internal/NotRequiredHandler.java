package ru.miit.uvp411.checklist.ops.internal;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import ru.miit.uvp411.checklist.shared.error.ApiException;

@Component
class NotRequiredHandler implements OperationActionHandler {

    private final OperationQueries queries;
    private final OpsAudit audit;

    NotRequiredHandler(OperationQueries queries, OpsAudit audit) {
        this.queries = queries;
        this.audit = audit;
    }

    @Override
    public OperationAction action() {
        return OperationAction.NOT_REQUIRED;
    }

    @Override
    public void handle(OperationContext context, OperationRequest request) {
        OperationContext.Operation operation = context.operation();
        if (operation.mandatory()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, OpsErrors.MANDATORY_OPERATION,
                    "Обязательную операцию нельзя пропустить");
        }
        String comment = request.requireComment();
        queries.updateStatusAndComment(operation.id(), "not_required", comment);
        audit.operationStatusWithComment("op_not_required", context, "not_required", comment);
    }
}
