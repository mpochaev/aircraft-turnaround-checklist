package ru.miit.uvp411.checklist.ops.internal;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import ru.miit.uvp411.checklist.shared.jdbc.Rows;

/**
 * Чтение оборотов. Справочники ref.* читаются в SELECT напрямую, это разрешено правилом 2
 * из docs/architecture.md (только чтение справочных данных, писать можно только в ops).
 */
@Repository
public class TurnaroundQueries {

    private static final String BASE = """
            select t.id, t.status,
                   fa.flight_number as "arrNumber", al.iata_code as "airline", da.iata_code as "arrFrom", fa.sta,
                   fd.flight_number as "depNumber", ad.iata_code as "depTo", fd.std,
                   ac.tail_number as "aircraft", ty.code as "aircraftType", s.code as "stand",
                   u.login as "technicianLogin", u.last_name || ' ' || left(u.first_name, 1) || '.' as "technician",
                   t.on_block_at as "onBlockAt", t.ready_at as "readyAt", t.off_block_at as "offBlockAt",
                   p.mandatory_done as "mandatoryDone", p.mandatory_total as "mandatoryTotal",
                   p.problems, p.delay_risk as "delayRisk"
            from ops.turnaround t
            join ops.flight fa on fa.id = t.arr_flight_id
            join ops.flight fd on fd.id = t.dep_flight_id
            join ref.airline al on al.id = fa.airline_id
            join ref.airport da on da.id = fa.dep_airport_id
            join ref.airport ad on ad.id = fd.arr_airport_id
            join ref.aircraft ac on ac.id = t.aircraft_id
            join ref.aircraft_type ty on ty.id = ac.aircraft_type_id
            join ref.stand s on s.id = t.stand_id
            join auth.app_user u on u.id = t.technician_id
            join ops.v_turnaround_progress p on p.turnaround_id = t.id
            """;

    private final JdbcClient jdbc;

    TurnaroundQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Обороты, у которых рейс прилёта приходится на дату. technicianLogin = null: все обороты. */
    public List<Map<String, Object>> byDate(LocalDate date, String technicianLogin) {
        return Rows.clean(jdbc.sql(BASE + """
                        where fa.sta >= :day and fa.sta < :next
                          and (cast(:tech as varchar) is null or u.login = :tech)
                        order by fa.sta, t.id
                        """)
                .param("day", date.atStartOfDay())
                .param("next", date.plusDays(1).atStartOfDay())
                .param("tech", technicianLogin)
                .query().listOfRows());
    }

    public Optional<Map<String, Object>> byId(long id) {
        return jdbc.sql(BASE + " where t.id = :id").param("id", id).query().listOfRows()
                .stream().findFirst().map(Rows::clean);
    }

    public List<Map<String, Object>> operations(long turnaroundId) {
        return Rows.clean(jdbc.sql("""
                        select e.id, e.seq_no as "seqNo", o.code as "operationCode", o.name, e.status,
                               e.duration_min as "durationMin", e.is_mandatory as "isMandatory",
                               e.planned_start as "plannedStart", e.planned_end as "plannedEnd",
                               e.actual_start as "actualStart", e.actual_end as "actualEnd",
                               dc.code as "delayCode", e.comment,
                               (select string_agg(po.code, ',' order by pe.seq_no)
                                  from ops.exec_dependency d
                                  join ops.operation_exec pe on pe.id = d.prev_exec_id
                                  join ref.operation po on po.id = pe.operation_id
                                 where d.exec_id = e.id) as "after"
                        from ops.operation_exec e
                        join ref.operation o on o.id = e.operation_id
                        left join ref.delay_code dc on dc.id = e.delay_code_id
                        where e.turnaround_id = :id
                        order by e.seq_no
                        """)
                .param("id", turnaroundId)
                .query().listOfRows());
    }
}
