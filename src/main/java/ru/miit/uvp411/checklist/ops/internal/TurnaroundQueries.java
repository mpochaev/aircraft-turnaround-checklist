package ru.miit.uvp411.checklist.ops.internal;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import ru.miit.uvp411.checklist.ops.PlanCalculator.PlannedOperation;
import ru.miit.uvp411.checklist.shared.jdbc.Rows;
import ru.miit.uvp411.checklist.shared.web.Paging;

/**
 * Чтение оборотов. Справочники ref.* читаются в SELECT напрямую, это разрешено правилом 2
 * из docs/architecture.md (только чтение справочных данных, писать можно только в ops).
 */
@Repository
public class TurnaroundQueries {

    private static final String OPERATION_LAG = """
            case when e.status = 'not_required' then 0 else greatest(0,
                floor(extract(epoch from (coalesce(e.actual_end, localtimestamp) - e.planned_end)) / 60)
                )::integer end
            """;

    private static final String FROM = """
            from ops.turnaround t
            join ops.flight fa on fa.id = t.arr_flight_id
            join ops.flight fd on fd.id = t.dep_flight_id
            join ref.airline aa on aa.id = fa.airline_id
            join ref.airline da on da.id = fd.airline_id
            join ref.airport af on af.id = fa.dep_airport_id
            join ref.airport fa_arr on fa_arr.id = fa.arr_airport_id
            join ref.airport df on df.id = fd.dep_airport_id
            join ref.airport dt on dt.id = fd.arr_airport_id
            join ref.aircraft ac on ac.id = t.aircraft_id
            join ref.aircraft_type ty on ty.id = ac.aircraft_type_id
            join ref.stand s on s.id = t.stand_id
            join auth.app_user u on u.id = t.technician_id
            left join ops.v_turnaround_progress p on p.turnaround_id = t.id
            """;

    private static final String ROW_SELECT = """
            select t.id, t.status, t.cancel_reason as "cancelReason",
                   coalesce(p.delay_risk, false) as "atRisk",
                   coalesce(p.delay_risk, false) as "delayRisk",
                   jsonb_build_object('id', fa.id, 'code', aa.iata_code || ' ' || fa.flight_number || coalesce(fa.letter, ''),
                       'route', af.iata_code || '–' || fa_arr.iata_code, 'sta', fa.sta) as "arrFlight",
                   jsonb_build_object('id', fd.id, 'code', da.iata_code || ' ' || fd.flight_number || coalesce(fd.letter, ''),
                       'route', df.iata_code || '–' || dt.iata_code, 'std', fd.std) as "depFlight",
                   jsonb_build_object('id', ac.id, 'tailNumber', ac.tail_number, 'typeCode', ty.code) as aircraft,
                   s.code as stand,
                   jsonb_build_object('id', u.id,
                       'shortName', u.last_name || ' ' || left(u.first_name, 1) || '.') as technician,
                   u.login as "technicianLogin",
                   t.on_block_at as "onBlockAt", t.ready_at as "readyAt", t.off_block_at as "offBlockAt",
                   jsonb_build_object('done', coalesce(p.mandatory_done, 0),
                       'total', coalesce(p.mandatory_total, 0)) as progress,
                   coalesce(p.problems, 0) as problems,
                   coalesce((
                       select jsonb_agg(jsonb_build_object(
                           'id', e.id, 'seqNo', e.seq_no, 'operationCode', o.code, 'name', o.name,
                           'status', e.status, 'isMandatory', e.is_mandatory,
                           'durationMin', e.duration_min,
                           'plannedStart', e.planned_start, 'plannedEnd', e.planned_end,
                           'actualStart', e.actual_start, 'actualEnd', e.actual_end,
                           'elapsedMin', case when e.actual_start is null then 0 else greatest(0,
                               floor(extract(epoch from (coalesce(e.actual_end, localtimestamp) - e.actual_start)) / 60)
                               )::integer end,
                           'lagMin', """ + OPERATION_LAG + """
                           ,
                           'delayMin', """ + OPERATION_LAG + """
                           ,
                           'hasDelayCode', e.delay_code_id is not null,
                           'delayCode', case when dc.id is null then null
                               else jsonb_build_object('id', dc.id, 'code', dc.code, 'name', dc.name) end,
                           'executor', case when eu.id is null then null else jsonb_build_object('id', eu.id,
                               'shortName', eu.last_name || ' ' || left(eu.first_name, 1) || '.') end,
                           'comment', e.comment,
                           'waitsFor', coalesce((select jsonb_agg(prev.id order by prev.seq_no, prev.id)
                               from ops.exec_dependency d
                               join ops.operation_exec prev on prev.id = d.prev_exec_id
                               where d.exec_id = e.id and prev.status not in ('done', 'not_required')),
                               '[]'::jsonb)
                       ) order by e.seq_no)
                       from ops.operation_exec e
                       join ref.operation o on o.id = e.operation_id
                       left join ref.delay_code dc on dc.id = e.delay_code_id
                       left join auth.app_user eu on eu.id = e.executor_id
                       where e.turnaround_id = t.id
                   ), '[]'::jsonb) as operations
            """;

