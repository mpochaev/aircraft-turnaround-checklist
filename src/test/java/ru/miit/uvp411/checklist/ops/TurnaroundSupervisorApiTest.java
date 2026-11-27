package ru.miit.uvp411.checklist.ops;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import ru.miit.uvp411.checklist.IntegrationTest;

class TurnaroundSupervisorApiTest extends IntegrationTest {

    @Test
    void supervisorCanPutAircraftOnBlock() throws Exception {
        LocalDateTime sta = LocalDateTime.now().plusDays(2).withSecond(0).withNano(0);
        long turnaroundId = data.turnaround("RA-73725", "planned", sta, sta.plusHours(2), null, null, null);
        data.operation(turnaroundId, "DEBOARD", "not_started",
                sta.plusMinutes(5), sta.plusMinutes(15), null, null);

        mvc.perform(get("/api/v1/turnarounds/{id}", turnaroundId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedActions[0]", is("in_service")));

        mvc.perform(patch("/api/v1/turnarounds/{id}", turnaroundId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"in_service\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("in_service")))
                .andExpect(jsonPath("$.onBlockAt").isNotEmpty());

        org.assertj.core.api.Assertions.assertThat(jdbc.sql("""
                        select count(*) from log.action_log
                        where action_type = 'on_block' and turnaround_id = :id
                        """)
                .param("id", turnaroundId).query(Integer.class).single()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbc.sql("""
                        select planned_start from ops.operation_exec where turnaround_id = :id
                        """)
                .param("id", turnaroundId).query(LocalDateTime.class).single()).isBefore(sta.plusMinutes(5));
    }

    @Test
    void onlyAssignedTechnicianCanPutAircraftOnBlockWithoutSupervisorRole() throws Exception {
        LocalDateTime sta = LocalDateTime.now().plusDays(2).withSecond(0).withNano(0);
        long turnaroundId = data.turnaround("RA-73725", "planned", sta, sta.plusHours(2), null, null, null);
        data.operation(turnaroundId, "DEBOARD", "not_started",
                sta.plusMinutes(5), sta.plusMinutes(15), null, null);

        mvc.perform(patch("/api/v1/turnarounds/{id}", turnaroundId)
                        .header("X-Dev-User", "tech12").header("X-Dev-Roles", "technician")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"in_service\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(patch("/api/v1/turnarounds/{id}", turnaroundId)
                        .header("X-Dev-User", "tech07").header("X-Dev-Roles", "technician")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"in_service\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("in_service")));
    }

    @Test
    void supervisorListIncludesOperationCellsAndAppliesServerFilters() throws Exception {
        LocalDateTime sta = LocalDate.now().atTime(9, 0);
        long turnaroundId = data.turnaround("RA-73725", "in_service", sta, sta.plusHours(2), sta, null, null);
        long operationId = data.operation(turnaroundId, "DEBOARD", "problem",
                sta.plusMinutes(5), sta.plusMinutes(15), null, null);
        String standCode = assignNumericStand(turnaroundId);
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
        String standCode = assignNumericStand(turnaroundId);

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
                .andExpect(jsonPath("$.error.code", is("INVALID_TRANSITION")));
    }

    private String assignNumericStand(long turnaroundId) {
        String standCode = Integer.toString(10_000 + Math.floorMod(UUID.randomUUID().hashCode(), 80_000));
        Integer standId = jdbc.sql("""
                        select stand_id from ops.turnaround where id = :id
                        """).param("id", turnaroundId).query(Integer.class).single();
        jdbc.sql("update ref.stand set code = :code where id = :id")
                .param("code", standCode).param("id", standId).update();
        return standCode;
    }
}
