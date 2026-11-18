package ru.miit.uvp411.checklist.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;

class KeycloakJwtConverterTest {

    private final PermissionResolver permissions = mock(PermissionResolver.class);
    private final KeycloakJwtConverter converter = new KeycloakJwtConverter(permissions);

    @Test
    void extractsLoginRolesAndDatabasePermissionsFromKeycloakJwt() {
        when(permissions.permissionsOf(List.of("technician"))).thenReturn(Set.of("turnarounds.read_own"));
        Jwt jwt = jwt("tech07", Map.of("roles", List.of("technician")));

        var authentication = converter.convert(jwt);

        assertThat(authentication.getName()).isEqualTo("tech07");
        assertThat(AuthorityUtils.authorityListToSet(authentication.getAuthorities()))
                .containsExactlyInAnyOrder("ROLE_technician", "turnarounds.read_own");
    }

    @Test
    void fallsBackToSubjectAndDoesNotInventAuthoritiesWhenClaimsAreMissing() {
        Jwt jwt = jwt(null, Map.of());

        var authentication = converter.convert(jwt);

        assertThat(authentication.getName()).isEqualTo("subject-123");
        assertThat(authentication.getAuthorities()).isEmpty();
    }

    private static Jwt jwt(String username, Map<String, Object> realmAccess) {
        var builder = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .subject("subject-123")
                .issuedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .expiresAt(Instant.parse("2026-01-01T00:05:00Z"))
                .claim("realm_access", realmAccess);
        if (username != null) {
            builder.claim("preferred_username", username);
        }
        return builder.build();
    }
}
