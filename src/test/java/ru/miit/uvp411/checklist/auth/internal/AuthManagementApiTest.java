package ru.miit.uvp411.checklist.auth.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import ru.miit.uvp411.checklist.IntegrationTest;

class AuthManagementApiTest extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired private MockMvc keycloakMvc;
    @MockitoBean private KeycloakAdminClient keycloak;

    @BeforeEach
    void clearRequests() { jdbc.sql("delete from auth.change_request").update(); }

    @Test
    void listsUsersFromKeycloakAndMapsRealmRoles() throws Exception {
        when(keycloak.users()).thenReturn(List.of(json("""
                {"id":"kc-tech07","username":"tech07","enabled":true,"firstName":"Пётр","lastName":"Иванов"}
                """)));
        when(keycloak.userRoles("kc-tech07")).thenReturn(List.of(json("{\"name\":\"technician\"}")));
        keycloakMvc.perform(get("/api/v1/users").param("role","technician")
                        .header("X-Dev-User","sup01").header("X-Dev-Roles","supervisor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].login").value("tech07"))
                .andExpect(jsonPath("$.items[0].roles[0].code").value("technician"));
    }

    @Test
    void createsUserWithOneTimeTemporaryPasswordAndSyncsLocalProfile() throws Exception {
        when(keycloak.findUsers("new.tech")).thenReturn(List.of());
        when(keycloak.createUser(any())).thenReturn("kc-new-user");
        when(keycloak.role("technician")).thenReturn(json("{\"id\":\"r-tech\",\"name\":\"technician\"}"));
        doNothing().when(keycloak).addUserRole("kc-new-user",json("{\"id\":\"r-tech\",\"name\":\"technician\"}"));
        when(keycloak.user("kc-new-user")).thenReturn(json("""
                {"id":"kc-new-user","username":"new.tech","enabled":true,"firstName":"Иван","lastName":"Новый",
                 "attributes":{"middleName":["Иванович"]}}
                """));
        keycloakMvc.perform(post("/api/v1/users")
                        .header("X-Dev-User","sup01").header("X-Dev-Roles","supervisor")
                        .contentType("application/json")
                        .content("""
                                {"login":"new.tech","lastName":"Новый","firstName":"Иван","middleName":"Иванович","roleIds":[1]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.login").value("new.tech"))
                .andExpect(jsonPath("$.temporaryPassword").isNotEmpty());
        org.assertj.core.api.Assertions.assertThat(data.userId("new.tech")).isPositive();
    }

    @Test
    void rejectsUnknownRoleBeforeCreatingKeycloakAccount() throws Exception {
        keycloakMvc.perform(post("/api/v1/users")
                        .header("X-Dev-User","sup01").header("X-Dev-Roles","supervisor")
                        .contentType("application/json")
                        .content("""
                                {"login":"new.tech","lastName":"Новый","firstName":"Иван","roleIds":[9999]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ROLE_NOT_FOUND"));
        org.mockito.Mockito.verify(keycloak,org.mockito.Mockito.never()).createUser(any());
    }

    @Test
    void createsPersonalDataRequestAndReturnsItForProfile() throws Exception {
        keycloakMvc.perform(post("/api/v1/me/requests")
                        .header("X-Dev-User","tech07").header("X-Dev-Roles","technician")
                        .contentType("application/json")
                        .content("""
                                {"lastName":"Сидоров","reason":"Изменение фамилии"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("pending"));
        keycloakMvc.perform(get("/api/v1/me")
                        .header("X-Dev-User","tech07").header("X-Dev-Roles","technician"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingRequests[0].kind").value("personal_data"));
    }

    @Test
    void passwordResetIsPublicAndDoesNotRevealUnknownAccounts() throws Exception {
        keycloakMvc.perform(post("/api/v1/auth/password-reset")
                        .contentType("application/json").content("{\"login\":\"missing.user\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value("Если учётная запись существует, заявка направлена супервайзеру"));
    }

    @Test
    void allowsPasswordResetRequestsFromTheConfiguredKeycloakOrigin() throws Exception {
        keycloakMvc.perform(options("/api/v1/auth/password-reset")
                        .header("Origin", "http://localhost:8180")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Access-Control-Allow-Origin", "http://localhost:8180"));
    }

    @Test
    void passwordResetForKnownUserCreatesPendingRequest() throws Exception {
        when(keycloak.findUsers("tech07")).thenReturn(List.of(json("""
                {"id":"kc-tech07","username":"tech07","enabled":true,"firstName":"Пётр","lastName":"Иванов"}
                """)));
        for (int attempt = 0; attempt < 2; attempt++) {
            keycloakMvc.perform(post("/api/v1/auth/password-reset")
                            .contentType("application/json")
                            .content("{\"login\":\"tech07\",\"reason\":\"Потерял пароль\"}"))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.status").value("pending"))
                    .andExpect(jsonPath("$.message").value("Если учётная запись существует, заявка направлена супервайзеру"))
                    .andExpect(jsonPath("$.id").doesNotExist());
        }
        org.assertj.core.api.Assertions.assertThat(jdbc.sql("select count(*) from auth.change_request where kind='password_reset' and user_id=:id")
                .param("id",data.userId("tech07")).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void permissionDirectoryIsForbiddenToTechnicians() throws Exception {
        keycloakMvc.perform(get("/api/v1/permissions")
                        .header("X-Dev-User","tech07").header("X-Dev-Roles","technician"))
                .andExpect(status().isForbidden());
    }

    @Test
    void createsRoleInDatabaseAndKeycloakWithValidatedPermissions() throws Exception {
        when(keycloak.roles()).thenReturn(List.of(
                json("{\"name\":\"technician\"}"),json("{\"name\":\"supervisor\"}"),
                json("{\"name\":\"fueler\"}")));
        when(keycloak.users()).thenReturn(List.of());
        keycloakMvc.perform(post("/api/v1/roles")
                        .header("X-Dev-User","sup01").header("X-Dev-Roles","supervisor")
                        .contentType("application/json")
                        .content("""
                                {"code":"fueler","name":"Заправщик","permissionCodes":["ref.read","operations.execute"]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("fueler"))
                .andExpect(jsonPath("$.permissions.length()").value(2));
        verify(keycloak).createRole("fueler","Заправщик");
    }

    @Test
    void preventsRemovingRoleManagementRightFromSystemSupervisor() throws Exception {
        keycloakMvc.perform(patch("/api/v1/roles/2")
                        .header("X-Dev-User","sup01").header("X-Dev-Roles","supervisor")
                        .contentType("application/json")
                        .content("""
                                {"permissionCodes":["ref.read"]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SYSTEM_ROLE"));
    }

    @Test
    void approvesPersonalDataRequestInDatabaseAndKeycloak() throws Exception {
        keycloakMvc.perform(post("/api/v1/me/requests")
                        .header("X-Dev-User","tech07").header("X-Dev-Roles","technician")
                        .contentType("application/json")
                        .content("""
                                {"lastName":"Сидоров","reason":"Изменение фамилии"}
                                """))
                .andExpect(status().isAccepted());
        long requestId = latestPersonalRequest();
        when(keycloak.findUsers("tech07")).thenReturn(List.of(json("""
                {"id":"kc-tech07","username":"tech07","enabled":true,"firstName":"Пётр","lastName":"Иванов"}
                """)));
        when(keycloak.user("kc-tech07")).thenReturn(json("""
                {"id":"kc-tech07","username":"tech07","enabled":true,"firstName":"Пётр","lastName":"Иванов"}
                """));
        keycloakMvc.perform(patch("/api/v1/change-requests/" + requestId)
                        .header("X-Dev-User","sup01").header("X-Dev-Roles","supervisor")
                        .contentType("application/json")
                        .content("""
                                {"status":"approved"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request.status").value("approved"))
                .andExpect(jsonPath("$.request.reviewedAt").isNotEmpty());
        org.assertj.core.api.Assertions.assertThat(jdbc.sql("select last_name from auth.app_user where login='tech07'")
                .query(String.class).single()).isEqualTo("Сидоров");
        verify(keycloak).updateUser(org.mockito.ArgumentMatchers.eq("kc-tech07"),any());
    }

    @Test
    void requiresCommentToRejectRequest() throws Exception {
        keycloakMvc.perform(post("/api/v1/me/requests")
                        .header("X-Dev-User","tech07").header("X-Dev-Roles","technician")
                        .contentType("application/json").content("""
                                {"lastName":"Сидоров","reason":"Изменение фамилии"}
                                """))
                .andExpect(status().isAccepted());
        long requestId = latestPersonalRequest();
        keycloakMvc.perform(patch("/api/v1/change-requests/" + requestId)
                        .header("X-Dev-User","sup01").header("X-Dev-Roles","supervisor")
                        .contentType("application/json").content("""
                                {"status":"rejected"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("COMMENT_REQUIRED"));
    }

    private long latestPersonalRequest() {
        return jdbc.sql("select max(id) from auth.change_request where kind='personal_data'")
                .query(Long.class).single();
    }

    private static JsonNode json(String text) { return JSON.readTree(text); }
}