    private static final String BASE = ROW_SELECT + FROM;

    private static final String DETAIL_BASE = """
            select t.id, t.status,
                   fa.flight_number as "arrNumber", aa.iata_code as "airline", af.iata_code as "arrFrom", fa.sta,
                   fd.flight_number as "depNumber", dt.iata_code as "depTo", fd.std,
                   ac.tail_number as "aircraft", ty.code as "aircraftType", s.code as "stand",
                   u.login as "technicianLogin", u.last_name || ' ' || left(u.first_name, 1) || '.' as "technician",
                   t.on_block_at as "onBlockAt", t.ready_at as "readyAt", t.off_block_at as "offBlockAt",
                   t.cancel_reason as "cancelReason",
                   coalesce(p.mandatory_done, 0) as "mandatoryDone",
                   coalesce(p.mandatory_total, 0) as "mandatoryTotal",
                   coalesce(p.problems, 0) as problems, coalesce(p.delay_risk, false) as "delayRisk"
            """ + FROM;

    private final JdbcClient jdbc;

    TurnaroundQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Обороты, у которых рейс прилёта приходится на дату. technicianLogin = null: все обороты. */
    public List<Map<String, Object>> byDate(LocalDate date, String technicianLogin) {
        return Rows.clean(jdbc.sql(DETAIL_BASE + """
                        where fa.sta >= :day and fa.sta < :next
                          and (cast(:tech as varchar) is null or u.login = :tech)
                        order by fa.sta, t.id
                        """)
                .param("day", date.atStartOfDay())
                .param("next", date.plusDays(1).atStartOfDay())
                .param("tech", technicianLogin)
                .query().listOfRows());
    }

    public record Filter(LocalDate dateFrom, LocalDate dateTo, List<String> statuses, Integer airlineId,
                         Integer aircraftId, Integer aircraftTypeId, String flightNumber,
                         String standFrom, String standTo,
                         boolean onlyIssues, String technicianLogin) {
    }

    public record Page(List<Map<String, Object>> items, long total, Map<String, Object> summary) {
    }

