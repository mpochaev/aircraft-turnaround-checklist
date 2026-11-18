package ru.miit.uvp411.checklist.shared.security;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;

import ru.miit.uvp411.checklist.IntegrationTest;

@SpringBootTest(properties = "app.security.mode=keycloak")
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class KeycloakApiSecurityTest extends IntegrationTest {

    @Autowired
    private MockMvc keycloakMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void configureDecoder() {
        when(jwtDecoder.decode("valid-tech")).thenReturn(jwt("tech07", "technician"));
        when(jwtDecoder.decode("valid-supervisor")).thenReturn(jwt("sup01", "supervisor"));
        when(jwtDecoder.decode("valid-unknown-role")).thenReturn(jwt("other", "unassigned"));
        when(jwtDecoder.decode("invalid")).thenThrow(new BadJwtException("Invalid test token"));
    }

    @Test
    void apiRequiresBearerTokenInKeycloakMode() throws Exception {
        keycloakMvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsInvalidBearerToken() throws Exception {
        keycloakMvc.perform(get("/api/v1/me").with(bearer("invalid")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void acceptsValidTokenAndBuildsCurrentUserFromClaimsAndDatabasePermissions() throws Exception {
        keycloakMvc.perform(get("/api/v1/me").with(bearer("valid-tech")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.login").value("tech07"))
                .andExpect(jsonPath("$.roles[0]").value("technician"))
                .andExpect(jsonPath("$.permissions").value(org.hamcrest.Matchers.hasItem("ref.read")));
    }

    @Test
    void grantsAndDeniesByDatabasePermissions() throws Exception {
        keycloakMvc.perform(get("/api/v1/ref").with(bearer("valid-tech")))
                .andExpect(status().isOk());
        keycloakMvc.perform(get("/api/v1/ref").with(bearer("valid-unknown-role")))
                .andExpect(status().isForbidden());
    }

    private static RequestPostProcessor bearer(String token) {
        return request -> {
            request.addHeader("Authorization", "Bearer " + token);
            return request;
        };
    }

    private static Jwt jwt(String username, String role) {
        return Jwt.withTokenValue("valid")
                .header("alg", "RS256")
                .subject("subject-" + username)
                .issuedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .expiresAt(Instant.parse("2026-01-01T00:05:00Z"))
                .claim("preferred_username", username)
                .claim("realm_access", Map.of("roles", List.of(role)))
                .build();
    }
}
