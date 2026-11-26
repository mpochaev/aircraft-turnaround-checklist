package ru.miit.uvp411.checklist.ops.internal;

import ru.miit.uvp411.checklist.shared.security.CurrentUser;

/** Коды действий для чек-листа и права на их выполнение. */
public enum OperationAction {
    START("start", "operations.execute"),
    FINISH("finish", "operations.execute"),
    PROBLEM("problem", "operations.execute"),
    RESOLVE("resolve", "operations.manage"),
    NOT_REQUIRED("not_required", "operations.manage"),
    REVERT("revert", "operations.manage"),
    DELAY_CODE("delay_code", "operations.manage");

    private final String code;
    private final String permission;

    OperationAction(String code, String permission) {
        this.code = code;
        this.permission = permission;
    }

    public String code() {
        return code;
    }

    public boolean permitted(CurrentUser user, String technicianLogin) {
        if (user.can("operations.manage") && this == DELAY_CODE) {
            return true;
        }
        boolean execution = permission.equals("operations.execute") || this == DELAY_CODE;
        if (execution) {
            return user.can("operations.execute") && user.login().equals(technicianLogin);
        }
        return user.can(permission);
    }
}
