-- Сценарий 56: хранение данных 3 года (ФТ-45). Выполняйте шаги по порядку.

-- Шаг 1. Оборот, операция и записи журнала старше 3 лет: 10-е число месяца 4 года назад.
DO $$
DECLARE
    d   timestamp := date_trunc('month', localtimestamp - interval '4 years') + interval '9 days 10 hours';
    arr bigint;
    dep bigint;
    t   bigint;
    e   bigint;
BEGIN
    INSERT INTO ops.flight (airline_id, flight_number, flight_date, dep_airport_id, arr_airport_id, std, sta)
    SELECT al.id, '9561', d::date, a1.id, a2.id, d - interval '2 hours', d
    FROM ref.airline al, ref.airport a1, ref.airport a2
    WHERE al.iata_code = 'SU' AND a1.iata_code = 'KGD' AND a2.iata_code = 'SVO'
    RETURNING id INTO arr;
    INSERT INTO ops.flight (airline_id, flight_number, flight_date, dep_airport_id, arr_airport_id, std, sta)
    SELECT al.id, '9562', d::date, a1.id, a2.id, d + interval '70 minutes', d + interval '155 minutes'
    FROM ref.airline al, ref.airport a1, ref.airport a2
    WHERE al.iata_code = 'SU' AND a1.iata_code = 'SVO' AND a2.iata_code = 'LED'
    RETURNING id INTO dep;
    INSERT INTO ops.turnaround (aircraft_id, stand_id, arr_flight_id, dep_flight_id, technician_id, status,
                                on_block_at, ready_at, off_block_at)
    SELECT ac.id, s.id, arr, dep, u.id, 'closed', d + interval '2 minutes', d + interval '60 minutes', d + interval '66 minutes'
    FROM ref.aircraft ac, ref.stand s, auth.app_user u
    WHERE ac.tail_number = 'RA-73711' AND s.code = '125'
      AND u.id = (SELECT ur.user_id FROM auth.user_role ur JOIN auth.role r ON r.id = ur.role_id
                  WHERE r.code = 'technician' ORDER BY ur.user_id LIMIT 1)
    RETURNING id INTO t;
    INSERT INTO ops.operation_exec (turnaround_id, operation_id, norm_id, seq_no, duration_min, is_mandatory, status,
                                    planned_start, planned_end, actual_start, actual_end)
    SELECT t, n.operation_id, n.id, n.seq_no, n.duration_min, n.is_mandatory, 'done',
           d + interval '15 minutes', d + interval '35 minutes', d + interval '15 minutes', d + interval '34 minutes'
    FROM ref.operation_norm n
    JOIN ref.operation o ON o.id = n.operation_id
    JOIN ref.aircraft_type ty ON ty.id = n.aircraft_type_id
    WHERE o.code = 'FUEL' AND ty.code = 'A320'
    RETURNING id INTO e;
    INSERT INTO log.action_log (action_time, login, action_type, entity, entity_id, turnaround_id) VALUES
        (d + interval '2 minutes', 'pmi', 'on_block', 'turnaround', t, t),
        (d + interval '34 minutes', 'pmi', 'op_finish', 'operation_exec', e, t);
    RAISE NOTICE 'Старый оборот %, дата %', t, d;
END $$;

-- Шаг 2. Что лежит в базе по месяцам до удаления. Запишите числа за последний месяц.
SELECT date_trunc('month', action_time)::date AS month, count(*) AS log_records
FROM log.action_log GROUP BY 1 ORDER BY 1;
SELECT date_trunc('month', fd.std)::date AS month, count(*) AS turnarounds
FROM ops.turnaround t JOIN ops.flight fd ON fd.id = t.dep_flight_id GROUP BY 1 ORDER BY 1;
SELECT (SELECT count(*) FROM ref.airport) AS airports, (SELECT count(*) FROM ref.aircraft) AS aircraft,
       (SELECT count(*) FROM ref.operation_norm) AS norms;

-- Шаг 3. Плановая процедура удаления. Покажет, до какой даты и сколько удалено.
SELECT * FROM log.purge_expired();

-- Шаг 4. Повторите запросы шага 2: месяцев старше 3 лет нет, последний месяц и справочники не изменились.
