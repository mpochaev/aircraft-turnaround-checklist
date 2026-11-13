package ru.miit.uvp411.checklist.ops;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import ru.miit.uvp411.checklist.IntegrationTest;

class TurnaroundApiTest extends IntegrationTest {

    @Test
    void createsTurnaroundWithPlanDependencySnapshotAndAuditRecord() throws Exception {
        int aircraft = data.aircraft("RA-73725");
        int stand = data.stand();
        int technician = data.userId("tech07");
        long[] flights = flights();

        mvc.perform(post("/api/v1/turnarounds").contentType(MediaType.APPLICATION_JSON)
                        .content(request(flights[0], flights[1], aircraft, stand, technician)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("planned")))
                .andExpect(jsonPath("$.operations", not(empty())));
        long turnaroundId = jdbc.sql("select id from ops.turnaround where arr_flight_id = :flight")
                .param("flight", flights[0]).query(Long.class).single();

        int operationCount = jdbc.sql("select count(*) from ops.operation_exec where turnaround_id = :id")
                .param("id", turnaroundId).query(Integer.class).single();
        int dependencyCount = jdbc.sql("""
                        select count(*) from ops.exec_dependency d
                        join ops.operation_exec e on e.id = d.exec_id
                        where e.turnaround_id = :id
                        """)
                .param("id", turnaroundId).query(Integer.class).single();
        int auditCount = jdbc.sql("""
                        select count(*) from log.action_log
                        where action_type = 'turnaround_created' and entity_id = :id
                        """)
                .param("id", turnaroundId).query(Integer.class).single();

        org.assertj.core.api.Assertions.assertThat(operationCount).isEqualTo(12);
        org.assertj.core.api.Assertions.assertThat(dependencyCount).isGreaterThan(0);
        org.assertj.core.api.Assertions.assertThat(auditCount).isEqualTo(1);
        LocalDateTime plannedStart = jdbc.sql("""
                        select planned_start from ops.operation_exec e
                        join ref.operation o on o.id = e.operation_id
                        where e.turnaround_id = :id and o.code = 'STAIRS'
                        """)
                .param("id", turnaroundId).query(LocalDateTime.class).single();
        LocalDateTime arrivalAt = jdbc.sql("select sta from ops.flight where id = :id")
                .param("id", flights[0]).query(LocalDateTime.class).single();
        org.assertj.core.api.Assertions.assertThat(plannedStart).isEqualTo(arrivalAt);
    }

