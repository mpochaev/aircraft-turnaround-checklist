package ru.miit.uvp411.checklist.shared.web;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import ru.miit.uvp411.checklist.shared.security.AppSecurityProperties;

/**
 * Настройки для страниц: как входить (через Keycloak или в режиме разработки без него).
 * Доступно без токена, страницы вызывают его первым делом.
 */
@RestController
public class ConfigController {

    private final AppSecurityProperties props;

    public ConfigController(AppSecurityProperties props) {
        this.props = props;
    }

    @GetMapping("/api/v1/config")
    public Map<String, Object> config() {
        return Map.of(
                "securityMode", props.mode().name().toLowerCase(),
                "keycloak", Map.of(
                        "url", props.keycloak().url(),
                        "realm", props.keycloak().realm(),
                        "clientId", props.keycloak().clientId()),
                "refreshSeconds", 15);
    }
}