    public Page find(Filter filter, Paging paging) {
        List<String> conditions = new ArrayList<>();
        conditions.add("fa.sta >= :from and fa.sta < :until");
        if (filter.statuses() != null) {
            conditions.add("t.status in (:statuses)");
        }
        if (filter.airlineId() != null) {
            conditions.add("fd.airline_id = :airlineId");
        }
        if (filter.aircraftId() != null) {
            conditions.add("t.aircraft_id = :aircraftId");
        }
        if (filter.aircraftTypeId() != null) {
            conditions.add("ac.aircraft_type_id = :aircraftTypeId");
        }
        if (filter.flightNumber() != null) {
            conditions.add("(fa.flight_number = :flightNumber or fd.flight_number = :flightNumber)");
        }
        if (filter.standFrom() != null) {
            conditions.add("s.code >= :standFrom");
        }
        if (filter.standTo() != null) {
            conditions.add("s.code <= :standTo");
        }
        if (filter.onlyIssues()) {
            conditions.add("(p.problems > 0 or exists (select 1 from ops.operation_exec issue "
                    + "where issue.turnaround_id = t.id and issue.status <> 'not_required' and "
                    + OPERATION_LAG.replace("e.", "issue.") + " > 0))");
        }
        if (filter.technicianLogin() != null) {
            conditions.add("u.login = :technicianLogin");
        }
        String where = " where " + String.join(" and ", conditions);

        String summarySql = """
                select count(*) as total,
                       count(*) filter (where p.problems > 0) as problems,
                       count(*) filter (where exists (select 1 from ops.operation_exec delayed
                           where delayed.turnaround_id = t.id and delayed.status <> 'not_required' and
                """ + OPERATION_LAG.replace("e.", "delayed.") + """
                           > 0)) as delayed
                """ + FROM + where;
        Map<String, Object> summaryRow = Rows.clean(bind(jdbc.sql(summarySql), filter)
                .query().singleRow());
        List<Map<String, Object>> items = Rows.clean(bind(jdbc.sql(BASE + where + "\n" + """
                        order by fa.sta, t.id
                        limit :size offset :offset
                        """), filter)
                .param("size", paging.size())
                .param("offset", paging.offset())
                .query().listOfRows());
        Map<String, Object> summary = Map.of(
                "problems", summaryRow.get("problems"),
                "delayed", summaryRow.get("delayed"));
        return new Page(items, ((Number) summaryRow.get("total")).longValue(), summary);
    }

    public Optional<Map<String, Object>> row(long id) {
        return jdbc.sql(BASE + " where t.id = :id").param("id", id).query().listOfRows()
                .stream().findFirst().map(Rows::clean);
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec statement, Filter filter) {
        statement = statement.param("from", filter.dateFrom().atStartOfDay())
                .param("until", filter.dateTo().plusDays(1).atStartOfDay());
        if (filter.statuses() != null) {
            statement = statement.param("statuses", filter.statuses());
        }
        if (filter.airlineId() != null) {
            statement = statement.param("airlineId", filter.airlineId());
        }
        if (filter.aircraftId() != null) {
            statement = statement.param("aircraftId", filter.aircraftId());
        }
        if (filter.aircraftTypeId() != null) {
            statement = statement.param("aircraftTypeId", filter.aircraftTypeId());
        }
        if (filter.flightNumber() != null) {
            statement = statement.param("flightNumber", filter.flightNumber());
        }
        if (filter.standFrom() != null) {
            statement = statement.param("standFrom", filter.standFrom());
        }
        if (filter.standTo() != null) {
            statement = statement.param("standTo", filter.standTo());
        }
        if (filter.technicianLogin() != null) {
            statement = statement.param("technicianLogin", filter.technicianLogin());
        }
        return statement;
    }

    public Optional<Map<String, Object>> byId(long id) {
        return jdbc.sql(DETAIL_BASE + " where t.id = :id").param("id", id).query().listOfRows()
                .stream().findFirst().map(Rows::clean);
    }

    public record LockedTurnaround(long id, String status, int technicianId, String technicianLogin) {
    }

    public Optional<LockedTurnaround> lockById(long id) {
        return jdbc.sql("""
                        select t.id, t.status, t.technician_id, u.login as technician_login
                        from ops.turnaround t
                        join auth.app_user u on u.id = t.technician_id
                        where t.id = :id
                        for update of t
                        """)
                .param("id", id)
                .query((rs, row) -> new LockedTurnaround(rs.getLong("id"), rs.getString("status"),
                        rs.getInt("technician_id"), rs.getString("technician_login")))
                .optional();
    }

    void updateTechnician(long turnaroundId, int technicianId) {
        jdbc.sql("update ops.turnaround set technician_id = :technicianId where id = :id")
                .param("technicianId", technicianId).param("id", turnaroundId).update();
    }

