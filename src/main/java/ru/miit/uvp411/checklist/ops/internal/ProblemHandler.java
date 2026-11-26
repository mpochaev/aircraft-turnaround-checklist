package ru.miit.uvp411.checklist.ops.internal;

import org.springframework.stereotype.Component;

@Component
class ProblemHandler implements OperationActionHandler {

    private final OperationQueries queries;
    private final OpsAudit audit;

    ProblemHandler(OperationQueries queries, OpsAudit audit) {
        this.queries = queries;
        this.audit = audit;
    }

    @Override
    public OperationAction action() {
        return OperationAction.PROBLEM;
    }

    @Override
    public void handle(OperationContext context, OperationRequest request) {
        String comment = request.requireComment();
        queries.updateStatusAndComment(context.operation().id(), "problem", comment);
        audit.operationStatusWithComment("op_problem", context, "problem", comment);
    }
}
