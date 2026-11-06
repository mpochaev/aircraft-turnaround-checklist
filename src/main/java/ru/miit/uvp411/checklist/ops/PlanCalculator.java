package ru.miit.uvp411.checklist.ops;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/** Расчёт плана чек-листа по снимку нормативов и зависимостей. */
public interface PlanCalculator {

    List<PlannedOperation> calculate(LocalDateTime startAt, List<Norm> norms, Set<String> notRequiredOperations);

    record Norm(long id, String operationCode, int seqNo, int durationMin, boolean mandatory, List<String> after) {
        public Norm {
            after = List.copyOf(after);
        }
    }

    record PlannedOperation(long normId, String operationCode, int seqNo, int durationMin, boolean mandatory,
                            LocalDateTime plannedStart, LocalDateTime plannedEnd, List<String> after) {
        public PlannedOperation {
            after = List.copyOf(after);
        }
    }
}
