package ru.miit.uvp411.checklist.shared.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import ru.miit.uvp411.checklist.IntegrationTest;

class DevAuthenticationTest extends IntegrationTest {

    @Test
    void configClearlyReportsDevMode() throws Exception {
        mvc.perform(get("/api/v1/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.securityMode").value("dev"));
    }

    @Test
    void usesConfiguredSupervisorByDefaultAndReturnsPermissions() throws Exception {
        mvc.perform(get("/api/v1/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.login").value("sup01"))
                .andExpect(jsonPath("$.roles[0]").value("supervisor"))
                .andExpect(jsonPath("$.permissions").isArray());
    }

    @Test
    void devHeadersCanSelectTechnicianAndPermissionSet() throws Exception {
        mvc.perform(get("/api/v1/me").with(devUser("tech07", "technician")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.login").value("tech07"))
                .andExpect(jsonPath("$.roles[0]").value("technician"))
                .andExpect(jsonPath("$.permissions").value(org.hamcrest.Matchers.hasItem("ref.read")));
    }

    @Test
    void deniesEndpointWhenDevRoleHasNoRequiredPermission() throws Exception {
        mvc.perform(get("/api/v1/ref").with(devUser("nobody", "unassigned")))
                .andExpect(status().isForbidden());
    }

    private static RequestPostProcessor devUser(String login, String role) {
        return request -> {
            request.addHeader("X-Dev-User", login);
            request.addHeader("X-Dev-Roles", role);
            return request;
        };
    }
}
