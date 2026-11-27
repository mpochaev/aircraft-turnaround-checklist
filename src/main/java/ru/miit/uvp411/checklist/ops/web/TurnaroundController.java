package ru.miit.uvp411.checklist.ops.web;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ru.miit.uvp411.checklist.ops.internal.OperationService;
import ru.miit.uvp411.checklist.ops.internal.TurnaroundQueries;
import ru.miit.uvp411.checklist.ops.internal.TurnaroundPatchRequest;
import ru.miit.uvp411.checklist.ops.internal.TurnaroundRequest;
import ru.miit.uvp411.checklist.ops.internal.TurnaroundService;
import ru.miit.uvp411.checklist.shared.error.ApiError;
import ru.miit.uvp411.checklist.shared.error.ApiException;
import ru.miit.uvp411.checklist.shared.security.CurrentUser;
import ru.miit.uvp411.checklist.shared.web.Paging;

/**
 * Обороты, раздел 7 спецификации API.
 * Чтение списка и карточки оборота.
 */
@RestController
@RequestMapping("/api/v1/turnarounds")
class TurnaroundController {

    private static final int MAX_DAYS = 92;
    private static final Set<String> STATUSES = Set.of("planned", "in_service", "ready", "closed", "cancelled");

    private final TurnaroundQueries queries;
    private final OperationService operationService;
    private final TurnaroundService turnaroundService;

    TurnaroundController(TurnaroundQueries queries, OperationService operationService,
                         TurnaroundService turnaroundService) {
        this.queries = queries;
        this.operationService = operationService;
        this.turnaroundService = turnaroundService;
    }

