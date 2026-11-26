-- Команда «Данные». Таблица auth.keycloak_event осталась от синхронизации событий Keycloak в модуле auth.
-- События входа в журнал переносит log.KeycloakEventImporter, таблица ему не нужна и нигде не используется.

DROP TABLE auth.keycloak_event;
