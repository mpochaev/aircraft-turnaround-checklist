package ru.miit.uvp411.checklist.ops.internal;

/** Коды ошибок раздела 7 спецификации API. HTTP-статус задаётся в месте проверки. */
public final class OpsErrors {

    public static final String FLIGHT_EXISTS = "FLIGHT_EXISTS";
    public static final String FLIGHT_ALREADY_USED = "FLIGHT_ALREADY_USED";
    public static final String AIRCRAFT_BUSY = "AIRCRAFT_BUSY";
    public static final String ROUTE_MISMATCH = "ROUTE_MISMATCH";
    public static final String TIME_ORDER = "TIME_ORDER";
    public static final String INACTIVE_REFERENCE = "INACTIVE_REFERENCE";
    public static final String NO_NORMS = "NO_NORMS";
    public static final String INVALID_TRANSITION = "INVALID_TRANSITION";
    public static final String TURNAROUND_NOT_IN_SERVICE = "TURNAROUND_NOT_IN_SERVICE";
    public static final String MANDATORY_NOT_DONE = "MANDATORY_NOT_DONE";
    public static final String PREDECESSORS_NOT_DONE = "PREDECESSORS_NOT_DONE";
    public static final String COMMENT_REQUIRED = "COMMENT_REQUIRED";
    public static final String DELAY_CODE_REQUIRED = "DELAY_CODE_REQUIRED";
    public static final String MANDATORY_OPERATION = "MANDATORY_OPERATION";

    private OpsErrors() {
    }
}
