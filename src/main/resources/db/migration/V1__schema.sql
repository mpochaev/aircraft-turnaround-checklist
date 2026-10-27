-- Цифровой чек-лист подготовки ВС к вылету. Группа УВП-411.
-- Физическая модель данных для PostgreSQL 16, версия 1.2 (23.10.2026: в v_turnaround_progress
-- плановое окончание и риск задержки считаются без операций «Не требуется»). Одна база, четыре схемы:
--   auth  пользователи, роли, права, сессии, заявки   (команда авторизации)
--   ref   справочники и нормативы                      (команда справочников)
--   ops   рейсы, обороты, операции                     (команда бизнес-логики)
--   log   журнал действий                              (команда данных)
-- Все моменты времени хранятся в местном времени базового аэропорта (тип timestamp).
-- Часовой пояс базового аэропорта задаётся настройкой сервера, а не полем в справочнике.

CREATE SCHEMA IF NOT EXISTS auth;
CREATE SCHEMA IF NOT EXISTS ref;
CREATE SCHEMA IF NOT EXISTS ops;
CREATE SCHEMA IF NOT EXISTS log;

-- ---------------------------------------------------------------- auth
CREATE TABLE auth.role (
    id         smallserial PRIMARY KEY,
    code       varchar(30) NOT NULL UNIQUE CHECK (code ~ '^[a-z_]{3,30}$'),
    name       varchar(50) NOT NULL,
    is_system  boolean     NOT NULL DEFAULT false   -- базовые роли нельзя удалить или переименовать
);

CREATE TABLE auth.permission (
    id           smallserial PRIMARY KEY,
    code         varchar(40)  NOT NULL UNIQUE,      -- например turnarounds.create
    http_method  varchar(6)   NOT NULL CHECK (http_method IN ('GET', 'POST', 'PATCH')),
    path         varchar(100) NOT NULL,             -- шаблон адреса ручки
    description  varchar(200) NOT NULL
);

