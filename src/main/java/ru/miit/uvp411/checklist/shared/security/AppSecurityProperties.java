package ru.miit.uvp411.checklist.shared.security;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки безопасности, раздел {@code app.security} в application.yml.
 *
 * @param mode     KEYCLOAK: запросы с токеном Keycloak; DEV: вход не нужен, все запросы идут от {@code dev.user}
 * @param keycloak адрес Keycloak для страниц (браузер получает токен сам)
 * @param dev      пользователь режима разработки
 */
@ConfigurationProperties(prefix = "app.security")
public record AppSecurityProperties(Mode mode, Keycloak keycloak, Dev dev) {

    public enum Mode { KEYCLOAK, DEV }

    public record Keycloak(String url, String realm, String clientId) {
    }

    /**
     * @param user  логин, от имени которого идут запросы в режиме DEV
     * @param roles его роли (коды из auth.role)
     */
    public record Dev(String user, List<String> roles) {
    }
}
