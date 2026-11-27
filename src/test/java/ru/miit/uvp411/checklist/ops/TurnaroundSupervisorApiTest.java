package ru.miit.uvp411.checklist.ops;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import ru.miit.uvp411.checklist.IntegrationTest;

class TurnaroundSupervisorApiTest extends IntegrationTest {

    @Test
    void supervisorListIncludesOperationCellsAndAppliesServerFilters() throws Exception {
        LocalDateTime sta = LocalDate.now().atTime(9, 0);
        long turnaroundId = data.turnaround("RA-73725", "in_service", sta, sta.plusHours(2), sta, null, null);
        long operationId = data.operation(turnaroundId, "DEBOARD", "problem",
                sta.plusMinutes(5), sta.plusMinutes(15), null, null);
        String standCode = standCode(turnaroundId);
        Integer airlineId = jdbc.sql("""
                        select airline_id from ops.flight
                        where id = (select arr_flight_id from ops.turnaround where id = :id)
                        """).param("id", turnaroundId).query(Integer.class).single();

        mvc.perform(get("/api/v1/turnarounds")
                        .param("date", LocalDate.now().toString())
                        .param("standFrom", standCode)
                        .param("standTo", standCode)
                        .param("airlineId", airlineId.toString())
                        .param("onlyIssues", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", is(1)))
                .andExpect(jsonPath("$.items[0].id", is((int) turnaroundId)))
                .andExpect(jsonPath("$.items[0].operations[0].id", is((int) operationId)))
                .andExpect(jsonPath("$.items[0].operations[0].status", is("problem")))
                .andExpect(jsonPath("$.items[0].allowedActions.length()", is(0)))
                .andExpect(jsonPath("$.summary.problems", is(1)));
    }

    @Test
    void readyActionIsSupervisorOnlyAndCanBeAppliedWhenMandatoryWorkIsDone() throws Exception {
        LocalDateTime sta = LocalDate.now().atTime(9, 0);
        long turnaroundId = data.turnaround("RA-73725", "in_service", sta, sta.plusHours(2), sta, null, null);
        data.operation(turnaroundId, "DEBOARD", "done",
                sta.plusMinutes(5), sta.plusMinutes(15), sta.plusMinutes(15), null);
        String standCode = standCode(turnaroundId);

        mvc.perform(get("/api/v1/turnarounds/{id}", turnaroundId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedActions[0]", is("ready")));

        mvc.perform(get("/api/v1/turnarounds")
                        .param("date", LocalDate.now().toString())
                        .param("standFrom", standCode)
                        .param("standTo", standCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", is(1)))
                .andExpect(jsonPath("$.items[0].allowedActions[0]", is("ready")));

        mvc.perform(get("/api/v1/turnarounds/{id}", turnaroundId)
                        .header("X-Dev-User", "tech07").header("X-Dev-Roles", "technician"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedActions.length()", is(0)));

        mvc.perform(patch("/api/v1/turnarounds/{id}", turnaroundId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ready\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ready")))
                .andExpect(jsonPath("$.readyAt").isNotEmpty())
                .andExpect(jsonPath("$.allowedActions.length()", is(0)));
    }

    @Test
    void readyActionIsNotOfferedUntilAllMandatoryOperationsAreDone() throws Exception {
        LocalDateTime sta = LocalDate.now().atTime(9, 0);
        long turnaroundId = data.turnaround("RA-73725", "in_service", sta, sta.plusHours(2), sta, null, null);
        data.operation(turnaroundId, "DEBOARD", "done",
                sta.plusMinutes(5), sta.plusMinutes(15), sta.plusMinutes(15), null);
        data.operation(turnaroundId, "CLEAN", "not_started",
                sta.plusMinutes(15), sta.plusMinutes(25), null, null);

        mvc.perform(get("/api/v1/turnarounds/{id}", turnaroundId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedActions.length()", is(0)));

        mvc.perform(patch("/api/v1/turnarounds/{id}", turnaroundId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ready\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code", is("MANDATORY_NOT_DONE")));
    }

    @Test
    void technicianCannotConfirmReadinessAndOtherStatusesAreRejected() throws Exception {
        LocalDateTime sta = LocalDate.now().atTime(9, 0);
        long turnaroundId = data.turnaround("RA-73725", "in_service", sta, sta.plusHours(2), sta, null, null);
        data.operation(turnaroundId, "DEBOARD", "done",
                sta.plusMinutes(5), sta.plusMinutes(15), sta.plusMinutes(15), null);

        mvc.perform(patch("/api/v1/turnarounds/{id}", turnaroundId)
                        .header("X-Dev-User", "tech07").header("X-Dev-Roles", "technician")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ready\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code", is("FORBIDDEN")));

        long planned = data.turnaround("RA-89041", "planned", sta, sta.plusHours(2), null, null, null);
        mvc.perform(patch("/api/v1/turnarounds/{id}", planned)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ready\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code", is("INVALID_TRANSITION")));
        mvc.perform(get("/api/v1/turnarounds/{id}", planned))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedActions[0]", is("cancelled")));
    }

    /** Код стоянки оборота: у каждой тестовой стоянки он свой, по нему фильтр находит ровно этот оборот. */
    private String standCode(long turnaroundId) {
        return jdbc.sql("""
                        select s.code from ops.turnaround t join ref.stand s on s.id = t.stand_id where t.id = :id
                        """).param("id", turnaroundId).query(String.class).single();
    }
}
