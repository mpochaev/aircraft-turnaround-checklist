package ru.miit.uvp411.checklist.ops.internal;

import org.springframework.http.HttpStatus;

import ru.miit.uvp411.checklist.shared.error.ApiException;
import ru.miit.uvp411.checklist.shared.security.CurrentUser;

/** Маршрутизация по таблице 7.2; бизнес-проверки переходов выполняет соответствующий обработчик. */
public enum OperationAction {
    START("not_started", "in_progress", "operations.execute"),
    FINISH("in_progress", "done", "operations.execute"),
    PROBLEM("in_progress", "problem", "operations.execute"),
    RESOLVE("problem", "in_progress", "operations.manage"),
    NOT_REQUIRED("not_started", "not_required", "operations.manage"),
    REVERT(null, null, "operations.manage"),
    DELAY_CODE(null, null, "operations.manage");

    private final String from;
    private final String to;
    private final String permission;

    OperationAction(String from, String to, String permission) {
        this.from = from;
        this.to = to;
        this.permission = permission;
    }

    public static OperationAction resolve(String currentStatus, OperationRequest request) {
        if (request.status() == null && request.delayCodeId() != null) {
            return DELAY_CODE;
        }
        if (("in_progress".equals(currentStatus) && "not_started".equals(request.status()))
                || ("done".equals(currentStatus) && "in_progress".equals(request.status()))) {
            return REVERT;
        }
        for (OperationAction action : values()) {
            if (action.from != null && action.from.equals(currentStatus) && action.to.equals(request.status())) {
                return action;
            }
        }
        throw new ApiException(HttpStatus.CONFLICT, OpsErrors.INVALID_TRANSITION,
                "Недопустимый переход статуса операции");
    }

    public boolean permitted(CurrentUser user, OperationContext context) {
        if (user.can("operations.manage") && this == DELAY_CODE) {
            return true;
        }
        boolean execution = permission.equals("operations.execute") || this == DELAY_CODE;
        if (execution) {
            return user.can("operations.execute") && user.login().equals(context.turnaround().technicianLogin());
        }
        return user.can(permission);
    }
}
