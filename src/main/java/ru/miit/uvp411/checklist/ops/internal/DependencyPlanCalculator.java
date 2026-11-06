package ru.miit.uvp411.checklist.ops.internal;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import org.springframework.stereotype.Component;

import ru.miit.uvp411.checklist.ops.PlanCalculator;

@Component
class DependencyPlanCalculator implements PlanCalculator {

    private static final Comparator<Norm> ORDER = Comparator.comparingInt(Norm::seqNo)
            .thenComparing(Norm::operationCode);

    @Override
    public List<PlannedOperation> calculate(LocalDateTime startAt, List<Norm> norms,
                                             Set<String> notRequiredOperations) {
        if (startAt == null || norms == null || notRequiredOperations == null) {
            throw new IllegalArgumentException("Для расчёта нужны начало, нормативы и список необязательных операций");
        }

        Map<String, Norm> byCode = new HashMap<>();
        Map<String, Integer> incoming = new HashMap<>();
        Map<String, List<String>> successors = new HashMap<>();
        for (Norm norm : norms) {
            if (norm.operationCode() == null || norm.seqNo() < 1 || norm.durationMin() < 1
                    || byCode.putIfAbsent(norm.operationCode(), norm) != null) {
                throw new IllegalArgumentException("Некорректный или повторяющийся норматив операции");
            }
            incoming.put(norm.operationCode(), 0);
            successors.put(norm.operationCode(), new ArrayList<>());
        }

        for (Norm norm : norms) {
            Set<String> uniquePredecessors = new HashSet<>();
            for (String predecessor : norm.after()) {
                if (!byCode.containsKey(predecessor) || !uniquePredecessors.add(predecessor)) {
                    throw new IllegalArgumentException("Неизвестная или повторяющаяся зависимость операции "
                            + norm.operationCode());
                }
                incoming.compute(norm.operationCode(), (code, count) -> count + 1);
                successors.get(predecessor).add(norm.operationCode());
            }
        }

        for (String code : notRequiredOperations) {
            Norm norm = byCode.get(code);
            if (norm == null || norm.mandatory()) {
                throw new IllegalArgumentException("Необязательной может быть только существующая операция");
            }
        }

        PriorityQueue<Norm> ready = new PriorityQueue<>(ORDER);
        norms.stream().filter(norm -> incoming.get(norm.operationCode()) == 0).forEach(ready::add);
        List<Norm> ordered = new ArrayList<>(norms.size());
        Map<String, PlannedOperation> plannedByCode = new HashMap<>();
        while (!ready.isEmpty()) {
            Norm norm = ready.remove();
            LocalDateTime plannedStart = norm.after().stream()
                    .filter(predecessor -> !notRequiredOperations.contains(predecessor))
                    .map(predecessor -> plannedByCode.get(predecessor).plannedEnd())
                    .max(LocalDateTime::compareTo)
                    .filter(end -> end.isAfter(startAt))
                    .orElse(startAt);
            PlannedOperation operation = new PlannedOperation(norm.id(), norm.operationCode(), norm.seqNo(),
                    norm.durationMin(), norm.mandatory(), plannedStart, plannedStart.plusMinutes(norm.durationMin()),
                    norm.after());
            plannedByCode.put(norm.operationCode(), operation);
            ordered.add(norm);

            for (String successor : successors.get(norm.operationCode())) {
                int remaining = incoming.compute(successor, (code, count) -> count - 1);
                if (remaining == 0) {
                    ready.add(byCode.get(successor));
                }
            }
        }
        if (ordered.size() != norms.size()) {
            throw new IllegalArgumentException("В зависимостях нормативов обнаружен цикл");
        }

        return ordered.stream()
                .sorted(ORDER)
                .map(norm -> plannedByCode.get(norm.operationCode()))
                .toList();
    }
}
