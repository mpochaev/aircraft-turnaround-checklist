package ru.miit.uvp411.checklist.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import ru.miit.uvp411.checklist.IntegrationTest;

@SpringBootTest(properties = "app.security.mode=keycloak")
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class AuthProfileSyncTest extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private MockMvc keycloakMvc;
    @MockitoBean private JwtDecoder jwtDecoder;
    @MockitoBean private KeycloakAdminClient keycloak;

    @BeforeEach
    void configureKeycloak() throws Exception {
        when(jwtDecoder.decode("valid-tech")).thenReturn(Jwt.withTokenValue("valid")
                .header("alg", "RS256")
                .subject("subject-tech07")
                .issuedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .expiresAt(Instant.parse("2027-01-01T00:00:00Z"))
                .claim("preferred_username", "tech07")
                .claim("realm_access", Map.of("roles", List.of("technician")))
                .build());
        when(keycloak.findUsers("tech07")).thenReturn(List.of(json("""
                {"id":"kc-tech07","username":"tech07","enabled":true,"firstName":"Пётр","lastName":"Иванов",
                 "attributes":{"middleName":["Иванович"]}}
                """)));
    }

    @Test
    void refreshesCurrentUsersNameFromKeycloakBeforeReturningProfile() throws Exception {
        jdbc.sql("update auth.app_user set middle_name = null where login = 'tech07'").update();

        keycloakMvc.perform(get("/api/v1/me").header("Authorization", "Bearer valid-tech"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.middleName").value("Иванович"));

        assertThat(jdbc.sql("select middle_name from auth.app_user where login = 'tech07'")
                .query(String.class).single()).isEqualTo("Иванович");
    }

    private static JsonNode json(String value) throws Exception {
        return JSON.readTree(value);
    }
}
