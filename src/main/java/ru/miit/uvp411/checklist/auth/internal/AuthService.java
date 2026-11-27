package ru.miit.uvp411.checklist.auth.internal;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import tools.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import ru.miit.uvp411.checklist.auth.AuthApi;
import ru.miit.uvp411.checklist.shared.security.AppSecurityProperties;
import ru.miit.uvp411.checklist.shared.security.PermissionResolver;

@Service
class AuthService implements AuthApi {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final JdbcClient jdbc;
    private final KeycloakAdminClient keycloak;
    private final PermissionResolver permissions;
    private final boolean devMode;

    AuthService(JdbcClient jdbc, KeycloakAdminClient keycloak, PermissionResolver permissions, AppSecurityProperties security) {
        this.jdbc = jdbc;
        this.keycloak = keycloak;
        this.permissions = permissions;
        this.devMode = security.mode() == AppSecurityProperties.Mode.DEV;
    }

    @Override
    public boolean hasPermission(int userId, String permission) {
        var user = jdbc.sql("select login, keycloak_id, is_active from auth.app_user where id = :id")
                .param("id", userId).query().listOfRows();
        if (user.isEmpty() || !Boolean.TRUE.equals(user.get(0).get("is_active"))) {
            return false;
        }
        if (devMode) {
            return true;
        }
        Object keycloakId = user.get(0).get("keycloak_id");
        String id = keycloakId != null ? keycloakId.toString()
                : keycloak.findUsers((String) user.get(0).get("login")).stream()
                        .map(u -> u.path("id").asText()).findFirst().orElse(null);
        if (id == null) {
            return false;
        }
        Set<String> roles = keycloak.userRoles(id).stream().map(r -> r.path("name").asText()).collect(Collectors.toSet());
        return permissions.permissionsOf(roles).contains(permission);
    }

    @Override
    public Optional<UserInfo> findUser(String login) {
        if (!devMode) {
            refreshProfileFromKeycloak(login);
        }
        return jdbc.sql("""
                        select id, login, last_name, first_name, middle_name, is_active
                        from auth.app_user where login = :login
                        """)
                .param("login", login)
                .query((rs, n) -> new UserInfo(rs.getInt("id"), rs.getString("login"), rs.getString("last_name"),
                        rs.getString("first_name"), rs.getString("middle_name"), rs.getBoolean("is_active")))
                .optional();
    }

    private void refreshProfileFromKeycloak(String login) {
        try {
            keycloak.findUsers(login).stream().findFirst().ifPresent(this::syncUser);
        } catch (RuntimeException e) {
            log.warn("Could not refresh current user's profile from Keycloak; using local data", e);
        }
    }

    private void syncUser(JsonNode kc) {
        String username = kc.path("username").asText();
        if (username.isBlank()) return;
        String middleName = kc.path("attributes").path("middleName").isArray()
                ? kc.path("attributes").path("middleName").path(0).asText(null) : null;
        jdbc.sql("""
                        insert into auth.app_user (login,last_name,first_name,middle_name,is_active,keycloak_id)
                        values (:login,:last,:first,:middle,:active,:kid)
                        on conflict (login) do update set last_name=excluded.last_name, first_name=excluded.first_name,
                        middle_name=excluded.middle_name, is_active=excluded.is_active, keycloak_id=excluded.keycloak_id
                        """)
                .param("login", username).param("last", kc.path("lastName").asText(""))
                .param("first", kc.path("firstName").asText("")).param("middle", middleName)
                .param("active", kc.path("enabled").asBoolean(true)).param("kid", kc.path("id").asText(null))
                .update();
    }
}