    @Test
    void rejectsUsedFlightWithConflictCode() throws Exception {
        int aircraft = data.aircraft("RA-73725");
        int stand = data.stand();
        int technician = data.userId("tech07");
        long[] flights = flights();
        data.turnaround(data.aircraft("RA-89041"), data.stand(), "planned",
                flights[0], flights[1], null, null, null);
        long[] otherFlights = flights();

        mvc.perform(post("/api/v1/turnarounds").contentType(MediaType.APPLICATION_JSON)
                        .content(request(flights[0], otherFlights[1], aircraft, stand, technician)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code", is("FLIGHT_ALREADY_USED")));
    }

    @Test
    void rejectsBusyAircraftAndMismatchedRoute() throws Exception {
        int aircraft = data.aircraft("RA-73725");
        int stand = data.stand();
        int busyStand = data.stand();
        int technician = data.userId("tech07");
        LocalDateTime arrivalAt = futureArrival();
        data.turnaround(aircraft, busyStand, "in_service",
                data.flight("KGD", "SVO", arrivalAt.minusHours(2), arrivalAt),
                data.flight("SVO", "LED", arrivalAt.plusMinutes(70), arrivalAt.plusMinutes(155)),
                arrivalAt, null, null);
        long[] newFlights = flights();

        mvc.perform(post("/api/v1/turnarounds").contentType(MediaType.APPLICATION_JSON)
                        .content(request(newFlights[0], newFlights[1], aircraft, stand, technician)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code", is("AIRCRAFT_BUSY")));

        long[] standFlights = flights();
        mvc.perform(post("/api/v1/turnarounds").contentType(MediaType.APPLICATION_JSON)
                        .content(request(standFlights[0], standFlights[1], data.aircraft("RA-89041"), busyStand, technician)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code", is("AIRCRAFT_BUSY")));

        long arrival = data.flight("KGD", "SVO", arrivalAt.minusHours(2), arrivalAt);
        long departure = data.flight("LED", "SVO", arrivalAt.plusMinutes(70), arrivalAt.plusMinutes(155));
        mvc.perform(post("/api/v1/turnarounds").contentType(MediaType.APPLICATION_JSON)
                        .content(request(arrival, departure, data.aircraft("RA-89041"), data.stand(), technician)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code", is("ROUTE_MISMATCH")));
    }

    @Test
    void rejectsDepartureBeforeArrivalAndInactiveAircraft() throws Exception {
        int technician = data.userId("tech07");
        LocalDateTime arrivalAt = futureArrival();
        long arrival = data.flight("KGD", "SVO", arrivalAt.minusHours(2), arrivalAt);
        long earlyDeparture = data.flight("SVO", "LED", arrivalAt.minusMinutes(1), arrivalAt.plusMinutes(84));

        mvc.perform(post("/api/v1/turnarounds").contentType(MediaType.APPLICATION_JSON)
                        .content(request(arrival, earlyDeparture, data.aircraft("RA-73725"), data.stand(), technician)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code", is("TIME_ORDER")));

        int inactiveAircraft = data.aircraft("RA-89041");
        jdbc.sql("update ref.aircraft set is_active = false where id = :id").param("id", inactiveAircraft).update();
        long[] flights = flights();
        mvc.perform(post("/api/v1/turnarounds").contentType(MediaType.APPLICATION_JSON)
                        .content(request(flights[0], flights[1], inactiveAircraft, data.stand(), technician)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code", is("INACTIVE_REFERENCE")));
    }

    @Test
    void rejectsAircraftTypeWithoutActiveNorms() throws Exception {
        int type = jdbc.sql("insert into ref.aircraft_type (code, name) values ('TST', 'Test type') returning id")
                .query(Integer.class).single();
        int airline = jdbc.sql("select id from ref.airline where iata_code = 'SU'").query(Integer.class).single();
        int aircraft = jdbc.sql("""
                        insert into ref.aircraft (tail_number, aircraft_type_id, airline_id)
                        values ('RA-TST', :type, :airline) returning id
                        """)
                .param("type", type).param("airline", airline).query(Integer.class).single();
        long[] flights = flights();

        mvc.perform(post("/api/v1/turnarounds").contentType(MediaType.APPLICATION_JSON)
                        .content(request(flights[0], flights[1], aircraft, data.stand(), data.userId("tech07"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code", is("NO_NORMS")));
    }

    @Test
    void rejectsInactiveStandAndTechnician() throws Exception {
        int aircraft = data.aircraft("RA-73725");
        int stand = data.stand();
        int technician = data.userId("tech12");
        long[] flights = flights();

        jdbc.sql("update ref.stand set is_active = false where id = :id").param("id", stand).update();
        mvc.perform(post("/api/v1/turnarounds").contentType(MediaType.APPLICATION_JSON)
                        .content(request(flights[0], flights[1], aircraft, stand, technician)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code", is("INACTIVE_REFERENCE")));

        int activeStand = data.stand();
        jdbc.sql("update auth.app_user set is_active = false where id = :id").param("id", technician).update();
        long[] otherFlights = flights();
        mvc.perform(post("/api/v1/turnarounds").contentType(MediaType.APPLICATION_JSON)
                        .content(request(otherFlights[0], otherFlights[1], aircraft, activeStand, technician)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code", is("INACTIVE_REFERENCE")));
    }

    private long[] flights() {
        LocalDateTime arrivalAt = futureArrival();
        return new long[]{
                data.flight("KGD", "SVO", arrivalAt.minusHours(2), arrivalAt),
                data.flight("SVO", "LED", arrivalAt.plusMinutes(70), arrivalAt.plusMinutes(155)),
        };
    }

    private static LocalDateTime futureArrival() {
        return LocalDateTime.now().withNano(0).plusDays(5).withHour(10).withMinute(0);
    }

    private static String request(long arrival, long departure, int aircraft, int stand, int technician) {
        return """
                {"arrFlightId":%d,"depFlightId":%d,"aircraftId":%d,"standId":%d,"technicianId":%d}
                """.formatted(arrival, departure, aircraft, stand, technician);
    }
}
