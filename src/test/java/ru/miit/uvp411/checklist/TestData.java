package ru.miit.uvp411.checklist;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Тестовые данные поверх справочников из V1: пользователи из таблицы 1 ПМИ, рейсы KGD–SVO–LED,
 * обороты и операции. Номера рейсов в каждом вызове новые, поэтому тесты не мешают друг другу.
 */
public class TestData {

    private static final AtomicInteger FLIGHT_NO = new AtomicInteger(5000);
    private static final AtomicInteger COUNTER = new AtomicInteger(1);

    private final JdbcClient jdbc;

    public TestData(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** sup01 (супервайзер), tech07 и tech12 (техники). Повторный вызов ничего не меняет. */
    public void users() {
        jdbc.sql("""
                insert into auth.app_user (login, password_hash, last_name, first_name, middle_name, must_change_password)
                values ('sup01', '-', 'Петрова', 'Анна', 'Сергеевна', false),
                       ('tech07', '-', 'Иванов', 'Пётр', 'Сергеевич', false),
                       ('tech12', '-', 'Смирнов', 'Олег', null, false)
                on conflict (login) do nothing
                """).update();
        jdbc.sql("""
                insert into auth.user_role (user_id, role_id)
                select u.id, r.id from auth.app_user u
                join auth.role r on r.code = case when u.login like 'sup%' then 'supervisor' else 'technician' end
                where u.login in ('sup01', 'tech07', 'tech12')
                on conflict do nothing
                """).update();
    }

    public int userId(String login) {
        return jdbc.sql("select id from auth.app_user where login = :login").param("login", login)
                .query(Integer.class).single();
    }

    public int aircraftTypeId(String code) {
        return jdbc.sql("select id from ref.aircraft_type where code = :code").param("code", code)
                .query(Integer.class).single();
    }

    /**
     * Оборот с техником tech07: рейс прилёта KGD–SVO с STA = sta, рейс вылета SVO–LED с STD = std.
     * Каждый оборот получает свой тестовый борт того же типа, что tail, и свою стоянку: так обороты
     * разных тестов не нарушают правило «борт и стоянка заняты только одним оборотом в работе».
     */
    public long turnaround(String tail, String status, LocalDateTime sta, LocalDateTime std,
                           LocalDateTime onBlock, LocalDateTime ready, LocalDateTime offBlock) {
        long arr = flight("KGD", "SVO", sta.minusMinutes(120), sta);
        long dep = flight("SVO", "LED", std, std.plusMinutes(85));
        return turnaround(aircraft(tail), stand(), status, arr, dep, onBlock, ready, offBlock);
    }

    /** Оборот на заданном борте, стоянке и рейсах: для проверок уникальности. */
    public long turnaround(int aircraftId, int standId, String status, long arrFlightId, long depFlightId,
                           LocalDateTime onBlock, LocalDateTime ready, LocalDateTime offBlock) {
        return jdbc.sql("""
                        insert into ops.turnaround (aircraft_id, stand_id, arr_flight_id, dep_flight_id, technician_id,
                                                    status, on_block_at, ready_at, off_block_at, cancel_reason)
                        values (:aircraft, :stand, :arr, :dep, (select id from auth.app_user where login = 'tech07'),
                                :status, :onBlock, :ready, :offBlock,
                                case when :status = 'cancelled' then 'Отмена рейса' end)
                        returning id
                        """)
                .param("aircraft", aircraftId).param("stand", standId).param("arr", arrFlightId).param("dep", depFlightId)
                .param("status", status).param("onBlock", onBlock).param("ready", ready).param("offBlock", offBlock)
                .query(Long.class).single();
    }

    /** Новый тестовый борт того же типа ВС, что борт tail из V1. */
    public int aircraft(String tail) {
        return jdbc.sql("""
                        insert into ref.aircraft (tail_number, aircraft_type_id, airline_id)
                        select :newTail, aircraft_type_id, airline_id from ref.aircraft where tail_number = :tail
                        returning id
                        """)
                .param("newTail", "RA-T" + COUNTER.getAndIncrement()).param("tail", tail)
                .query(Integer.class).single();
    }

    /** Новая тестовая стоянка в SVO. */
    public int stand() {
        return jdbc.sql("""
                        insert into ref.stand (airport_id, code)
                        select id, :code from ref.airport where iata_code = 'SVO'
                        returning id
                        """)
                .param("code", "T" + COUNTER.getAndIncrement())
                .query(Integer.class).single();
    }

    /** Закрытый оборот: постановка onBlock, отправление offBlock. */
    public long closedTurnaround(String tail, LocalDateTime onBlock, LocalDateTime offBlock) {
        return turnaround(tail, "closed", onBlock.minusMinutes(5), offBlock.plusMinutes(5), onBlock, offBlock.minusMinutes(3), offBlock);
    }

    /**
     * Операция оборота по нормативу его типа ВС. Для статуса done фактическое начало берётся равным
     * плановому, окончание задаётся. delayCode, например "36", или null.
     */
    public long operation(long turnaroundId, String operationCode, String status, LocalDateTime plannedStart,
                          LocalDateTime plannedEnd, LocalDateTime actualEnd, String delayCode) {
        return jdbc.sql("""
                        insert into ops.operation_exec (turnaround_id, operation_id, norm_id, seq_no, duration_min, is_mandatory,
                                                        status, planned_start, planned_end, actual_start, actual_end,
                                                        delay_code_id, comment)
                        select t.id, n.operation_id, n.id, n.seq_no, n.duration_min, n.is_mandatory,
                               :status, :ps, :pe, case when :status in ('done', 'in_progress', 'problem') then :ps end, :ae,
                               (select id from ref.delay_code where code = :dc),
                               case when :status = 'problem' then 'Тестовая проблема' end
                        from ops.turnaround t
                        join ref.aircraft ac on ac.id = t.aircraft_id
                        join ref.operation o on o.code = :op
                        join ref.operation_norm n on n.aircraft_type_id = ac.aircraft_type_id and n.operation_id = o.id
                        where t.id = :tid
                        returning id
                        """)
                .param("tid", turnaroundId).param("op", operationCode).param("status", status)
                .param("ps", plannedStart).param("pe", plannedEnd).param("ae", actualEnd).param("dc", delayCode)
                .query(Long.class).single();
    }

    /** Запись журнала с заданным временем, в обход AuditLog: нужна для данных «из прошлого». */
    public void logRecord(LocalDateTime time, String login, String actionType, String entity, Long entityId, Long turnaroundId) {
        jdbc.sql("""
                        insert into log.action_log (action_time, user_id, login, action_type, entity, entity_id, turnaround_id)
                        values (:time, (select id from auth.app_user where login = :login), :login, :type, :entity, :eid, :tid)
                        """)
                .param("time", time).param("login", login).param("type", actionType)
                .param("entity", entity).param("eid", entityId).param("tid", turnaroundId)
                .update();
    }

    /** Рейс авиакомпании SU с новым номером. */
    public long flight(String from, String to, LocalDateTime std, LocalDateTime sta) {
        return jdbc.sql("""
                        insert into ops.flight (airline_id, flight_number, flight_date, dep_airport_id, arr_airport_id, std, sta)
                        values ((select id from ref.airline where iata_code = 'SU'), :num, :date,
                                (select id from ref.airport where iata_code = :from),
                                (select id from ref.airport where iata_code = :to), :std, :sta)
                        returning id
                        """)
                .param("num", String.valueOf(FLIGHT_NO.getAndIncrement() % 10000))
                .param("date", std.toLocalDate()).param("from", from).param("to", to)
                .param("std", std).param("sta", sta)
                .query(Long.class).single();
    }
}
