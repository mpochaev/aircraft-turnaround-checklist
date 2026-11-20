package ru.miit.uvp411.checklist.ops.internal;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import ru.miit.uvp411.checklist.shared.jdbc.Rows;
import ru.miit.uvp411.checklist.shared.web.Paging;

@Repository
public class TurnaroundQueries {

    private static final String FROM = """
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

    private static final String BASE = """
            select t.id, t.status,
                   fa.flight_number as "arrNumber", al.iata_code as "airline", da.iata_code as "arrFrom", fa.sta,
                   fd.flight_number as "depNumber", ad.iata_code as "depTo", fd.std,
                   ac.tail_number as "aircraft", ty.code as "aircraftType", s.code as "stand",
                   u.login as "technicianLogin", u.last_name || ' ' || left(u.first_name, 1) || '.' as "technician",
                   t.on_block_at as "onBlockAt", t.ready_at as "readyAt", t.off_block_at as "offBlockAt",
                   p.mandatory_done as "mandatoryDone", p.mandatory_total as "mandatoryTotal",
                   p.problems, p.delay_risk as "delayRisk"
            """ + FROM;

    private final JdbcClient jdbc;

    TurnaroundQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static final String FILTERS = """
            where fa.sta >= :from and fa.sta < :to
              and (cast(:statuses as varchar) is null or t.status = any(string_to_array(:statuses, ',')))
              and (cast(:airlineId as integer) is null or fd.airline_id = :airlineId)
              and (cast(:aircraftId as integer) is null or t.aircraft_id = :aircraftId)
              and (cast(:flightNumber as varchar) is null
                   or fa.flight_number = :flightNumber or fd.flight_number = :flightNumber)
              and (cast(:standFrom as varchar) is null or s.code >= :standFrom)
              and (cast(:standTo as varchar) is null or s.code <= :standTo)
              and (not :onlyIssues or p.problems > 0 or p.delay_risk)
              and (cast(:tech as varchar) is null or u.login = :tech)
            """;

    /** Фильтрованный и постраничный список. Общее число считается до LIMIT/OFFSET. */
    public Map<String, Object> search(LocalDate dateFrom, LocalDate dateTo, List<String> statuses,
                                      Integer airlineId, Integer aircraftId, String flightNumber,
                                      String standFrom, String standTo, boolean onlyIssues,
                                      String technicianLogin, Paging paging) {
        JdbcClient.StatementSpec countQuery = filtered(
                "select count(*) " + FROM + FILTERS, dateFrom, dateTo, statuses, airlineId, aircraftId,
                flightNumber, standFrom, standTo, onlyIssues, technicianLogin);
        long total = countQuery.query(Long.class).single();

        List<Map<String, Object>> items = Rows.clean(filtered(
                BASE + FILTERS + " order by fa.sta, t.id limit :limit offset :offset",
                dateFrom, dateTo, statuses, airlineId, aircraftId, flightNumber,
                standFrom, standTo, onlyIssues, technicianLogin)
                .param("limit", paging.size())
                .param("offset", paging.offset())
                .query().listOfRows());
        return Map.of("items", items, "total", total);
    }

    private JdbcClient.StatementSpec filtered(String sql, LocalDate dateFrom, LocalDate dateTo,
                                              List<String> statuses, Integer airlineId, Integer aircraftId,
                                              String flightNumber, String standFrom, String standTo,
                                              boolean onlyIssues, String technicianLogin) {
        return jdbc.sql(sql)
                .param("from", dateFrom.atStartOfDay())
                .param("to", dateTo.plusDays(1).atStartOfDay())
                .param("statuses", statuses.isEmpty() ? null : String.join(",", statuses))
                .param("airlineId", airlineId)
                .param("aircraftId", aircraftId)
                .param("flightNumber", flightNumber)
                .param("standFrom", standFrom)
                .param("standTo", standTo)
                .param("onlyIssues", onlyIssues)
                .param("tech", technicianLogin);
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
                               case when ex.id is null then null else jsonb_build_object(
                                   'id', ex.id, 'shortName', ex.last_name || ' ' || left(ex.first_name, 1) || '.'
                               ) end as executor,
                               (select coalesce(jsonb_agg(pe.id order by pe.seq_no), '[]'::jsonb)
                                  from ops.exec_dependency d
                                  join ops.operation_exec pe on pe.id = d.prev_exec_id
                                 where d.exec_id = e.id and pe.status not in ('done', 'not_required')) as "waitsFor",
                               case when e.actual_start is null then 0
                                    else greatest(0, floor(extract(epoch from
                                         (coalesce(e.actual_end, localtimestamp) - e.actual_start)) / 60)::int)
                               end as "elapsedMin",
                               case when e.status = 'not_required' then 0
                                    else greatest(0, floor(extract(epoch from
                                         (coalesce(e.actual_end, localtimestamp) - e.planned_end)) / 60)::int)
                               end as "delayMin",
                               dc.code as "delayCode", e.comment,
                               (select string_agg(po.code, ',' order by pe.seq_no)
                                  from ops.exec_dependency d
                                  join ops.operation_exec pe on pe.id = d.prev_exec_id
                                  join ref.operation po on po.id = pe.operation_id
                                 where d.exec_id = e.id) as "after"
                        from ops.operation_exec e
                        join ref.operation o on o.id = e.operation_id
                        left join ref.delay_code dc on dc.id = e.delay_code_id
                        left join auth.app_user ex on ex.id = e.executor_id
                        where e.turnaround_id = :id
                        order by e.seq_no
                        """)
                .param("id", turnaroundId)
                .query().listOfRows());
    }
}
