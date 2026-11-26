package ru.miit.uvp411.checklist.auth.internal;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import ru.miit.uvp411.checklist.auth.AuthApi;
import ru.miit.uvp411.checklist.shared.security.AppSecurityProperties;
import ru.miit.uvp411.checklist.shared.security.PermissionResolver;

@Service
class AuthService implements AuthApi {

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
        return jdbc.sql("""
                        select id, login, last_name, first_name, middle_name, is_active
                        from auth.app_user where login = :login
                        """)
                .param("login", login)
                .query((rs, n) -> new UserInfo(rs.getInt("id"), rs.getString("login"), rs.getString("last_name"),
                        rs.getString("first_name"), rs.getString("middle_name"), rs.getBoolean("is_active")))
                .optional();
    }
}
