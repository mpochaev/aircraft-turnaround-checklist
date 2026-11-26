package ru.miit.uvp411.checklist.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import ru.miit.uvp411.checklist.IntegrationTest;

class TurnaroundPatchApiTest extends IntegrationTest {

    @Test
    void cancellationRequiresReasonAndWritesReasonToAudit() throws Exception {
        long turnaround = plannedTurnaround();

        mvc.perform(patch("/api/v1/turnarounds/{id}", turnaround)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"cancelled\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code", is("VALIDATION_ERROR")));
        mvc.perform(patch("/api/v1/turnarounds/{id}", turnaround)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"cancelled\",\"cancelReason\":\"   \"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code", is("VALIDATION_ERROR")));
        assertThat(turnaroundStatus(turnaround)).isEqualTo("planned");

        mvc.perform(patch("/api/v1/turnarounds/{id}", turnaround)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"cancelled\",\"cancelReason\":\"Погодные условия\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("cancelled")));

        var audit = jdbc.sql("""
                        select old_value ->> 'status' as before, new_value ->> 'status' as after,
                               new_value ->> 'cancelReason' as reason
                        from log.action_log
                        where action_type = 'turnaround_cancelled' and turnaround_id = :id
                        """)
                .param("id", turnaround).query().singleRow();
        assertThat(audit).containsEntry("before", "planned")
                .containsEntry("after", "cancelled").containsEntry("reason", "Погодные условия");
    }

    @Test
    void acceptsMaximumCancellationReasonLength() throws Exception {
        long turnaround = plannedTurnaround();
        String reason = "x".repeat(300);

        mvc.perform(patch("/api/v1/turnarounds/{id}", turnaround)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"cancelled\",\"cancelReason\":\"" + "x".repeat(301) + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code", is("VALIDATION_ERROR")));
        assertThat(turnaroundStatus(turnaround)).isEqualTo("planned");

        mvc.perform(patch("/api/v1/turnarounds/{id}", turnaround)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"cancelled\",\"cancelReason\":\"" + reason + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("cancelled")));
        assertThat(jdbc.sql("select length(cancel_reason) from ops.turnaround where id = :id")
                .param("id", turnaround).query(Integer.class).single()).isEqualTo(300);
    }

    @Test
    void replacesTechnicianOnlyForManagerAndAuditsTheChange() throws Exception {
        long turnaround = plannedTurnaround();
        int previousTechnician = data.userId("tech07");
        int nextTechnician = data.userId("tech12");

        mvc.perform(patch("/api/v1/turnarounds/{id}", turnaround)
                        .header("X-Dev-User", "tech07").header("X-Dev-Roles", "technician")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"technicianId\":" + nextTechnician + "}"))
                .andExpect(status().isForbidden());
        assertThat(turnaroundTechnician(turnaround)).isEqualTo(previousTechnician);

        mvc.perform(patch("/api/v1/turnarounds/{id}", turnaround)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"technicianId\":" + nextTechnician + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.technician.id", is(nextTechnician)));

        var audit = jdbc.sql("""
                        select old_value ->> 'technicianId' as before, new_value ->> 'technicianId' as after
                        from log.action_log
                        where action_type = 'technician_changed' and turnaround_id = :id
                        """)
                .param("id", turnaround).query().singleRow();
        assertThat(audit).containsEntry("before", String.valueOf(previousTechnician))
                .containsEntry("after", String.valueOf(nextTechnician));
    }

    @Test
    void rejectsChangingTechnicianAfterCancellation() throws Exception {
        long turnaround = plannedTurnaround();
        mvc.perform(patch("/api/v1/turnarounds/{id}", turnaround)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"cancelled\",\"cancelReason\":\"Отмена рейса\"}"))
                .andExpect(status().isOk());

        mvc.perform(patch("/api/v1/turnarounds/{id}", turnaround)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"technicianId\":" + data.userId("tech12") + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code", is("INVALID_TRANSITION")));
    }

    private long plannedTurnaround() {
        LocalDateTime arrival = LocalDateTime.now().withSecond(0).withNano(0).plusDays(5).withHour(10);
        return data.turnaround("RA-73725", "planned", arrival, arrival.plusMinutes(90), null, null, null);
    }

    private String turnaroundStatus(long id) {
        return jdbc.sql("select status from ops.turnaround where id = :id").param("id", id)
                .query(String.class).single();
    }

    private int turnaroundTechnician(long id) {
        return jdbc.sql("select technician_id from ops.turnaround where id = :id").param("id", id)
                .query(Integer.class).single();
    }
}
