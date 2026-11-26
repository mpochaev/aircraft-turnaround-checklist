package ru.miit.uvp411.checklist.ops.internal;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.miit.uvp411.checklist.shared.error.ApiException;
import ru.miit.uvp411.checklist.shared.security.CurrentUser;

@Service
public class OperationService {

    private final OperationQueries queries;
    private final Map<OperationAction, OperationActionHandler> handlers;

    public OperationService(OperationQueries queries, List<OperationActionHandler> handlers) {
        this.queries = queries;
        EnumMap<OperationAction, OperationActionHandler> registered = new EnumMap<>(OperationAction.class);
        for (OperationActionHandler handler : handlers) {
            if (registered.putIfAbsent(handler.action(), handler) != null) {
                throw new IllegalArgumentException("Повторный обработчик действия " + handler.action());
            }
        }
        this.handlers = Map.copyOf(registered);
    }

    @Transactional
    public Map<String, Object> update(long id, OperationRequest request) {
        CurrentUser user = CurrentUser.get();
        if (!user.can("operations.execute") && !user.can("operations.manage")) {
            throw ApiException.forbidden();
        }
        OperationContext context = queries.lockById(id).orElseThrow(() -> ApiException.notFound("Операция"));
        OperationAction action = OperationTransitions.resolve(context.operation().status(), request);
        if (!action.permitted(user, context.turnaround().technicianLogin())) {
            throw ApiException.forbidden();
        }
        if (!"in_service".equals(context.turnaround().status())) {
            throw new ApiException(HttpStatus.CONFLICT, OpsErrors.TURNAROUND_NOT_IN_SERVICE,
                    "Оборот должен находиться в статусе «Обслуживание»");
        }
        OperationActionHandler handler = handlers.get(action);
        if (handler == null) {
            throw new ApiException(HttpStatus.CONFLICT, OpsErrors.INVALID_TRANSITION,
                    "Обработчик действия ещё не подключён");
        }
        handler.handle(context, request);
        Map<String, Object> turnaround = queries.turnaround(context.turnaround().id());
        return Map.of("operation", withAllowedActions(queries.operation(id), (String) turnaround.get("status"),
                context.turnaround().technicianLogin(), user), "turnaround", turnaround);
    }

    public Map<String, Object> withAllowedActions(Map<String, Object> operation, String turnaroundStatus,
                                                  String technicianLogin, CurrentUser user) {
        Map<String, Object> result = new LinkedHashMap<>(operation);
        List<?> waitsFor = (List<?>) operation.get("waitsFor");
        result.put("allowedActions", OperationTransitions.allowedActions((String) operation.get("status"),
                Boolean.TRUE.equals(operation.get("isMandatory")), !waitsFor.isEmpty(),
                turnaroundStatus, technicianLogin, user, handlers.keySet()));
        return result;
    }
}
