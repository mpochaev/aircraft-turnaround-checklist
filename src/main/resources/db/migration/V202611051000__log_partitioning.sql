-- Команда «Данные»: секционирование журнала по месяцам (замечание преподавателя на паре 30.10).
-- Журнал пишется на каждое действие: около 23 записей на оборот, при 150 оборотах в сутки
-- около 3 500 записей в сутки, 106 тысяч в месяц и 3,9 миллиона за 3 года хранения.
-- Расчёт в документе «Модель данных», таблица 24.
-- Таблица делится на секции по месяцам по полю action_time:
--   - запрос журнала за период читает только секции этого периода, а не всю таблицу;
--   - индексы каждой секции маленькие;
--   - данные старше 3 лет удаляются целым месяцем: секция удаляется командой DROP (ФТ-45).
-- Первичный ключ секционированной таблицы обязан включать ключ секционирования: (id, action_time).
-- На журнал не ссылается ни одна таблица, поэтому внешние ключи менять не нужно.

-- 1. Старую таблицу переименовываем, её последовательность id оставляем для новой таблицы
ALTER TABLE log.action_log RENAME TO action_log_old;
ALTER INDEX log.action_log_pkey RENAME TO action_log_old_pkey;
ALTER SEQUENCE log.action_log_id_seq OWNED BY NONE;

-- 2. Новая таблица с секциями по месяцам
CREATE TABLE log.action_log (
    id            bigint      NOT NULL DEFAULT nextval('log.action_log_id_seq'),
    action_time   timestamp   NOT NULL DEFAULT localtimestamp,
    user_id       int         REFERENCES auth.app_user (id),
    login         varchar(50),
    action_type   varchar(30) NOT NULL,
    entity        varchar(30),
    entity_id     bigint,
    turnaround_id bigint      REFERENCES ops.turnaround (id),
    old_value     jsonb,
    new_value     jsonb,
    ip            inet,
    CONSTRAINT action_log_pkey PRIMARY KEY (id, action_time)
) PARTITION BY RANGE (action_time);

-- Секция по умолчанию: сюда попадают записи вне созданных месяцев (например, загруженные задним числом)
CREATE TABLE log.action_log_default PARTITION OF log.action_log DEFAULT;

-- 3. Секция одного месяца, например log.action_log_y2026m11. Повторный вызов ничего не делает.
CREATE FUNCTION log.create_month_partition(p_month date) RETURNS text LANGUAGE plpgsql AS $$
DECLARE
    v_from date := date_trunc('month', p_month);
    v_name text := format('action_log_y%sm%s', to_char(v_from, 'YYYY'), to_char(v_from, 'MM'));
BEGIN
    IF to_regclass('log.' || v_name) IS NOT NULL THEN
        RETURN v_name;
    END IF;
    IF EXISTS (SELECT 1 FROM log.action_log_default
               WHERE action_time >= v_from AND action_time < v_from + interval '1 month') THEN
        RAISE WARNING 'Секция % не создана: записи этого месяца уже лежат в log.action_log_default', v_name;
        RETURN NULL;
    END IF;
    EXECUTE format('CREATE TABLE log.%I PARTITION OF log.action_log FOR VALUES FROM (%L) TO (%L)',
                   v_name, v_from, (v_from + interval '1 month')::date);
    EXECUTE format('CREATE TRIGGER action_log_no_truncate BEFORE TRUNCATE ON log.%I '
                   'FOR EACH STATEMENT EXECUTE FUNCTION log.forbid_truncate()', v_name);
    RETURN v_name;
END $$;

-- Секции на текущий месяц и p_months_ahead месяцев вперёд. Вызывается при запуске приложения
-- и раз в месяц (RetentionJob), поэтому секция нужного месяца всегда есть заранее.
CREATE FUNCTION log.ensure_partitions(p_months_ahead int DEFAULT 3) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    FOR i IN 0..p_months_ahead LOOP
        PERFORM log.create_month_partition((date_trunc('month', localtimestamp) + make_interval(months => i))::date);
    END LOOP;
END $$;

-- 4. Секции для уже записанных месяцев и на 3 месяца вперёд, перенос записей
DO $$
DECLARE
    m date := date_trunc('month', coalesce((SELECT min(action_time) FROM log.action_log_old), localtimestamp));
BEGIN
    WHILE m < date_trunc('month', localtimestamp) LOOP
        PERFORM log.create_month_partition(m);
        m := m + interval '1 month';
    END LOOP;
    PERFORM log.ensure_partitions(3);
END $$;

INSERT INTO log.action_log (id, action_time, user_id, login, action_type, entity, entity_id, turnaround_id, old_value, new_value, ip)
SELECT id, action_time, user_id, login, action_type, entity, entity_id, turnaround_id, old_value, new_value, ip
FROM log.action_log_old;

DROP TABLE log.action_log_old;
ALTER SEQUENCE log.action_log_id_seq OWNED BY log.action_log.id;

-- 5. Индексы создаются на всей таблице и сами появляются в каждой секции
CREATE INDEX ON log.action_log (action_time);
CREATE INDEX ON log.action_log (user_id, action_time);
CREATE INDEX ON log.action_log (turnaround_id);

-- 6. Защита журнала (ФТ-41): изменение и удаление запрещены триггером в каждой секции,
-- очистка TRUNCATE запрещена и на всей таблице, и на каждой секции
CREATE TRIGGER action_log_protect BEFORE UPDATE OR DELETE ON log.action_log
    FOR EACH ROW EXECUTE FUNCTION log.protect_action_log();
CREATE TRIGGER action_log_no_truncate BEFORE TRUNCATE ON log.action_log
    FOR EACH STATEMENT EXECUTE FUNCTION log.forbid_truncate();
CREATE TRIGGER action_log_no_truncate BEFORE TRUNCATE ON log.action_log_default
    FOR EACH STATEMENT EXECUTE FUNCTION log.forbid_truncate();

-- 7. Удаление данных старше 3 лет (ФТ-45): месяцы журнала удаляются целыми секциями
CREATE OR REPLACE FUNCTION log.purge_expired()
    RETURNS TABLE (cutoff date, turnarounds bigint, operations bigint, flights bigint, log_records bigint)
    LANGUAGE plpgsql AS $$
DECLARE
    v_cutoff timestamp := date_trunc('month', localtimestamp - interval '3 years');
    v_ids    bigint[];
    v_part   record;
    v_rows   bigint;
BEGIN
    cutoff := v_cutoff::date;
    log_records := 0;

    -- секции месяцев, которые целиком старше 3 лет
    FOR v_part IN
        SELECT c.relname
        FROM pg_inherits i
        JOIN pg_class c ON c.oid = i.inhrelid
        WHERE i.inhparent = 'log.action_log'::regclass
          AND c.relname ~ '^action_log_y\d{4}m\d{2}$'
          AND make_date(substring(c.relname FROM 'y(\d{4})m')::int, substring(c.relname FROM 'm(\d{2})$')::int, 1)
              + interval '1 month' <= v_cutoff
    LOOP
        EXECUTE format('SELECT count(*) FROM log.%I', v_part.relname) INTO v_rows;
        EXECUTE format('DROP TABLE log.%I', v_part.relname);
        log_records := log_records + v_rows;
    END LOOP;

    -- старые записи из секции по умолчанию (триггер пропускает только записи старше 3 лет)
    DELETE FROM log.action_log_default WHERE action_time < v_cutoff;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    log_records := log_records + v_rows;

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
