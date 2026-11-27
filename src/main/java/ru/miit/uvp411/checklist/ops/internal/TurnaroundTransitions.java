package ru.miit.uvp411.checklist.ops.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
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
        if ("planned".equals(turnaround.get("status")) && user.can("turnarounds.service")) {
            return List.of("in_service");
        }
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
        boolean onBlock = "in_service".equals(request.status());
        if (!onBlock && !"ready".equals(request.status())) {
            throw invalidTransition();
        }

        CurrentUser user = CurrentUser.get();
        if (onBlock ? !user.can("turnarounds.service") : !user.can("turnarounds.manage")) {
            throw ApiException.forbidden();
        }

        Map<String, Object> before = queries.lockForTransition(id)
                .orElseThrow(() -> ApiException.notFound("Оборот"));
        if (onBlock && !user.roles().contains("supervisor")
                && !user.login().equals(before.get("technicianLogin"))) {
            throw ApiException.forbidden();
        }
        if (!allowedActions(before, user).contains(request.status())) {
            throw invalidTransition();
        }

        if (onBlock) {
            try {
                queries.markInService(id);
            } catch (DataIntegrityViolationException e) {
                throw translateConstraint(e);
            }
            queries.shiftPlanToActualOnBlock(id);
        } else {
            queries.markReady(id);
        }
        Map<String, Object> turnaround = new LinkedHashMap<>(queries.byId(id)
                .orElseThrow(() -> new IllegalStateException("Оборот не найден после перехода")));
        String action = onBlock ? "on_block" : "ready";
        audit.turnaround(action, id, before, Map.of("status", request.status()));
        turnaround.put("operations", queries.operations(id));
        turnaround.put("allowedActions", allowedActions(turnaround, user));
        return turnaround;
    }

    private static int number(Map<String, Object> values, String key) {
        return ((Number) values.get(key)).intValue();
    }

    private static ApiException invalidTransition() {
        return new ApiException(HttpStatus.CONFLICT, OpsErrors.INVALID_TRANSITION,
                "Недопустимый переход статуса оборота");
    }

    private static RuntimeException translateConstraint(DataIntegrityViolationException exception) {
        String message = exception.getMostSpecificCause().getMessage();
        if (message != null && (message.contains("turnaround_aircraft_busy_uq")
                || message.contains("turnaround_stand_busy_uq"))) {
            return new ApiException(HttpStatus.CONFLICT, OpsErrors.AIRCRAFT_BUSY,
                    "Борт или стоянка заняты другим оборотом");
        }
        return exception;
    }
}
