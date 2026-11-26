package ru.miit.uvp411.checklist.ops.internal;

import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;

import ru.miit.uvp411.checklist.shared.error.ApiException;
import ru.miit.uvp411.checklist.shared.security.CurrentUser;

/** Переходы операции по рисунку А.3 и таблице 7.2. */
public final class OperationTransitions {

    private static final List<Transition> TRANSITIONS = List.of(
            new Transition("not_started", "in_progress", OperationAction.START),
            new Transition("in_progress", "done", OperationAction.FINISH),
            new Transition("in_progress", "problem", OperationAction.PROBLEM),
            new Transition("problem", "in_progress", OperationAction.RESOLVE),
            new Transition("not_started", "not_required", OperationAction.NOT_REQUIRED),
            new Transition("in_progress", "not_started", OperationAction.REVERT),
            new Transition("done", "in_progress", OperationAction.REVERT));

    private OperationTransitions() {
    }

    public static OperationAction resolve(String status, OperationRequest request) {
        if (request.status() == null && request.delayCodeId() != null) {
            return OperationAction.DELAY_CODE;
        }
        return TRANSITIONS.stream()
                .filter(transition -> transition.from().equals(status) && transition.to().equals(request.status()))
                .map(Transition::action)
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, OpsErrors.INVALID_TRANSITION,
                        "Недопустимый переход статуса операции"));
    }

    static List<String> allowedActions(String status, boolean mandatory, boolean waitingForPredecessors,
                                       String turnaroundStatus, String technicianLogin, CurrentUser user,
                                       Set<OperationAction> implementedActions) {
        if (!"in_service".equals(turnaroundStatus)) {
            return List.of();
        }
        return TRANSITIONS.stream()
                .filter(transition -> transition.from().equals(status))
                .map(Transition::action)
                .filter(implementedActions::contains)
                .filter(action -> action.permitted(user, technicianLogin))
                .filter(action -> action != OperationAction.START || !waitingForPredecessors)
                .filter(action -> action != OperationAction.NOT_REQUIRED || !mandatory)
                .map(OperationAction::code)
                .toList();
    }

    private record Transition(String from, String to, OperationAction action) {
    }
}
