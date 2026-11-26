package ru.miit.uvp411.checklist.ops;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import ru.miit.uvp411.checklist.IntegrationTest;

class TurnaroundListApiTest extends IntegrationTest {

    @Test
    void filtersTurnaroundsAndPaginatesWhileReturningFilteredTotal() throws Exception {
        LocalDateTime arrivalAt = LocalDate.now().plusDays(10).atTime(10, 0);
        int aircraft = data.aircraft("RA-73725");
        long first = createProblemTurnaround(aircraft, arrivalAt);
        long second = createProblemTurnaround(aircraft, arrivalAt.plusHours(1));
        int airlineId = jdbc.sql("select id from ref.airline where iata_code = 'SU'")
                .query(Integer.class).single();
        String standCode = jdbc.sql("""
                        select s.code from ops.turnaround t join ref.stand s on s.id = t.stand_id
                        where t.id = :id
                        """)
                .param("id", first).query(String.class).single();
        String flightNumber = jdbc.sql("""
                        select flight_number from ops.flight where id =
                            (select arr_flight_id from ops.turnaround where id = :id)
                        """)
                .param("id", first).query(String.class).single();

        mvc.perform(get("/api/v1/turnarounds")
                        .param("dateFrom", arrivalAt.toLocalDate().toString())
                        .param("dateTo", arrivalAt.toLocalDate().toString())
                        .param("status", "planned")
                        .param("airlineId", String.valueOf(airlineId))
                        .param("aircraftId", String.valueOf(aircraft))
                        .param("flightNumber", flightNumber)
                        .param("standFrom", standCode)
                        .param("standTo", standCode)
                        .param("onlyIssues", "true")
                        .param("page", "1")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.total", is(1)))
                .andExpect(jsonPath("$.items[0].arrFlight.code").isNotEmpty())
                .andExpect(jsonPath("$.items[0].depFlight.route").isNotEmpty())
                .andExpect(jsonPath("$.items[0].aircraft.tailNumber").isNotEmpty())
                .andExpect(jsonPath("$.items[0].progress.done").isNumber())
                .andExpect(jsonPath("$.items[0].operations[0].lagMin").isNumber())
                .andExpect(jsonPath("$.items[0].operations[0].allowedActions").isArray())
                .andExpect(jsonPath("$.summary.problems", is(1)))
                .andExpect(jsonPath("$.summary.delayed", is(0)));

        mvc.perform(get("/api/v1/turnarounds")
                        .param("dateFrom", arrivalAt.toLocalDate().toString())
                        .param("dateTo", arrivalAt.toLocalDate().toString())
                        .param("status", "planned,ready")
                        .param("aircraftId", String.valueOf(aircraft))
                        .param("onlyIssues", "true")
                        .param("page", "2")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.total", is(2)));

        org.assertj.core.api.Assertions.assertThat(first).isNotEqualTo(second);
    }

    @Test
    void onlyIssuesUsesProblemsAndObservedOperationLagNotForecastRisk() throws Exception {
        LocalDateTime arrivalAt = LocalDateTime.now().withSecond(0).withNano(0)
                .plusDays(2).withHour(10);
        long forecastRisk = data.turnaround("RA-73725", "planned", arrivalAt, arrivalAt.plusMinutes(35),
                null, null, null);
        data.operation(forecastRisk, "DEICE", "not_started",
                arrivalAt.plusMinutes(40), arrivalAt.plusMinutes(55), null, null);

        LocalDateTime laterArrival = arrivalAt.plusHours(1);
        long actualLag = data.turnaround("RA-89041", "planned", laterArrival, laterArrival.plusMinutes(90),
                null, null, null);
        LocalDateTime now = LocalDateTime.now().withSecond(0).withNano(0);
        data.operation(actualLag, "DEICE", "in_progress",
                now.minusMinutes(40), now.minusMinutes(20), null, null);

        mvc.perform(get("/api/v1/turnarounds").param("date", arrivalAt.toLocalDate().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(2)))
                .andExpect(jsonPath("$.items[0].id", is((int) forecastRisk)))
                .andExpect(jsonPath("$.items[0].atRisk", is(true)));

        mvc.perform(get("/api/v1/turnarounds")
                        .param("date", arrivalAt.toLocalDate().toString())
                        .param("onlyIssues", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id", is((int) actualLag)))
                .andExpect(jsonPath("$.items[0].operations[0].lagMin", greaterThanOrEqualTo(20)))
                .andExpect(jsonPath("$.summary.problems", is(0)))
                .andExpect(jsonPath("$.summary.delayed", is(1)));
    }

    @Test
    void supportsDateAliasAndRejectsInvalidPeriodStatusAndPaging() throws Exception {
        mvc.perform(get("/api/v1/turnarounds").param("date", LocalDate.now().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.total").isNumber());

        mvc.perform(get("/api/v1/turnarounds").param("dateFrom", LocalDate.now().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field", is("dateTo")));
        mvc.perform(get("/api/v1/turnarounds").param("status", "planned,unknown"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field", is("status")));
        mvc.perform(get("/api/v1/turnarounds").param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field", is("page")));
        mvc.perform(get("/api/v1/turnarounds").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field", is("size")));
        mvc.perform(get("/api/v1/turnarounds")
                        .param("dateFrom", LocalDate.now().minusDays(92).toString())
                        .param("dateTo", LocalDate.now().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field", is("dateTo")));
    }

    @Test
    void turnaroundDetailContainsExecutorWaitsAndTimingMetrics() throws Exception {
        LocalDateTime arrivalAt = LocalDateTime.now().withSecond(0).withNano(0).plusDays(7).withHour(10);
        long turnaround = data.turnaround("RA-73725", "planned", arrivalAt, arrivalAt.plusMinutes(70),
                null, null, null);
        long predecessor = data.operation(turnaround, "STAIRS", "not_started",
                arrivalAt, arrivalAt.plusMinutes(5), null, null);
        long operation = data.operation(turnaround, "DEBOARD", "in_progress",
                arrivalAt.plusMinutes(5), arrivalAt.plusMinutes(15), null, null);
        int technician = data.userId("tech07");
        jdbc.sql("""
                        update ops.operation_exec
                        set planned_start = localtimestamp - interval '40 minutes',
                            planned_end = localtimestamp - interval '10 minutes',
                            actual_start = localtimestamp - interval '30 minutes',
                            executor_id = :executor
                        where id = :id
                        """)
                .param("executor", technician).param("id", operation).update();
        jdbc.sql("insert into ops.exec_dependency (exec_id, prev_exec_id) values (:exec, :prev)")
                .param("exec", operation).param("prev", predecessor).update();

        mvc.perform(get("/api/v1/turnarounds/{id}", turnaround))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operations[1].id", is((int) operation)))
                .andExpect(jsonPath("$.operations[1].executor.id", is(technician)))
                .andExpect(jsonPath("$.operations[1].waitsFor[0]", is((int) predecessor)))
                .andExpect(jsonPath("$.operations[1].elapsedMin", greaterThanOrEqualTo(30)))
                .andExpect(jsonPath("$.operations[1].delayMin", greaterThanOrEqualTo(10)));
    }

    private long createProblemTurnaround(int aircraft, LocalDateTime arrivalAt) {
        long turnaround = data.turnaround(aircraft, data.stand(), "planned",
                data.flight("KGD", "SVO", arrivalAt.minusHours(2), arrivalAt),
                data.flight("SVO", "LED", arrivalAt.plusMinutes(70), arrivalAt.plusMinutes(155)),
                null, null, null);
        data.operation(turnaround, "STAIRS", "problem", arrivalAt, arrivalAt.plusMinutes(5), null, null);
        return turnaround;
    }
}
