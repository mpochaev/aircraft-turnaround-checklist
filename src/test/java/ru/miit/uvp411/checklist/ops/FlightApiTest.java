package ru.miit.uvp411.checklist.ops;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import ru.miit.uvp411.checklist.IntegrationTest;

class FlightApiTest extends IntegrationTest {

    private static final AtomicInteger NUMBER = new AtomicInteger(100);

    @Test
    void createsFlightAndSearchesByDateAndRoute() throws Exception {
        LocalDate date = LocalDate.now().plusDays(2);
        String number = nextNumber();
        int airlineId = id("select id from ref.airline where iata_code = 'SU'");
        int kgdId = id("select id from ref.airport where iata_code = 'KGD'");
        int svoId = id("select id from ref.airport where iata_code = 'SVO'");

        mvc.perform(post("/api/v1/flights").contentType(MediaType.APPLICATION_JSON)
                        .content(flightJson(airlineId, number, "", date, kgdId, svoId,
                                date.atTime(8, 0), date.atTime(10, 0))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.flightNumber", is(number)))
                .andExpect(jsonPath("$.letter", is("")))
                .andExpect(jsonPath("$.airline.iataCode", is("SU")))
                .andExpect(jsonPath("$.depAirport", is("KGD")))
                .andExpect(jsonPath("$.arrAirport", is("SVO")));

        mvc.perform(get("/api/v1/flights").param("date", date.toString())
                        .param("arrAirportId", String.valueOf(svoId)).param("number", number))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].flightNumber", is(number)))
                .andExpect(jsonPath("$[0].sta", is(date + "T10:00:00")));
    }

    @Test
    void rejectsDuplicateFlightKeyButAllowsDifferentLetter() throws Exception {
        LocalDate date = LocalDate.now().plusDays(2);
        String number = nextNumber();
        int airlineId = id("select id from ref.airline where iata_code = 'SU'");
        int kgdId = id("select id from ref.airport where iata_code = 'KGD'");
        int svoId = id("select id from ref.airport where iata_code = 'SVO'");
        String body = flightJson(airlineId, number, "", date, kgdId, svoId, date.atTime(8, 0), date.atTime(10, 0));

        mvc.perform(post("/api/v1/flights").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/flights").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code", is("FLIGHT_EXISTS")));
        mvc.perform(post("/api/v1/flights").contentType(MediaType.APPLICATION_JSON)
                        .content(flightJson(airlineId, number, "A", date, kgdId, svoId,
                                date.atTime(8, 0), date.atTime(10, 0))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.letter", is("A")));

        int logged = jdbc.sql("""
                        select count(*) from log.action_log
                        where action_type = 'flight_created'
                          and entity = 'flight'
                          and new_value ->> 'flightNumber' = :number
                        """)
                .param("number", number).query(Integer.class).single();
        org.junit.jupiter.api.Assertions.assertEquals(2, logged);
    }

    @Test
    void freeFilterExcludesFlightsUsedByNonCancelledTurnarounds() throws Exception {
        LocalDateTime now = LocalDateTime.now().withNano(0).plusDays(3);
        long arrival = data.flight("KGD", "SVO", now, now.plusHours(2));
        long departure = data.flight("SVO", "LED", now.plusHours(3), now.plusHours(4));
        data.turnaround(data.aircraft("RA-73725"), data.stand(), "planned", arrival, departure, null, null, null);
        LocalDate date = now.toLocalDate();

        mvc.perform(get("/api/v1/flights").param("date", date.toString()).param("free", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + arrival + ")]").doesNotExist());
        mvc.perform(get("/api/v1/flights").param("date", date.toString()).param("free", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + arrival + ")]").exists());
    }

    private int id(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }

    private static String nextNumber() {
        return String.valueOf(NUMBER.getAndIncrement());
    }

    private static String flightJson(int airlineId, String number, String letter, LocalDate date,
                                     int depAirportId, int arrAirportId, LocalDateTime std, LocalDateTime sta) {
        return """
                {"airlineId":%d,"flightNumber":"%s","letter":"%s","flightDate":"%s",
                 "depAirportId":%d,"arrAirportId":%d,"std":"%s","sta":"%s"}
                """.formatted(airlineId, number, letter, date, depAirportId, arrAirportId, std, sta);
    }
}
