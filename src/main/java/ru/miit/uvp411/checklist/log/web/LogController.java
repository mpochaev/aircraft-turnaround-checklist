package ru.miit.uvp411.checklist.log.web;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ru.miit.uvp411.checklist.shared.error.ApiException;
import ru.miit.uvp411.checklist.shared.jdbc.Rows;
import ru.miit.uvp411.checklist.shared.security.CurrentUser;

/**
 * Журнал действий, раздел 8.1 спецификации API.
 * Без права log.read_all пользователь видит только свои записи, а чужой userId даёт 403.
 * Готово: фильтр по дате, пользователю и обороту.
 * TODO команда «Данные»: фильтр по действию и периоду до 31 дня, постраничный вывод, GET /metrics (8.2).
 */
@RestController
@RequestMapping("/api/v1/log")
class LogController {

    private final JdbcClient jdbc;

    LogController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    Map<String, Object> list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                             @RequestParam(required = false) String user,
                             @RequestParam(required = false) Long turnaroundId,
                             @RequestParam(defaultValue = "200") int size) {
        CurrentUser me = CurrentUser.get();
        String login = user;
        if (!me.can("log.read_all")) {
            if (user != null && !user.equals(me.login())) {
                throw ApiException.forbidden();
            }
            login = me.login();
        }
        LocalDate day = date != null ? date : LocalDate.now();
        List<Map<String, Object>> items = Rows.clean(jdbc.sql("""
                        select l.id, l.action_time as "time", l.login as "user", l.action_type as "actionType",
                               l.entity, l.entity_id as "entityId", l.turnaround_id as "turnaroundId",
                               l.old_value as "oldValue", l.new_value as "newValue"
                        from log.action_log l
                        where l.action_time >= :day and l.action_time < :next
                          and (cast(:login as varchar) is null or l.login = :login)
                          and (cast(:tid as bigint) is null or l.turnaround_id = :tid)
                        order by l.action_time desc, l.id desc
                        limit :size
                        """)
                .param("day", day.atStartOfDay())
                .param("next", day.plusDays(1).atStartOfDay())
                .param("login", login)
                .param("tid", turnaroundId)
                .param("size", Math.min(Math.max(size, 1), 1000))
                .query().listOfRows());
        return Map.of("items", items, "total", items.size());
    }
}
