package ru.miit.uvp411.checklist.ops.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import ru.miit.uvp411.checklist.shared.error.ApiException;
import ru.miit.uvp411.checklist.shared.security.CurrentUser;

class OperationServiceTest {

    @AfterEach
    void clearUser() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void duplicateHandlersAreRejectedAtStartup() {
        OperationActionHandler first = handler(OperationAction.NOT_REQUIRED);
        OperationActionHandler second = handler(OperationAction.NOT_REQUIRED);
        assertThatThrownBy(() -> new OperationService(new OperationQueries(null), List.of(first, second)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("NOT_REQUIRED");
    }

    @Test
    void serviceChecksPermissionsEvenWhenCalledWithoutController() {
        OperationQueries queries = new OperationQueries(null) {
            @Override
            public Optional<OperationContext> lockById(long id) {
                throw new AssertionError("Без права нельзя обращаться к данным операции");
            }
        };
        OperationService service = new OperationService(queries, List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("reader", null, List.of()));

        assertThatThrownBy(() -> service.update(1, new OperationRequest("in_progress", null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("FORBIDDEN"));
    }

    @Test
    void resumingProblemRequiresManageEvenThoughStartingUsesTheSameTargetStatus() {
        OperationRequest request = new OperationRequest("in_progress", "Исправлено", null);
        OperationContext context = new OperationContext(null,
                new OperationContext.Turnaround(7, "in_service", 1, "tech07"), List.of());
        CurrentUser technician = new CurrentUser("tech07", Set.of("technician"), Set.of("operations.execute"));
        CurrentUser supervisor = new CurrentUser("sup01", Set.of("supervisor"), Set.of("operations.manage"));

        assertThat(OperationAction.resolve("not_started", request).permitted(technician, context)).isTrue();
        assertThat(OperationAction.resolve("problem", request).permitted(technician, context)).isFalse();
        assertThat(OperationAction.resolve("problem", request).permitted(supervisor, context)).isTrue();
    }

    @Test
    void standaloneDelayCodeIsAllowedForAssignedTechnicianOrManager() {
        OperationContext context = new OperationContext(null,
                new OperationContext.Turnaround(7, "in_service", 1, "tech07"), List.of());
        OperationAction action = OperationAction.resolve("done", new OperationRequest(null, null, 1));

        assertThat(action.permitted(new CurrentUser("tech07", Set.of(), Set.of("operations.execute")), context)).isTrue();
        assertThat(action.permitted(new CurrentUser("tech12", Set.of(), Set.of("operations.execute")), context)).isFalse();
        assertThat(action.permitted(new CurrentUser("sup01", Set.of(), Set.of("operations.manage")), context)).isTrue();
    }

    private static OperationActionHandler handler(OperationAction action) {
        return new OperationActionHandler() {
            @Override
            public OperationAction action() {
                return action;
            }

            @Override
            public void handle(OperationContext context, OperationRequest request) {
                throw new UnsupportedOperationException("Не вызывается в тесте регистрации");
            }
        };
    }
}