CREATE TABLE auth.role_permission (
    role_id        smallint NOT NULL REFERENCES auth.role (id),
    permission_id  smallint NOT NULL REFERENCES auth.permission (id),
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE auth.app_user (
    id                    serial PRIMARY KEY,
    login                 varchar(50)  NOT NULL UNIQUE CHECK (login ~ '^[a-z0-9._]{3,50}$'),
    password_hash         varchar(255) NOT NULL,
    last_name             varchar(50)  NOT NULL,
    first_name            varchar(50)  NOT NULL,
    middle_name           varchar(50),
    is_active             boolean      NOT NULL DEFAULT true,
    must_change_password  boolean      NOT NULL DEFAULT true,
    failed_attempts       smallint     NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
    locked_until          timestamp
);

CREATE TABLE auth.user_role (
    user_id  int      NOT NULL REFERENCES auth.app_user (id),
    role_id  smallint NOT NULL REFERENCES auth.role (id),
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE auth.user_session (
    id                bigserial PRIMARY KEY,
    user_id           int         NOT NULL REFERENCES auth.app_user (id),
    token_hash        char(64)    NOT NULL UNIQUE,
    started_at        timestamp   NOT NULL DEFAULT localtimestamp,
    last_activity_at  timestamp   NOT NULL DEFAULT localtimestamp,
    ended_at          timestamp,
    end_reason        varchar(20) CHECK (end_reason IN ('logout', 'timeout', 'shift_limit', 'blocked')),
    CHECK (ended_at IS NULL OR ended_at >= started_at)
);
CREATE INDEX ON auth.user_session (user_id) WHERE ended_at IS NULL;

-- Заявка на изменение персональных данных или сброс пароля. Применяется после решения супервайзера.
CREATE TABLE auth.change_request (
    id              bigserial PRIMARY KEY,
    user_id         int         NOT NULL REFERENCES auth.app_user (id),
    kind            varchar(20) NOT NULL CHECK (kind IN ('personal_data', 'password_reset')),
    new_data        jsonb,                         -- новые значения: last_name, first_name, middle_name
    reason          varchar(300),                  -- пояснение пользователя
    status          varchar(10) NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved', 'rejected')),
    created_at      timestamp   NOT NULL DEFAULT localtimestamp,
    reviewed_by     int         REFERENCES auth.app_user (id),
    reviewed_at     timestamp,
    review_comment  varchar(300),
    CHECK (kind <> 'personal_data' OR new_data IS NOT NULL),
    CHECK ((status = 'pending') = (reviewed_at IS NULL)),
    CHECK (status <> 'rejected' OR review_comment IS NOT NULL)
);
-- у пользователя не больше одной открытой заявки каждого вида
CREATE UNIQUE INDEX ON auth.change_request (user_id, kind) WHERE status = 'pending';

-- ---------------------------------------------------------------- ref
CREATE TABLE ref.airport (
    id         serial PRIMARY KEY,
    iata_code  char(3)      NOT NULL UNIQUE CHECK (iata_code ~ '^[A-Z]{3}$'),
    name       varchar(100) NOT NULL,
    city       varchar(60)  NOT NULL,
    is_active  boolean      NOT NULL DEFAULT true
);

CREATE TABLE ref.airline (
    id         serial PRIMARY KEY,
    iata_code  char(2)      NOT NULL UNIQUE CHECK (iata_code ~ '^[A-Z0-9]{2}$'),
    name       varchar(100) NOT NULL,
    is_active  boolean      NOT NULL DEFAULT true
);

CREATE TABLE ref.stand (
    id          serial PRIMARY KEY,
    airport_id  int         NOT NULL REFERENCES ref.airport (id),
    code        varchar(10) NOT NULL,
    is_active   boolean     NOT NULL DEFAULT true,
    UNIQUE (airport_id, code)
);

CREATE TABLE ref.aircraft_type (
    id         serial PRIMARY KEY,
    code       varchar(10) NOT NULL UNIQUE,
    name       varchar(60) NOT NULL,
    is_active  boolean     NOT NULL DEFAULT true
);

CREATE TABLE ref.aircraft (
    id                serial PRIMARY KEY,
    tail_number       varchar(10) NOT NULL UNIQUE,
    aircraft_type_id  int         NOT NULL REFERENCES ref.aircraft_type (id),
    airline_id        int         NOT NULL REFERENCES ref.airline (id),
    is_active         boolean     NOT NULL DEFAULT true
);

CREATE TABLE ref.operation (
    id         serial PRIMARY KEY,
    code       varchar(20)  NOT NULL UNIQUE,
    name       varchar(100) NOT NULL,
    is_active  boolean      NOT NULL DEFAULT true
);

CREATE TABLE ref.operation_norm (
    id                serial PRIMARY KEY,
    aircraft_type_id  int      NOT NULL REFERENCES ref.aircraft_type (id),
    operation_id      int      NOT NULL REFERENCES ref.operation (id),
    seq_no            smallint NOT NULL CHECK (seq_no > 0),
    duration_min      smallint NOT NULL CHECK (duration_min BETWEEN 1 AND 600),
    is_mandatory      boolean  NOT NULL DEFAULT true,
    is_active         boolean  NOT NULL DEFAULT true,
    UNIQUE (aircraft_type_id, operation_id)
);

CREATE TABLE ref.norm_dependency (
    norm_id       int NOT NULL REFERENCES ref.operation_norm (id),
    prev_norm_id  int NOT NULL REFERENCES ref.operation_norm (id),
    PRIMARY KEY (norm_id, prev_norm_id),
    CHECK (norm_id <> prev_norm_id)
);

CREATE TABLE ref.delay_code (
    id         serial PRIMARY KEY,
    code       varchar(4)   NOT NULL UNIQUE,
    name       varchar(150) NOT NULL,
    is_active  boolean      NOT NULL DEFAULT true
);

-- ---------------------------------------------------------------- ops
-- std и sta записываются в местном времени аэропортов вылета и прилёта, как в расписании.
-- Для оборота важны sta рейса прилёта и std рейса вылета: оба относятся к базовому аэропорту.
CREATE TABLE ops.flight (
    id              bigserial PRIMARY KEY,
    airline_id      int         NOT NULL REFERENCES ref.airline (id),
    flight_number   varchar(4)  NOT NULL CHECK (flight_number ~ '^[0-9]{1,4}$'),
    letter          varchar(1)  NOT NULL DEFAULT '' CHECK (letter ~ '^[A-Z]?$'),
    flight_date     date        NOT NULL,
    dep_airport_id  int         NOT NULL REFERENCES ref.airport (id),
    arr_airport_id  int         NOT NULL REFERENCES ref.airport (id),
    std             timestamp   NOT NULL,
    sta             timestamp   NOT NULL,
    atd             timestamp,
    ata             timestamp,
    CHECK (dep_airport_id <> arr_airport_id),
    UNIQUE (airline_id, flight_number, letter, flight_date, dep_airport_id, arr_airport_id)
);

CREATE TABLE ops.turnaround (
    id             bigserial PRIMARY KEY,
    aircraft_id    int         NOT NULL REFERENCES ref.aircraft (id),
    stand_id       int         NOT NULL REFERENCES ref.stand (id),
    arr_flight_id  bigint      NOT NULL UNIQUE REFERENCES ops.flight (id),
    dep_flight_id  bigint      NOT NULL UNIQUE REFERENCES ops.flight (id),
    technician_id  int         NOT NULL REFERENCES auth.app_user (id),
    status         varchar(20) NOT NULL DEFAULT 'planned'
                   CHECK (status IN ('planned', 'in_service', 'ready', 'closed', 'cancelled')),
    on_block_at    timestamp,
    ready_at       timestamp,
    off_block_at   timestamp,
    cancel_reason  varchar(300),
    CHECK (arr_flight_id <> dep_flight_id),
    CHECK (status <> 'cancelled' OR cancel_reason IS NOT NULL),
    CHECK (ready_at IS NULL OR ready_at >= on_block_at),
    CHECK (off_block_at IS NULL OR off_block_at >= ready_at)
);
CREATE INDEX ON ops.turnaround (status);
CREATE INDEX ON ops.turnaround (technician_id);

-- Выполнение операции. Порядок, длительность и обязательность копируются из норматива
-- при создании оборота, поэтому изменение норматива не влияет на уже созданные обороты.
CREATE TABLE ops.operation_exec (
    id             bigserial PRIMARY KEY,
    turnaround_id  bigint      NOT NULL REFERENCES ops.turnaround (id),
    operation_id   int         NOT NULL REFERENCES ref.operation (id),
    norm_id        int         NOT NULL REFERENCES ref.operation_norm (id),  -- откуда скопировано
    seq_no         smallint    NOT NULL CHECK (seq_no > 0),
    duration_min   smallint    NOT NULL CHECK (duration_min > 0),
    is_mandatory   boolean     NOT NULL,
    status         varchar(20) NOT NULL DEFAULT 'not_started'
                   CHECK (status IN ('not_started', 'in_progress', 'problem', 'done', 'not_required')),
    planned_start  timestamp   NOT NULL,
    planned_end    timestamp   NOT NULL,
    actual_start   timestamp,
    actual_end     timestamp,
    executor_id    int         REFERENCES auth.app_user (id),
    delay_code_id  int         REFERENCES ref.delay_code (id),
    comment        varchar(500),
    UNIQUE (turnaround_id, operation_id),
    CHECK (planned_end > planned_start),
    CHECK (actual_end IS NULL OR actual_end >= actual_start),
    CHECK (status <> 'done' OR (actual_start IS NOT NULL AND actual_end IS NOT NULL)),
    CHECK (status <> 'problem' OR comment IS NOT NULL),
    CHECK (status <> 'not_required' OR NOT is_mandatory)
);
CREATE INDEX ON ops.operation_exec (turnaround_id);
CREATE INDEX ON ops.operation_exec (status) WHERE status = 'problem';

-- Копия зависимостей нормативов на момент создания оборота
CREATE TABLE ops.exec_dependency (
    exec_id       bigint NOT NULL REFERENCES ops.operation_exec (id),
    prev_exec_id  bigint NOT NULL REFERENCES ops.operation_exec (id),
    PRIMARY KEY (exec_id, prev_exec_id),
    CHECK (exec_id <> prev_exec_id)
);

-- ---------------------------------------------------------------- log
CREATE TABLE log.action_log (
    id           bigserial PRIMARY KEY,
    action_time  timestamp   NOT NULL DEFAULT localtimestamp,
    user_id      int         REFERENCES auth.app_user (id),
    login        varchar(50),
    action_type  varchar(30) NOT NULL,
    entity       varchar(30),
    entity_id    bigint,
    turnaround_id bigint     REFERENCES ops.turnaround (id),  -- для фильтра журнала по обороту
    old_value    jsonb,
    new_value    jsonb,
    ip           inet
);
CREATE INDEX ON log.action_log (action_time);
CREATE INDEX ON log.action_log (user_id, action_time);
CREATE INDEX ON log.action_log (turnaround_id);

-- Журнал только дополняется. Изменить запись нельзя, удалить можно только старше 3 лет.
CREATE FUNCTION log.protect_action_log() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'Записи журнала изменять нельзя';
    END IF;
    IF OLD.action_time > localtimestamp - interval '3 years' THEN
        RAISE EXCEPTION 'Записи журнала моложе 3 лет удалять нельзя';
    END IF;
    RETURN OLD;
END $$;
CREATE TRIGGER action_log_protect BEFORE UPDATE OR DELETE ON log.action_log
    FOR EACH ROW EXECUTE FUNCTION log.protect_action_log();

-- Права пользователя: роли пользователя -> права ролей. Сервер проверяет право по этому представлению.
CREATE VIEW auth.v_user_permission AS
SELECT DISTINCT ur.user_id, p.code, p.http_method, p.path
FROM auth.user_role ur
JOIN auth.role_permission rp ON rp.role_id = ur.role_id
JOIN auth.permission p ON p.id = rp.permission_id;

-- Прогресс оборотов для таблицы супервайзера
CREATE VIEW ops.v_turnaround_progress AS
SELECT t.id AS turnaround_id,
       t.status,
       count(*) FILTER (WHERE e.is_mandatory)                         AS mandatory_total,
       count(*) FILTER (WHERE e.is_mandatory AND e.status = 'done')   AS mandatory_done,
       count(*) FILTER (WHERE e.status = 'problem')                   AS problems,
       max(e.planned_end) FILTER (WHERE e.status <> 'not_required')  AS planned_finish,
       f.std                                                          AS departure_std,
       max(e.planned_end) FILTER (WHERE e.status <> 'not_required') > f.std AS delay_risk
FROM ops.turnaround t
JOIN ops.operation_exec e ON e.turnaround_id = t.id
JOIN ops.flight f ON f.id = t.dep_flight_id
GROUP BY t.id, t.status, f.std;

-- ---------------------------------------------------------------- начальные данные
INSERT INTO auth.role (code, name, is_system) VALUES
    ('technician', 'Техник', true),
    ('supervisor', 'Супервайзер', true);

INSERT INTO auth.permission (code, http_method, path, description) VALUES
    ('users.read',          'GET',   '/users',               'Список пользователей'),
    ('users.write',         'POST',  '/users',               'Создание пользователя'),
    ('users.edit',          'PATCH', '/users/{id}',          'Изменение данных, ролей, сброс пароля, блокировка'),
    ('requests.review',     'PATCH', '/change-requests/{id}','Подтверждение и отклонение заявок'),
    ('requests.read',       'GET',   '/change-requests',     'Список заявок'),
    ('roles.read',          'GET',   '/roles',               'Список ролей и прав'),
    ('roles.write',         'POST',  '/roles',               'Создание роли'),
    ('roles.edit',          'PATCH', '/roles/{id}',          'Изменение роли и её прав'),
    ('ref.read',            'GET',   '/ref/{dictionary}',    'Чтение справочников'),
    ('ref.write',           'POST',  '/ref/{dictionary}',    'Добавление записи справочника'),
    ('ref.edit',            'PATCH', '/ref/{dictionary}/{id}','Изменение и деактивация записи справочника'),
    ('flights.read',        'GET',   '/flights',             'Поиск рейсов'),
    ('flights.write',       'POST',  '/flights',             'Создание рейса'),
    ('turnarounds.read_all','GET',   '/turnarounds',         'Все обороты (без этого права только назначенные)'),
    ('turnarounds.create',  'POST',  '/turnarounds',         'Создание оборота'),
    ('turnarounds.service', 'PATCH', '/turnarounds/{id}',    'Постановка и отправление ВС'),
    ('turnarounds.manage',  'PATCH', '/turnarounds/{id}',    'Готовность, отмена, замена техника'),
    ('operations.execute',  'PATCH', '/operations/{id}',     'Начать, завершить, сообщить о проблеме'),
    ('operations.manage',   'PATCH', '/operations/{id}',     'Решить проблему, не требуется, исправить отметку'),
    ('log.read_all',        'GET',   '/log',                 'Журнал всех пользователей (без этого права только свои записи)'),
    ('metrics.read',        'GET',   '/metrics',             'Показатели');

-- техник: справочники на чтение, свои обороты, отметки операций, свои записи журнала
INSERT INTO auth.role_permission (role_id, permission_id)
SELECT r.id, p.id FROM auth.role r JOIN auth.permission p
  ON p.code IN ('ref.read', 'turnarounds.service', 'operations.execute')
WHERE r.code = 'technician';
-- супервайзер: все права
INSERT INTO auth.role_permission (role_id, permission_id)
SELECT r.id, p.id FROM auth.role r CROSS JOIN auth.permission p WHERE r.code = 'supervisor';

INSERT INTO ref.airport (iata_code, name, city) VALUES
    ('SVO', 'Шереметьево', 'Москва'),
    ('KGD', 'Храброво', 'Калининград'),
    ('LED', 'Пулково', 'Санкт-Петербург');

INSERT INTO ref.airline (iata_code, name) VALUES ('SU', 'Аэрофлот'), ('FV', 'Россия'), ('DP', 'Победа');

INSERT INTO ref.stand (airport_id, code)
SELECT a.id, s::text FROM ref.airport a, generate_series(120, 150) s WHERE a.iata_code = 'SVO';

INSERT INTO ref.aircraft_type (code, name) VALUES ('A320', 'Airbus A320'), ('B777', 'Boeing 777-300ER');

INSERT INTO ref.aircraft (tail_number, aircraft_type_id, airline_id)
SELECT v.tail, t.id, l.id
FROM (VALUES ('RA-73725', 'A320', 'SU'), ('RA-73711', 'A320', 'SU'), ('RA-73110', 'A320', 'SU'),
             ('RA-89041', 'B777', 'SU')) AS v (tail, type_code, airline_code)
JOIN ref.aircraft_type t ON t.code = v.type_code
JOIN ref.airline l ON l.iata_code = v.airline_code;

INSERT INTO ref.operation (code, name) VALUES
    ('STAIRS',   'Подгон трапа'),
    ('DEBOARD',  'Высадка пассажиров'),
    ('UNLOAD',   'Выгрузка багажа'),
    ('INSPECT',  'Технический осмотр'),
    ('WATER',    'Санузлы и питьевая вода'),
    ('FUEL',     'Заправка топливом'),
    ('CLEAN',    'Уборка салона'),
    ('CATERING', 'Загрузка бортпитания'),
    ('LOAD',     'Загрузка багажа'),
    ('BOARD',    'Посадка пассажиров'),
    ('DEICE',    'Противообледенительная обработка'),
    ('CLOSE',    'Уборка трапа, закрытие дверей');

-- нормативы: порядковый номер, длительность A320, длительность B777, обязательность
INSERT INTO ref.operation_norm (aircraft_type_id, operation_id, seq_no, duration_min, is_mandatory)
SELECT t.id, o.id, v.seq, CASE t.code WHEN 'A320' THEN v.a320 ELSE v.b777 END, v.mandatory
FROM (VALUES ('STAIRS', 1, 3, 5, true), ('DEBOARD', 2, 10, 20, true), ('UNLOAD', 3, 15, 25, true),
             ('INSPECT', 4, 20, 30, true), ('WATER', 5, 10, 15, true), ('FUEL', 6, 20, 35, true),
             ('CLEAN', 7, 15, 25, true), ('CATERING', 8, 10, 20, true), ('LOAD', 9, 15, 25, true),
             ('BOARD', 10, 20, 30, true), ('DEICE', 11, 10, 15, false), ('CLOSE', 12, 3, 5, true))
     AS v (op, seq, a320, b777, mandatory)
JOIN ref.operation o ON o.code = v.op
CROSS JOIN ref.aircraft_type t;

-- зависимости одинаковы для обоих типов ВС
INSERT INTO ref.norm_dependency (norm_id, prev_norm_id)
SELECT n.id, p.id
FROM (VALUES ('DEBOARD', 'STAIRS'), ('FUEL', 'DEBOARD'), ('CLEAN', 'DEBOARD'), ('CATERING', 'DEBOARD'),
             ('LOAD', 'UNLOAD'), ('BOARD', 'INSPECT'), ('BOARD', 'FUEL'), ('BOARD', 'CLEAN'), ('BOARD', 'CATERING'),
             ('DEICE', 'BOARD'), ('CLOSE', 'LOAD'), ('CLOSE', 'BOARD'), ('CLOSE', 'DEICE')) AS v (op, prev)
JOIN ref.operation o  ON o.code = v.op
JOIN ref.operation po ON po.code = v.prev
JOIN ref.operation_norm n ON n.operation_id = o.id
JOIN ref.operation_norm p ON p.operation_id = po.id AND p.aircraft_type_id = n.aircraft_type_id;

-- коды задержек по стандарту IATA (учебная выборка)
INSERT INTO ref.delay_code (code, name) VALUES
    ('15', 'Посадка пассажиров'),
    ('18', 'Обработка багажа'),
    ('32', 'Погрузка и выгрузка багажа'),
    ('33', 'Погрузочное оборудование'),
    ('34', 'Оборудование обслуживания (трап, спецмашины)'),
    ('35', 'Уборка салона'),
    ('36', 'Заправка топливом'),
    ('37', 'Бортпитание'),
    ('41', 'Техническая неисправность ВС'),
    ('75', 'Противообледенительная обработка'),
    ('87', 'Аэродромная инфраструктура, стоянка'),
    ('93', 'Позднее прибытие ВС с предыдущего рейса');
