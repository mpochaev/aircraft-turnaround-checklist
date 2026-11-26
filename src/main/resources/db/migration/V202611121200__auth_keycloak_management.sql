-- Authentication secrets and user-role assignments are owned by Keycloak (ADR-0002).
-- Keep application profile data locally for reports and foreign keys.
DROP VIEW IF EXISTS auth.v_user_permission;
DROP TABLE IF EXISTS auth.user_session;
DROP TABLE IF EXISTS auth.user_role;
ALTER TABLE auth.app_user
    DROP COLUMN IF EXISTS password_hash,
    DROP COLUMN IF EXISTS must_change_password,
    DROP COLUMN IF EXISTS failed_attempts,
    DROP COLUMN IF EXISTS locked_until,
    ADD COLUMN IF NOT EXISTS keycloak_id varchar(36);

-- The realm import contains these four local demonstration identities.
INSERT INTO auth.app_user (login, last_name, first_name, middle_name, is_active)
VALUES
    ('sup01', 'Петрова', 'Анна', NULL, true),
    ('sup02', 'Смирнов', 'Олег', NULL, true),
    ('tech07', 'Иванов', 'Пётр', NULL, true),
    ('tech12', 'Кузнецов', 'Дмитрий', NULL, true)
ON CONFLICT (login) DO NOTHING;

CREATE TABLE auth.keycloak_event (
    event_hash char(64) PRIMARY KEY,
    event_time timestamp NOT NULL,
    processed_at timestamp NOT NULL DEFAULT localtimestamp
);
CREATE INDEX ON auth.keycloak_event (event_time);
