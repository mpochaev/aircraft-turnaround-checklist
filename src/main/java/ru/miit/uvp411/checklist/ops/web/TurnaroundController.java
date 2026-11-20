package ru.miit.uvp411.checklist.ops.web;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ru.miit.uvp411.checklist.ops.internal.TurnaroundQueries;
import ru.miit.uvp411.checklist.shared.error.ApiError;
import ru.miit.uvp411.checklist.shared.error.ApiException;
import ru.miit.uvp411.checklist.shared.security.CurrentUser;
import ru.miit.uvp411.checklist.shared.web.Paging;

/**
 * Обороты, раздел 7 спецификации API.
 * Готово: GET /turnarounds с фильтрами и GET /turnarounds/{id}.
 * TODO команда «Бизнес-логика»: POST /turnarounds (7.4), PATCH /turnarounds/{id} (7.6),
 * PATCH /operations/{id} (7.7), allowedActions в чек-листе.
 */
@RestController
@RequestMapping("/api/v1/turnarounds")
class TurnaroundController {

    private final TurnaroundQueries queries;

    TurnaroundController(TurnaroundQueries queries) {
        this.queries = queries;
    }

    /**
     * Супервайзер (право turnarounds.read_all) видит все обороты, техник только назначенные ему.
     * Это проверка на уровне данных: право на метод есть у всех, а что вернуть, решает сервер.
     */
    @GetMapping
    Map<String, Object> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer airlineId,
            @RequestParam(required = false) Integer aircraftId,
            @RequestParam(required = false) String flightNumber,
            @RequestParam(required = false) String standFrom,
            @RequestParam(required = false) String standTo,
            @RequestParam(defaultValue = "false") boolean onlyIssues,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        LocalDate from;
        LocalDate to;
        if (dateFrom != null || dateTo != null) {
            if (dateFrom == null || dateTo == null) {
                String field = dateFrom == null ? "dateFrom" : "dateTo";
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Укажите обе границы периода",
                        List.of(new ApiError.Detail(field, null, "обязательное поле")));
            }
            from = dateFrom;
            to = dateTo;
        } else {
            from = date != null ? date : LocalDate.now();
            to = from;
        }
        if (to.isBefore(from)) {
            throw ApiException.validation("dateTo", to, "Конец периода раньше начала");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > 92) {
            throw ApiException.validation("dateTo", to, "Период больше 92 дней");
        }
        List<String> statuses = parseStatuses(status);
        if (airlineId != null && airlineId < 1) {
            throw ApiException.validation("airlineId", airlineId, "Идентификатор авиакомпании должен быть положительным");
        }
        if (aircraftId != null && aircraftId < 1) {
            throw ApiException.validation("aircraftId", aircraftId, "Идентификатор борта должен быть положительным");
        }
        if (flightNumber != null && !flightNumber.matches("[0-9]{1,4}")) {
            throw ApiException.validation("flightNumber", flightNumber, "Номер рейса должен содержать от 1 до 4 цифр");
        }
        Paging paging = Paging.of(page, size);
        CurrentUser user = CurrentUser.get();
        String onlyTechnician = user.can("turnarounds.read_all") ? null : user.login();
        return queries.search(from, to, statuses, airlineId, aircraftId, flightNumber,
                standFrom, standTo, onlyIssues, onlyTechnician, paging);
    }

    @GetMapping("/{id}")
    Map<String, Object> one(@PathVariable long id) {
        CurrentUser user = CurrentUser.get();
        Map<String, Object> t = queries.byId(id).orElseThrow(() -> ApiException.notFound("Оборот"));
        if (!user.can("turnarounds.read_all") && !user.login().equals(t.get("technicianLogin"))) {
            throw ApiException.notFound("Оборот");   // спецификация 7.5: чужой оборот выглядит как несуществующий
        }
        Map<String, Object> body = new LinkedHashMap<>(t);
        body.put("operations", queries.operations(id));
        return body;
    }

    private static List<String> parseStatuses(String status) {
        if (status == null || status.isBlank()) {
            return List.of();
        }
        List<String> statuses = java.util.Arrays.stream(status.split(",", -1))
                .map(String::trim)
                .toList();
        List<String> allowed = List.of("planned", "in_service", "ready", "closed", "cancelled");
        if (statuses.stream().anyMatch(value -> value.isEmpty() || !allowed.contains(value))) {
            throw ApiException.validation("status", status, "Недопустимый статус оборота");
        }
        return statuses.stream().distinct().toList();
    }
}