    /**
     * Супервайзер (право turnarounds.read_all) видит все обороты, техник только назначенные ему.
     * Это проверка на уровне данных: право на метод есть у всех, а что вернуть, решает сервер.
     */
    @GetMapping
    Map<String, Object> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) Integer airlineId,
            @RequestParam(required = false) Integer aircraftId,
            @RequestParam(required = false) Integer aircraftTypeId,
            @RequestParam(required = false) String flightNumber,
            @RequestParam(required = false) String standFrom,
            @RequestParam(required = false) String standTo,
            @RequestParam(defaultValue = "false") boolean onlyIssues,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        LocalDate[] period = period(dateFrom, dateTo, date);
        validateStatuses(status);
        validatePositive("airlineId", airlineId);
        validatePositive("aircraftId", aircraftId);
        validatePositive("aircraftTypeId", aircraftTypeId);
        if (flightNumber != null && !flightNumber.matches("\\d{1,4}")) {
            throw ApiException.validation("flightNumber", flightNumber, "Номер рейса должен содержать от 1 до 4 цифр");
        }
        if (standFrom != null && standTo != null && standTo.compareTo(standFrom) < 0) {
            throw ApiException.validation("standTo", standTo, "Конец диапазона стоянок раньше начала");
        }

        CurrentUser user = CurrentUser.get();
        String onlyTechnician = user.can("turnarounds.read_all") ? null : user.login();
        Paging paging = Paging.of(page, size);
        TurnaroundQueries.Page result = queries.find(new TurnaroundQueries.Filter(
                period[0], period[1], status, airlineId, aircraftId, aircraftTypeId,
                flightNumber, standFrom, standTo,
                onlyIssues, onlyTechnician), paging);
        return Map.of("items", result.items().stream().map(item -> withOperationActions(item, user)).toList(),
                "total", result.total(), "summary", result.summary());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('turnarounds.create')")
    ResponseEntity<Map<String, Object>> create(@Valid @RequestBody TurnaroundRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(turnaroundService.create(request));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('turnarounds.manage', 'turnarounds.service')")
    Map<String, Object> update(@PathVariable long id, @RequestBody TurnaroundPatchRequest request) {
        if (id <= 0) {
            throw ApiException.validation("id", id, "Идентификатор должен быть положительным");
        }
        turnaroundService.update(id, request);
        CurrentUser user = CurrentUser.get();
        Map<String, Object> row = queries.row(id).orElseThrow(() -> ApiException.notFound("Оборот"));
        return withOperationActions(row, user);
    }

    @GetMapping("/{id}")
    Map<String, Object> one(@PathVariable long id) {
        CurrentUser user = CurrentUser.get();
        Map<String, Object> t = queries.byId(id).orElseThrow(() -> ApiException.notFound("Оборот"));
        if (!user.can("turnarounds.read_all") && !user.login().equals(t.get("technicianLogin"))) {
            throw ApiException.notFound("Оборот");   // спецификация 7.5: чужой оборот выглядит как несуществующий
        }
        Map<String, Object> body = new LinkedHashMap<>(t);
        body.put("operations", queries.operations(id).stream()
                .map(operation -> operationService.withAllowedActions(operation, (String) t.get("status"),
                        (String) t.get("technicianLogin"), user))
                .toList());
        body.put("allowedActions", TurnaroundService.allowedActions((String) t.get("status"),
                number(t.get("mandatoryDone")), number(t.get("mandatoryTotal")), user));
        return body;
    }

    private Map<String, Object> withOperationActions(Map<String, Object> source, CurrentUser user) {
        Map<String, Object> row = new LinkedHashMap<>(source);
        String status = (String) row.get("status");
        String technicianLogin = (String) row.remove("technicianLogin");
        Object rawOperations = row.get("operations");
        if (!(rawOperations instanceof List<?> operations)) {
            throw new IllegalStateException("Запрос оборота вернул operations не в виде списка");
        }
        row.put("operations", operations.stream().map(operation -> {
            if (!(operation instanceof Map<?, ?> values)) {
                throw new IllegalStateException("Запрос оборота вернул операцию не в виде объекта");
            }
            Map<String, Object> fields = new LinkedHashMap<>();
            values.forEach((key, value) -> {
                if (!(key instanceof String name)) {
                    throw new IllegalStateException("Запрос оборота вернул поле операции без строкового имени");
                }
                fields.put(name, value);
            });
            return operationService.withAllowedActions(fields, status, technicianLogin, user);
        }).toList());
        int done = 0;
        int total = -1;   // нет данных о прогрессе: готовность не предлагаем
        if (row.get("progress") instanceof Map<?, ?> progress) {
            done = number(progress.get("done"));
            total = number(progress.get("total"));
        }
        row.put("allowedActions", TurnaroundService.allowedActions(status, done, total, user));
        return row;
    }

    private static int number(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static LocalDate[] period(LocalDate dateFrom, LocalDate dateTo, LocalDate date) {
        if (date != null && (dateFrom != null || dateTo != null)) {
            throw ApiException.validation("date", date, "Используйте date или dateFrom/dateTo, но не оба варианта");
        }
        if (date != null) {
            return new LocalDate[]{date, date};
        }
        if (dateFrom == null && dateTo == null) {
            LocalDate today = LocalDate.now();
            return new LocalDate[]{today, today};
        }
        if (dateFrom == null || dateTo == null) {
            String missing = dateFrom == null ? "dateFrom" : "dateTo";
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Укажите обе границы периода",
                    List.of(new ApiError.Detail(missing, null, "обязательное поле")));
        }
        if (dateTo.isBefore(dateFrom)) {
            throw ApiException.validation("dateTo", dateTo, "Конец периода раньше начала");
        }
        if (ChronoUnit.DAYS.between(dateFrom, dateTo) + 1 > MAX_DAYS) {
            throw ApiException.validation("dateTo", dateTo, "Период больше 92 дней");
        }
        return new LocalDate[]{dateFrom, dateTo};
    }

    private static void validateStatuses(List<String> statuses) {
        if (statuses != null && (statuses.isEmpty() || statuses.stream().anyMatch(s -> !STATUSES.contains(s)))) {
            throw ApiException.validation("status", statuses, "Список содержит неизвестный статус оборота");
        }
    }

    private static void validatePositive(String field, Integer value) {
        if (value != null && value <= 0) {
            throw ApiException.validation(field, value, "Идентификатор должен быть положительным");
        }
    }
}
