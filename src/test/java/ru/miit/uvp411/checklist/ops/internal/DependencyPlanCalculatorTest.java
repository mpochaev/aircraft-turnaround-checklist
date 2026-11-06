package ru.miit.uvp411.checklist.ops.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ru.miit.uvp411.checklist.ops.PlanCalculator.Norm;
import ru.miit.uvp411.checklist.ops.PlanCalculator.PlannedOperation;

class DependencyPlanCalculatorTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 6, 10, 2);

    private final DependencyPlanCalculator calculator = new DependencyPlanCalculator();

    @Test
    void calculatesA320PlanWhenDeicingIsNotRequired() {
        List<PlannedOperation> plan = calculator.calculate(START, norms("A320"), Set.of("DEICE"));

        assertEquals(LocalDateTime.of(2026, 11, 6, 10, 58), operation(plan, "CLOSE").plannedEnd());
    }

    @Test
    void calculatesA320PlanWhenDeicingIsPerformed() {
        List<PlannedOperation> plan = calculator.calculate(START, norms("A320"), Set.of());

        assertEquals(LocalDateTime.of(2026, 11, 6, 11, 8), operation(plan, "CLOSE").plannedEnd());
    }

    @Test
    void calculatesB777PlanWhenDeicingIsNotRequired() {
        List<PlannedOperation> plan = calculator.calculate(START, norms("B777"), Set.of("DEICE"));

        assertEquals(LocalDateTime.of(2026, 11, 6, 11, 37), operation(plan, "CLOSE").plannedEnd());
    }

    @Test
    void existingPlanKeepsItsNormSnapshotAfterFuelNormChanges() {
        List<Norm> originalNorms = norms("A320");
        List<PlannedOperation> existingPlan = calculator.calculate(START, originalNorms, Set.of("DEICE"));

        List<Norm> changedNorms = new ArrayList<>(originalNorms);
        changedNorms.replaceAll(norm -> norm.operationCode().equals("FUEL")
                ? new Norm(norm.id(), norm.operationCode(), norm.seqNo(), 25, norm.mandatory(), norm.after())
                : norm);
        List<PlannedOperation> newPlan = calculator.calculate(START, changedNorms, Set.of("DEICE"));

        assertEquals(20, operation(existingPlan, "FUEL").durationMin());
        assertEquals(LocalDateTime.of(2026, 11, 6, 10, 58), operation(existingPlan, "CLOSE").plannedEnd());
        assertEquals(25, operation(newPlan, "FUEL").durationMin());
        assertEquals(LocalDateTime.of(2026, 11, 6, 11, 3), operation(newPlan, "CLOSE").plannedEnd());
    }

    private static PlannedOperation operation(List<PlannedOperation> plan, String code) {
        return plan.stream().filter(item -> item.operationCode().equals(code)).findFirst().orElseThrow();
    }

    private static List<Norm> norms(String type) {
        boolean a320 = type.equals("A320");
        return List.of(
                new Norm(1, "STAIRS", 1, a320 ? 3 : 5, true, List.of()),
                new Norm(2, "DEBOARD", 2, a320 ? 10 : 20, true, List.of("STAIRS")),
                new Norm(3, "UNLOAD", 3, a320 ? 15 : 25, true, List.of()),
                new Norm(4, "INSPECT", 4, a320 ? 20 : 30, true, List.of()),
                new Norm(5, "WATER", 5, a320 ? 10 : 15, true, List.of()),
                new Norm(6, "FUEL", 6, a320 ? 20 : 35, true, List.of("DEBOARD")),
                new Norm(7, "CLEAN", 7, a320 ? 15 : 25, true, List.of("DEBOARD")),
                new Norm(8, "CATERING", 8, a320 ? 10 : 20, true, List.of("DEBOARD")),
                new Norm(9, "LOAD", 9, a320 ? 15 : 25, true, List.of("UNLOAD")),
                new Norm(10, "BOARD", 10, a320 ? 20 : 30, true, List.of("INSPECT", "FUEL", "CLEAN", "CATERING")),
                new Norm(11, "DEICE", 11, a320 ? 10 : 15, false, List.of("BOARD")),
                new Norm(12, "CLOSE", 12, a320 ? 3 : 5, true, List.of("LOAD", "BOARD", "DEICE")));
    }
}
