-- Команда «Данные»: защита журнала, представления для журнала и показателей, хранение 3 года.
-- Представления лежат в схеме ops, потому что читают её таблицы. Их пишет команда «Данные»,
-- проверяет команда «Бизнес-логика» как владелец схемы ops (docs/architecture.md, правило 2).

-- ---------------------------------------------------------------- защита журнала (ФТ-41)
-- UPDATE и DELETE уже запрещены триггером action_log_protect из V1. TRUNCATE строчные триггеры
-- не вызывает, поэтому запрещаем его отдельно.
CREATE FUNCTION log.forbid_truncate() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Журнал очищать нельзя';
END $$;
CREATE TRIGGER action_log_no_truncate BEFORE TRUNCATE ON log.action_log
    FOR EACH STATEMENT EXECUTE FUNCTION log.forbid_truncate();

-- ---------------------------------------------------------------- журнал: название объекта
-- Колонка «Объект» на экране журнала (спецификация API, таблица 8.3): «Оборот 5812 · Заправка топливом».
CREATE VIEW ops.v_object_title AS
SELECT 'turnaround'::varchar(30) AS entity, t.id AS entity_id, 'Оборот ' || t.id AS title
FROM ops.turnaround t
UNION ALL
SELECT 'operation_exec', e.id, 'Оборот ' || e.turnaround_id || ' · ' || o.name
FROM ops.operation_exec e
JOIN ref.operation o ON o.id = e.operation_id
UNION ALL
SELECT 'flight', f.id, 'Рейс ' || al.iata_code || ' ' || f.flight_number || f.letter
FROM ops.flight f
JOIN ref.airline al ON al.id = f.airline_id;

-- ---------------------------------------------------------------- показатели (ФТ-44, GET /metrics)
-- Закрытые обороты: длительность от постановки до отправления ВС.
CREATE VIEW ops.v_turnaround_result AS
SELECT t.id AS turnaround_id,
       ac.aircraft_type_id,
       t.aircraft_id,
       t.on_block_at,
       t.off_block_at,
       round(extract(epoch FROM t.off_block_at - t.on_block_at) / 60)::int AS duration_min
FROM ops.turnaround t
JOIN ref.aircraft ac ON ac.id = t.aircraft_id
WHERE t.status = 'closed';

-- Выполненные операции: в срок, если фактическое окончание не позже планового (таблица 8.6).
-- Отставание в целых минутах с округлением вверх: 30 секунд опоздания считаются за 1 минуту.
CREATE VIEW ops.v_operation_result AS
SELECT e.id AS exec_id,
       e.turnaround_id,
       e.operation_id,
       e.planned_end,
       e.actual_end,
       e.actual_end <= e.planned_end AS on_time,
       greatest(0, ceil(extract(epoch FROM e.actual_end - e.planned_end) / 60))::int AS delay_min,
       e.delay_code_id
FROM ops.operation_exec e
WHERE e.status = 'done';

-- ---------------------------------------------------------------- хранение 3 года (ФТ-45)
-- Удаляет обороты, их операции и рейсы, а также записи журнала за месяцы старше 3 лет.
-- Справочники и пользователи не трогает. Вызывается раз в месяц из приложения (RetentionJob),
-- вручную: SELECT * FROM log.purge_expired();
-- Оборот остаётся, пока на него ссылается более новая запись журнала: его удалит следующий запуск.
CREATE FUNCTION log.purge_expired()
    RETURNS TABLE (cutoff date, turnarounds bigint, operations bigint, flights bigint, log_records bigint)
    LANGUAGE plpgsql AS $$
DECLARE
    v_cutoff timestamp := date_trunc('month', localtimestamp - interval '3 years');
    v_ids    bigint[];
BEGIN
    cutoff := v_cutoff::date;

    -- триггер action_log_protect пропускает удаление только записей старше 3 лет
    DELETE FROM log.action_log WHERE action_time < v_cutoff;
    GET DIAGNOSTICS log_records = ROW_COUNT;

    SELECT coalesce(array_agg(t.id), '{}') INTO v_ids
    FROM ops.turnaround t
    JOIN ops.flight fd ON fd.id = t.dep_flight_id
    WHERE fd.std < v_cutoff
      AND coalesce(t.off_block_at, fd.std) < v_cutoff
      AND NOT EXISTS (SELECT 1 FROM log.action_log l WHERE l.turnaround_id = t.id);

    DELETE FROM ops.exec_dependency d
    USING ops.operation_exec e
    WHERE e.id = d.exec_id AND e.turnaround_id = ANY (v_ids);

    DELETE FROM ops.operation_exec WHERE turnaround_id = ANY (v_ids);
    GET DIAGNOSTICS operations = ROW_COUNT;

    DELETE FROM ops.turnaround WHERE id = ANY (v_ids);
    GET DIAGNOSTICS turnarounds = ROW_COUNT;

    DELETE FROM ops.flight f
    WHERE f.std < v_cutoff AND f.sta < v_cutoff
      AND NOT EXISTS (SELECT 1 FROM ops.turnaround t WHERE t.arr_flight_id = f.id OR t.dep_flight_id = f.id);
    GET DIAGNOSTICS flights = ROW_COUNT;

    RETURN NEXT;
END $$;
