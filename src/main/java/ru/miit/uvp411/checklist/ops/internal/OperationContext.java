package ru.miit.uvp411.checklist.ops.internal;

import java.time.LocalDateTime;
import java.util.List;

/** Снимок операции, оборота и всех непосредственных предшественников для обработчика. */
public record OperationContext(Operation operation, Turnaround turnaround, List<Predecessor> predecessors) {

    public OperationContext {
        predecessors = List.copyOf(predecessors);
    }

    public record Operation(long id, long turnaroundId, int operationId, int normId, int seqNo,
                            int durationMin, boolean mandatory, String status,
                            LocalDateTime plannedStart, LocalDateTime plannedEnd,
                            LocalDateTime actualStart, LocalDateTime actualEnd,
                            Integer executorId, Integer delayCodeId, String comment) {
    }

    public record Turnaround(long id, String status, int technicianId, String technicianLogin) {
    }

    public record Predecessor(long id, String operationCode, String name, String status) {

        public boolean completed() {
            return "done".equals(status) || "not_required".equals(status);
        }
    }
}
