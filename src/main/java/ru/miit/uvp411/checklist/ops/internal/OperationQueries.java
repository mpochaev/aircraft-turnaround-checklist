package ru.miit.uvp411.checklist.ops.internal;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import ru.miit.uvp411.checklist.shared.jdbc.Rows;

@Repository
public class OperationQueries {

    private final JdbcClient jdbc;

    public OperationQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Вызывается внутри транзакции OperationService. Другие операции того же оборота не блокируются. */
    public Optional<OperationContext> lockById(long id) {
        return jdbc.sql("""
                        select e.*, t.status as turnaround_status, t.technician_id, u.login as technician_login
                        from ops.operation_exec e
                        join ops.turnaround t on t.id = e.turnaround_id
                        join auth.app_user u on u.id = t.technician_id
                        where e.id = :id
                        for update of e
                        """)
                .param("id", id)
                .query((rs, row) -> new OperationContext(
                        new OperationContext.Operation(rs.getLong("id"), rs.getLong("turnaround_id"),
                                rs.getInt("operation_id"), rs.getInt("norm_id"), rs.getInt("seq_no"),
                                rs.getInt("duration_min"), rs.getBoolean("is_mandatory"), rs.getString("status"),
                                rs.getObject("planned_start", LocalDateTime.class),
                                rs.getObject("planned_end", LocalDateTime.class),
                                rs.getObject("actual_start", LocalDateTime.class),
                                rs.getObject("actual_end", LocalDateTime.class),
                                rs.getObject("executor_id", Integer.class), rs.getObject("delay_code_id", Integer.class),
                                rs.getString("comment")),
                        new OperationContext.Turnaround(rs.getLong("turnaround_id"), rs.getString("turnaround_status"),
                                rs.getInt("technician_id"), rs.getString("technician_login")), List.of()))
                .optional()
                .map(context -> new OperationContext(context.operation(), context.turnaround(), predecessors(id)));
    }

    private List<OperationContext.Predecessor> predecessors(long id) {
        return jdbc.sql("""
                        select p.id, o.code, o.name, p.status
                        from ops.exec_dependency d
                        join ops.operation_exec p on p.id = d.prev_exec_id
                        join ref.operation o on o.id = p.operation_id
                        where d.exec_id = :id
                        order by p.seq_no, p.id
                        """)
                .param("id", id)
                .query((rs, row) -> new OperationContext.Predecessor(rs.getLong("id"), rs.getString("code"),
                        rs.getString("name"), rs.getString("status")))
                .list();
    }

    /** Повторное чтение после обработчика: клиент получает сохранённые значения, а не старый снимок. */
    public Map<String, Object> operation(long id) {
        return Rows.clean(jdbc.sql("""
                        select e.id, e.seq_no as "seqNo", o.code as "operationCode", o.name, e.status,
                               e.duration_min as "durationMin", e.is_mandatory as "isMandatory",
                               e.planned_start as "plannedStart", e.planned_end as "plannedEnd",
                               e.actual_start as "actualStart", e.actual_end as "actualEnd",
                               case when u.id is not null then jsonb_build_object('id', u.id, 'shortName',
                                   u.last_name || ' ' || left(u.first_name, 1) || '.') end as executor,
                               case when dc.id is not null then jsonb_build_object('id', dc.id,
                                   'code', dc.code, 'name', dc.name) end as "delayCode", e.comment,
                               coalesce((select jsonb_agg(p.id order by p.seq_no, p.id)
                                   from ops.exec_dependency d join ops.operation_exec p on p.id = d.prev_exec_id
                                   where d.exec_id = e.id and p.status not in ('done', 'not_required')),
                                   '[]'::jsonb) as "waitsFor",
                               case when e.actual_start is null then 0 else greatest(0,
                                   floor(extract(epoch from (coalesce(e.actual_end, localtimestamp) - e.actual_start)) / 60)
                                   )::integer end as "elapsedMin",
                               case when e.status = 'not_required' then 0 else greatest(0,
                                   floor(extract(epoch from (coalesce(e.actual_end, localtimestamp) - e.planned_end)) / 60)
                                   )::integer end as "delayMin"
                        from ops.operation_exec e
                        join ref.operation o on o.id = e.operation_id
                        left join auth.app_user u on u.id = e.executor_id
                        left join ref.delay_code dc on dc.id = e.delay_code_id
                        where e.id = :id
                        """).param("id", id).query().singleRow());
    }

    public Map<String, Object> turnaround(long id) {
        return Rows.clean(jdbc.sql("""
                        select turnaround_id as id, status,
                               jsonb_build_object('done', mandatory_done, 'total', mandatory_total) as progress,
                               coalesce(delay_risk, false) as "delayRisk"
                        from ops.v_turnaround_progress where turnaround_id = :id
                        """).param("id", id).query().singleRow());
    }
}
