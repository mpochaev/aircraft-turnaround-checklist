package ru.miit.uvp411.checklist.ops.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.miit.uvp411.checklist.shared.error.ApiException;
import ru.miit.uvp411.checklist.shared.security.CurrentUser;

@Service
public class TurnaroundTransitions {

    private final TurnaroundQueries queries;
    private final OpsAudit audit;

    public TurnaroundTransitions(TurnaroundQueries queries, OpsAudit audit) {
        this.queries = queries;
        this.audit = audit;
    }

    public List<String> allowedActions(Map<String, Object> turnaround, CurrentUser user) {
        if (user.can("turnarounds.manage")
                && "in_service".equals(turnaround.get("status"))
                && number(turnaround, "mandatoryTotal") > 0
                && number(turnaround, "mandatoryDone") == number(turnaround, "mandatoryTotal")) {
            return List.of("ready");
        }
        return List.of();
    }

    @Transactional
    public void completeWhenMandatoryOperationsDone(long id) {
        Map<String, Object> before = queries.lockForTransition(id)
                .orElseThrow(() -> new IllegalStateException("Оборот не найден при автоматическом завершении"));
        if (!"in_service".equals(before.get("status"))
                || number(before, "mandatoryTotal") == 0
                || number(before, "mandatoryDone") != number(before, "mandatoryTotal")) {
            return;
        }

        queries.markReady(id);
        audit.turnaround("ready", id, before, Map.of("status", "ready"));
    }

    @Transactional
    public Map<String, Object> update(long id, TurnaroundTransitionRequest request) {
        if (!"ready".equals(request.status())) {
            throw invalidTransition();
        }

        CurrentUser user = CurrentUser.get();
        if (!user.can("turnarounds.manage")) {
            throw ApiException.forbidden();
        }

        Map<String, Object> before = queries.lockForTransition(id)
                .orElseThrow(() -> ApiException.notFound("Оборот"));
        if (!allowedActions(before, user).contains("ready")) {
            throw invalidTransition();
        }

        queries.markReady(id);
        Map<String, Object> turnaround = new LinkedHashMap<>(queries.byId(id)
                .orElseThrow(() -> new IllegalStateException("Оборот не найден после перехода")));
        audit.turnaround("ready", id, before, Map.of("status", "ready"));
        turnaround.put("operations", queries.operations(id));
        turnaround.put("allowedActions", allowedActions(turnaround, user));
        return turnaround;
    }

    private static int number(Map<String, Object> values, String key) {
        return ((Number) values.get(key)).intValue();
    }

    private static ApiException invalidTransition() {
        return new ApiException(HttpStatus.CONFLICT, OpsErrors.INVALID_TRANSITION,
                "Оборот нельзя перевести в статус «Готов к вылету»");
    }
}
