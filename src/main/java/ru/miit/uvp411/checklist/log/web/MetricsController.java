package ru.miit.uvp411.checklist.log.web;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ru.miit.uvp411.checklist.log.internal.MetricsQueries;
import ru.miit.uvp411.checklist.shared.error.ApiError;
import ru.miit.uvp411.checklist.shared.error.ApiException;

/** Показатели для экрана «История», раздел 8.2 спецификации API (ФТ-44, сценарий 51). */
@RestController
@RequestMapping("/api/v1/metrics")
class MetricsController {

    private static final int MAX_DAYS = 92;

    private final MetricsQueries queries;

    MetricsController(MetricsQueries queries) {
        this.queries = queries;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('metrics.read')")
    Map<String, Object> metrics(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
                                @RequestParam(required = false) Integer aircraftTypeId) {
        if (dateFrom == null || dateTo == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Укажите период", List.of(
                    new ApiError.Detail(dateFrom == null ? "dateFrom" : "dateTo", null, "обязательное поле")));
        }
        if (dateTo.isBefore(dateFrom)) {
            throw ApiException.validation("dateTo", dateTo, "Конец периода раньше начала");
        }
        if (ChronoUnit.DAYS.between(dateFrom, dateTo) + 1 > MAX_DAYS) {
            throw ApiException.validation("dateTo", dateTo, "Период больше 92 дней");
        }
        return queries.metrics(dateFrom, dateTo, aircraftTypeId);
    }
}
