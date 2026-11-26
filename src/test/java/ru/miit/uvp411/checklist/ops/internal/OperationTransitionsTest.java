package ru.miit.uvp411.checklist.ops.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import ru.miit.uvp411.checklist.shared.error.ApiException;
import ru.miit.uvp411.checklist.shared.security.CurrentUser;

class OperationTransitionsTest {

    private static final Set<OperationAction> ALL = EnumSet.allOf(OperationAction.class);
    private static final CurrentUser TECHNICIAN =
            new CurrentUser("tech07", Set.of("technician"), Set.of("operations.execute"));
    private static final CurrentUser SUPERVISOR =
            new CurrentUser("sup01", Set.of("supervisor"), Set.of("operations.execute", "operations.manage"));

    @ParameterizedTest
    @CsvSource({
        "not_started, in_progress, START",
        "in_progress, done, FINISH",
        "in_progress, problem, PROBLEM",
        "problem, in_progress, RESOLVE",
        "not_started, not_required, NOT_REQUIRED",
        "in_progress, not_started, REVERT",
        "done, in_progress, REVERT"
    })
    void routesTransitionsFromTheSpecification(String from, String to, OperationAction expected) {
        assertThat(OperationTransitions.resolve(from, new OperationRequest(to, null, null))).isEqualTo(expected);
    }

    @Test
    void cannotSkipStart() {
        assertThatThrownBy(() -> OperationTransitions.resolve("not_started", new OperationRequest("done", null, null)))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("INVALID_TRANSITION"));
    }

    @Test
    void checklistOffersOnlyActionsMatchingAssignmentStateAndRegisteredHandlers() {
        assertThat(actions("not_started", true, false, "in_service", "tech07", TECHNICIAN, ALL)).containsExactly("start");
        assertThat(actions("not_started", true, true, "in_service", "tech07", TECHNICIAN, ALL)).isEmpty();
        assertThat(actions("not_started", true, false, "planned", "tech07", TECHNICIAN, ALL)).isEmpty();
        assertThat(actions("not_started", true, false, "in_service", "tech12", TECHNICIAN, ALL)).isEmpty();
        assertThat(actions("in_progress", true, false, "in_service", "tech07", TECHNICIAN, ALL))
                .containsExactly("finish", "problem");
        assertThat(actions("in_progress", true, false, "in_service", "tech07", TECHNICIAN, Set.of(OperationAction.START)))
                .isEmpty();
        assertThat(actions("not_started", false, false, "in_service", "tech07", SUPERVISOR, ALL))
                .containsExactly("not_required");
        assertThat(actions("not_started", true, false, "in_service", "tech07", SUPERVISOR, ALL)).isEmpty();
        assertThat(actions("problem", true, false, "in_service", "tech07", SUPERVISOR, ALL)).containsExactly("resolve");
        assertThat(actions("done", true, false, "in_service", "tech07", SUPERVISOR, ALL)).containsExactly("revert");
    }

    private static List<String> actions(String status, boolean mandatory, boolean blocked, String turnaroundStatus,
                                 String technicianLogin, CurrentUser user, Set<OperationAction> handlers) {
        return OperationTransitions.allowedActions(status, mandatory, blocked, turnaroundStatus, technicianLogin,
                user, handlers);
    }
}
