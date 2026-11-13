package ru.miit.uvp411.checklist.ops.internal;

import java.time.LocalDate;
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

/**
 * Чтение оборотов и запись чек-листа. Справочники ref.* читаются в SELECT напрямую, это разрешено правилом 2
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

    // ---------------------------------------------------------------- создание оборота (7.4)

    Optional<Map<String, Object>> flight(long id) {
        return Rows.clean(jdbc.sql("""
                        select id, dep_airport_id as "depAirportId", arr_airport_id as "arrAirportId", std, sta
                        from ops.flight where id = :id
                        """)
                .param("id", id).query().listOfRows()).stream().findFirst();
    }

    /** Тип ВС борта: из него берутся активные нормативы (ФТ-27). */
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

    /** Активность учётной записи техника: чтение auth.app_user разрешено правилом 2 CONTRIBUTING. */
    Optional<Boolean> technicianActive(int technicianId) {
        return jdbc.sql("select is_active from auth.app_user where id = :id")
                .param("id", technicianId).query(Boolean.class).list().stream().findFirst();
    }

    /** Право turnarounds.service у техника (спецификация 7.4: «активный пользователь с правом»). */
    boolean technicianHasService(int technicianId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        select exists (select 1 from auth.v_user_permission
                                       where user_id = :id and code = 'turnarounds.service')
                        """)
                .param("id", technicianId).query(Boolean.class).single());
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

    /** Чек-лист одной пакетной вставкой: снимок норматива и плановое время всех операций сразу. */
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

    /** Копия зависимостей нормативов в ops.exec_dependency (ФТ-21: снимок на момент создания). */
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
                .query().listOfRows().forEach(row -> ids.put((String) row.get("code"), ((Number) row.get("id")).longValue()));
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
                .query().listOfRows().forEach(row -> ids.put((String) row.get("code"), ((Number) row.get("id")).longValue()));
        return ids;
    }
}