    /** У оборота есть обязательные операции, и все они выполнены. */
    boolean allMandatoryOperationsDone(long turnaroundId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        select count(*) > 0 and count(*) filter (where status <> 'done') = 0
                        from ops.operation_exec
                        where turnaround_id = :id and is_mandatory
                        """)
                .param("id", turnaroundId).query(Boolean.class).single());
    }

    /** Подтверждение готовности: in_service -> ready, время готовности ставит сервер. */
    void markReady(long turnaroundId) {
        jdbc.sql("update ops.turnaround set status = 'ready', ready_at = localtimestamp where id = :id and status = 'in_service'")
                .param("id", turnaroundId).update();
    }

    void cancel(long turnaroundId, String reason) {
        jdbc.sql("update ops.turnaround set status = 'cancelled', cancel_reason = :reason where id = :id")
                .param("reason", reason).param("id", turnaroundId).update();
    }

    boolean mandatoryOperationsDone(long turnaroundId) {
        return jdbc.sql("""
                        select not exists (select 1 from ops.operation_exec
                            where turnaround_id = :id and is_mandatory and status <> 'done')
                        """)
                .param("id", turnaroundId).query(Boolean.class).single();
    }

    boolean aircraftBusy(long aircraftId, long exceptTurnaroundId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        select exists (select 1 from ops.turnaround
                                       where aircraft_id = :aircraft and id <> :id
                                         and status in ('in_service', 'ready'))
                        """)
                .param("aircraft", aircraftId).param("id", exceptTurnaroundId)
                .query(Boolean.class).single());
    }

    boolean standBusy(long standId, long exceptTurnaroundId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        select exists (select 1 from ops.turnaround
                                       where stand_id = :stand and id <> :id
                                         and status in ('in_service', 'ready'))
                        """)
                .param("stand", standId).param("id", exceptTurnaroundId)
                .query(Boolean.class).single());
    }

    public List<Map<String, Object>> operations(long turnaroundId) {
        return Rows.clean(jdbc.sql("""
                        select e.id, e.seq_no as "seqNo", o.code as "operationCode", o.name, e.status,
                               e.duration_min as "durationMin", e.is_mandatory as "isMandatory",
                               e.planned_start as "plannedStart", e.planned_end as "plannedEnd",
                               e.actual_start as "actualStart", e.actual_end as "actualEnd",
                               case when u.id is not null then jsonb_build_object('id', u.id, 'shortName',
                                   u.last_name || ' ' || left(u.first_name, 1) || '.') end as executor,
                               dc.code as "delayCode", e.comment,
                        """ + OperationQueries.WAITS_FOR + """
                               ,
                               case when e.actual_start is null then 0 else greatest(0,
                                   floor(extract(epoch from (coalesce(e.actual_end, localtimestamp) - e.actual_start)) / 60)
                                   )::integer end as "elapsedMin",
                               case when e.status = 'not_required' then 0 else greatest(0,
                                   floor(extract(epoch from (coalesce(e.actual_end, localtimestamp) - e.planned_end)) / 60)
                                   )::integer end as "delayMin",
                               (select string_agg(po.code, ',' order by pe.seq_no)
                                  from ops.exec_dependency d
                                  join ops.operation_exec pe on pe.id = d.prev_exec_id
                                  join ref.operation po on po.id = pe.operation_id
                                 where d.exec_id = e.id) as "after"
                        from ops.operation_exec e
                        join ref.operation o on o.id = e.operation_id
                        left join auth.app_user u on u.id = e.executor_id
                        left join ref.delay_code dc on dc.id = e.delay_code_id
                        where e.turnaround_id = :id
                        order by e.seq_no
                        """)
                .param("id", turnaroundId)
                .query().listOfRows());
    }

    Optional<Map<String, Object>> flight(long id) {
        return Rows.clean(jdbc.sql("""
                        select id, dep_airport_id as "depAirportId", arr_airport_id as "arrAirportId", std, sta
                        from ops.flight where id = :id
                        """)
                .param("id", id).query().listOfRows()).stream().findFirst();
    }

    Map<String, Object> aircraftType(int aircraftId) {
        return Rows.clean(jdbc.sql("""
                        select ty.id, ty.code from ref.aircraft ac
                        join ref.aircraft_type ty on ty.id = ac.aircraft_type_id
                        where ac.id = :id
                        """)
                .param("id", aircraftId).query().singleRow());
    }

    long standAirport(int standId) {
        return jdbc.sql("select airport_id from ref.stand where id = :id")
                .param("id", standId).query(Long.class).single();
    }

    Optional<Boolean> technicianActive(int technicianId) {
        return jdbc.sql("select is_active from auth.app_user where id = :id")
                .param("id", technicianId).query(Boolean.class).list().stream().findFirst();
    }

    boolean aircraftBusy(int aircraftId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        select exists (select 1 from ops.turnaround
                                       where aircraft_id = :id and status in ('in_service', 'ready'))
                        """)
                .param("id", aircraftId).query(Boolean.class).single());
    }

    boolean standBusy(int standId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        select exists (select 1 from ops.turnaround
                                       where stand_id = :id and status in ('in_service', 'ready'))
                        """)
                .param("id", standId).query(Boolean.class).single());
    }

    long insertTurnaround(TurnaroundRequest request) {
        return jdbc.sql("""
                        insert into ops.turnaround (aircraft_id, stand_id, arr_flight_id, dep_flight_id, technician_id)
                        values (:aircraftId, :standId, :arrFlightId, :depFlightId, :technicianId)
                        returning id
                        """)
                .param("aircraftId", request.aircraftId())
                .param("standId", request.standId())
                .param("arrFlightId", request.arrFlightId())
                .param("depFlightId", request.depFlightId())
                .param("technicianId", request.technicianId())
                .query(Long.class).single();
    }

    void insertOperations(long turnaroundId, List<PlannedOperation> plan) {
        Map<String, Long> operationIds = operationIds(plan);
        StringJoiner values = new StringJoiner(", ");
        for (int i = 0; i < plan.size(); i++) {
            values.add("(:turnaroundId, :op" + i + ", :norm" + i + ", :seq" + i + ", :dur" + i
                    + ", :mand" + i + ", :start" + i + ", :end" + i + ")");
        }
        JdbcClient.StatementSpec statement = jdbc.sql("""
                        insert into ops.operation_exec (turnaround_id, operation_id, norm_id, seq_no, duration_min,
                                                        is_mandatory, planned_start, planned_end)
                        values
                        """ + values)
                .param("turnaroundId", turnaroundId);
        for (int i = 0; i < plan.size(); i++) {
            PlannedOperation operation = plan.get(i);
            statement = statement.param("op" + i, operationIds.get(operation.operationCode()))
                    .param("norm" + i, operation.normId())
                    .param("seq" + i, operation.seqNo())
                    .param("dur" + i, operation.durationMin())
                    .param("mand" + i, operation.mandatory())
                    .param("start" + i, operation.plannedStart())
                    .param("end" + i, operation.plannedEnd());
        }
        statement.update();
    }

    void insertDependencies(long turnaroundId, List<PlannedOperation> plan) {
        Map<String, Long> execIds = execIds(turnaroundId);
        StringJoiner values = new StringJoiner(", ");
        int size = 0;
        for (PlannedOperation operation : plan) {
            for (String predecessor : operation.after()) {
                values.add("(:exec" + size + ", :prev" + size + ")");
                size++;
            }
        }
        if (size == 0) {
            return;
        }
        JdbcClient.StatementSpec statement = jdbc.sql("""
                        insert into ops.exec_dependency (exec_id, prev_exec_id)
                        values
                        """ + values);
        int index = 0;
        for (PlannedOperation operation : plan) {
            for (String predecessor : operation.after()) {
                statement = statement.param("exec" + index, execIds.get(operation.operationCode()))
                        .param("prev" + index, execIds.get(predecessor));
                index++;
            }
        }
        statement.update();
    }

    private Map<String, Long> operationIds(List<PlannedOperation> plan) {
        Map<String, Long> ids = new HashMap<>();
        jdbc.sql("select o.code, o.id from ref.operation o where o.code in (:codes)")
                .param("codes", plan.stream().map(PlannedOperation::operationCode).toList())
                .query().listOfRows().forEach(row -> ids.put((String) row.get("code"),
                        ((Number) row.get("id")).longValue()));
        return ids;
    }

    private Map<String, Long> execIds(long turnaroundId) {
        Map<String, Long> ids = new LinkedHashMap<>();
        jdbc.sql("""
                        select o.code, e.id from ops.operation_exec e
                        join ref.operation o on o.id = e.operation_id
                        where e.turnaround_id = :id
                        """)
                .param("id", turnaroundId)
                .query().listOfRows().forEach(row -> ids.put((String) row.get("code"),
                        ((Number) row.get("id")).longValue()));
        return ids;
    }
}
