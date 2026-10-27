package ru.miit.uvp411.checklist.ops.web;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ru.miit.uvp411.checklist.ops.internal.TurnaroundQueries;
import ru.miit.uvp411.checklist.shared.error.ApiException;
import ru.miit.uvp411.checklist.shared.security.CurrentUser;

/**
 * Обороты, раздел 7 спецификации API.
 * Готово: GET /turnarounds?date=, GET /turnarounds/{id}.
 * TODO команда «Бизнес-логика»: фильтры (7.3), POST /turnarounds (7.4), PATCH /turnarounds/{id} (7.6),
 * PATCH /operations/{id} (7.7), allowedActions и elapsedMin в чек-листе, рейсы (7.1, 7.2).
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
    Map<String, Object> list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        CurrentUser user = CurrentUser.get();
        String onlyTechnician = user.can("turnarounds.read_all") ? null : user.login();
        List<Map<String, Object>> items = queries.byDate(date != null ? date : LocalDate.now(), onlyTechnician);
        return Map.of("items", items, "total", items.size());
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
}
