package ru.miit.uvp411.checklist.ops.internal;

import java.util.EnumMap;
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
        OperationAction action = OperationAction.resolve(context.operation().status(), request);
        if (!action.permitted(user, context)) {
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
        return Map.of("operation", queries.operation(id), "turnaround", queries.turnaround(context.turnaround().id()));
    }
}
