package ru.miit.uvp411.checklist.log.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки загрузки событий входа из Keycloak, раздел {@code app.keycloak-events} в application.yml.
 *
 * @param enabled      загружать ли события (в профиле dev выключено)
 * @param url          адрес Keycloak со стороны сервера, в контейнере http://keycloak:8080
 * @param realm        realm Системы
 * @param clientId     служебный клиент с правами view-events и view-users
 * @param clientSecret его секрет
 */
@ConfigurationProperties(prefix = "app.keycloak-events")
record KeycloakEventsProperties(boolean enabled, String url, String realm, String clientId, String clientSecret) {
}
