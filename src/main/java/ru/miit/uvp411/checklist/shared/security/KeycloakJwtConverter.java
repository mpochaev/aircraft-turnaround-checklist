package ru.miit.uvp411.checklist.shared.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Токен Keycloak в пользователя Spring Security.
 * <p>
 * Роли берутся из поля realm_access.roles токена (их назначают в Keycloak),
 * права к ролям из нашей базы (auth.role_permission) через {@link PermissionResolver}.
 * Имя пользователя: preferred_username, оно совпадает с auth.app_user.login.
 */
public class KeycloakJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final PermissionResolver resolver;

    public KeycloakJwtConverter(PermissionResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String login = jwt.getClaimAsString("preferred_username");
        return new JwtAuthenticationToken(jwt, Authorities.of(realmRoles(jwt), resolver), login != null ? login : jwt.getSubject());
    }

    @SuppressWarnings("unchecked")
    private static Collection<String> realmRoles(Jwt jwt) {
        Object access = jwt.getClaims().get("realm_access");
        if (access instanceof Map<?, ?> map && map.get("roles") instanceof Collection<?> roles) {
            return (Collection<String>) roles;
        }
        return List.of();
    }
}
