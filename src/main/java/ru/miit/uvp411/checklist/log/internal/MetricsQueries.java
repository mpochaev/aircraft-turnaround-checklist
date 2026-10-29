package ru.miit.uvp411.checklist.log.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Показатели по закрытым оборотам для GET /metrics (спецификация API, раздел 8.2, ФТ-44).
 * Обороты берутся из представлений ops.v_turnaround_result и ops.v_operation_result, период считается
 * по времени отправления ВС. Как проверить вручную (сценарий 51), написано в docs/metrics.md.
 */
@Repository
public class MetricsQueries {

    private static final String SCOPE = """
            with t as (
                select r.* from ops.v_turnaround_result r
                where r.off_block_at >= :from and r.off_block_at < :to
                  and (cast(:typeId as int) is null or r.aircraft_type_id = :typeId)
            ),
            o as (
                select r.* from ops.v_operation_result r join t on t.turnaround_id = r.turnaround_id
            )
            """;

    private final JdbcClient jdbc;

    MetricsQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** dateFrom и dateTo включительно. */
    public Map<String, Object> metrics(LocalDate dateFrom, LocalDate dateTo, Integer aircraftTypeId) {
        Map<String, Object> totals = scope(SCOPE + """
                select (select count(*) from t) as closed,
                       (select round(avg(duration_min)) from t) as avg_min,
                       (select round(avg(case when on_time then 1.0 else 0 end), 2) from o) as on_time_share
                """, dateFrom, dateTo, aircraftTypeId).query().singleRow();

        List<Map<String, Object>> late = scope(SCOPE + """
                select op.code, op.name, count(*) as late_count, round(avg(o.delay_min)) as avg_delay
                from o join ref.operation op on op.id = o.operation_id
                where not o.on_time
                group by op.code, op.name
                order by late_count desc, avg_delay desc, op.code
                limit 5
                """, dateFrom, dateTo, aircraftTypeId).query().listOfRows();

        List<Map<String, Object>> codes = scope(SCOPE + """
                select dc.code, dc.name, count(*) as cnt
                from o join ref.delay_code dc on dc.id = o.delay_code_id
                group by dc.code, dc.name
                order by cnt desc, dc.code
                limit 5
                """, dateFrom, dateTo, aircraftTypeId).query().listOfRows();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("turnaroundsClosed", ((Number) totals.get("closed")).intValue());
        body.put("avgTurnaroundMin", intOrNull(totals.get("avg_min")));
        body.put("onTimeShare", totals.get("on_time_share"));
        body.put("topLateOperations", late.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("operationCode", r.get("code"));
            m.put("name", r.get("name"));
            m.put("lateCount", ((Number) r.get("late_count")).intValue());
            m.put("avgDelayMin", intOrNull(r.get("avg_delay")));
            return m;
        }).toList());
        body.put("topDelayCodes", codes.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", r.get("code"));
            m.put("name", r.get("name"));
            m.put("count", ((Number) r.get("cnt")).intValue());
            return m;
        }).toList());
        return body;
    }

    private JdbcClient.StatementSpec scope(String sql, LocalDate from, LocalDate to, Integer typeId) {
        return jdbc.sql(sql)
                .param("from", from.atStartOfDay())
                .param("to", to.plusDays(1).atStartOfDay())
                .param("typeId", typeId);
    }

    private static Integer intOrNull(Object v) {
        return v == null ? null : ((BigDecimal) v).intValue();
    }
}
