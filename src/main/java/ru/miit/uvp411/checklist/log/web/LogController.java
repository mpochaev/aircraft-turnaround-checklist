package ru.miit.uvp411.checklist.log.web;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ru.miit.uvp411.checklist.auth.AuthApi;
import ru.miit.uvp411.checklist.log.ActionTypes;
import ru.miit.uvp411.checklist.log.internal.LogQueries;
import ru.miit.uvp411.checklist.shared.error.ApiException;
import ru.miit.uvp411.checklist.shared.security.CurrentUser;
import ru.miit.uvp411.checklist.shared.web.Paging;

/**
 * Журнал действий, раздел 8.1 спецификации API.
 * Без права log.read_all пользователь видит только свои записи, а чужой userId даёт 403 (ФТ-42, сценарий 48).
 */
@RestController
@RequestMapping("/api/v1/log")
class LogController {

    private static final Duration MAX_PERIOD = Duration.ofDays(31);

    private final LogQueries queries;
    private final AuthApi auth;

    LogController(LogQueries queries, AuthApi auth) {
        this.queries = queries;
        this.auth = auth;
    }

    @GetMapping
    Map<String, Object> list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
                             @RequestParam(required = false) Integer userId,
                             @RequestParam(required = false) String actionType,
                             @RequestParam(required = false) Long turnaroundId,
                             @RequestParam(defaultValue = "1") int page,
                             @RequestParam(defaultValue = "50") int size) {
        // по умолчанию текущие сутки; если задан только один конец, период занимает его сутки
        LocalDate day = from != null ? from.toLocalDate() : to != null ? to.toLocalDate() : LocalDate.now();
        LocalDateTime start = from != null ? from : day.atStartOfDay();
        LocalDateTime end = to != null ? to : day.atTime(LocalTime.MAX.withNano(0));
        if (end.isBefore(start)) {
            throw ApiException.validation("to", to, "Конец периода раньше начала");
        }
        if (Duration.between(start, end).compareTo(MAX_PERIOD) > 0) {
            throw ApiException.validation("to", to, "Период больше 31 дня");
        }
        if (actionType != null && !ActionTypes.exists(actionType)) {
            throw ApiException.validation("actionType", actionType, "Неизвестный вид действия");
        }
        Paging paging = Paging.of(page, size);

        CurrentUser me = CurrentUser.get();
        String onlyLogin = null;
        if (!me.can("log.read_all")) {
            Integer myId = auth.findUser(me.login()).map(AuthApi.UserInfo::id).orElse(null);
            if (userId != null && !userId.equals(myId)) {
                throw ApiException.forbidden();
            }
            onlyLogin = me.login();
        }

        LogQueries.Page result = queries.find(
                new LogQueries.Filter(start, end, userId, onlyLogin, actionType, turnaroundId), paging);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", result.items());
        body.put("total", result.total());
        return body;
    }
}
