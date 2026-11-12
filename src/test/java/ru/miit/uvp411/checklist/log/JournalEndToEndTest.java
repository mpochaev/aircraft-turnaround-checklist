package ru.miit.uvp411.checklist.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import ru.miit.uvp411.checklist.IntegrationTest;

/**
 * Журнал работает от начала до конца: действие другой команды попадает в журнал и читается на экране
 * понятным текстом (сценарий 47). И записи оборота около полуночи на стыке месяцев не теряются,
 * хотя лежат в двух секциях (вопрос преподавателя на паре 06.11).
 */
class JournalEndToEndTest extends IntegrationTest {

    @Test
    void flightCreatedByOpsIsInJournalWithNames() throws Exception {
        LocalDate day = LocalDate.now().plusDays(20);
        String number = String.valueOf(7000 + (int) (System.nanoTime() % 2000));
        mvc.perform(post("/api/v1/flights").contentType(MediaType.APPLICATION_JSON).content("""
                        {"airlineId": %d, "flightNumber": "%s", "letter": "", "flightDate": "%s",
                         "depAirportId": %d, "arrAirportId": %d, "std": "%sT08:00:00", "sta": "%sT10:00:00"}
                        """.formatted(id("ref.airline", "iata_code", "SU"), number, day,
                        id("ref.airport", "iata_code", "KGD"), id("ref.airport", "iata_code", "SVO"), day, day)))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/log").param("actionType", "flight_created").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].actionName", is("Создание рейса")))
                .andExpect(jsonPath("$.items[0].user.login", is("sup01")))
                .andExpect(jsonPath("$.items[0].objectTitle", is("Рейс SU " + number)))
                .andExpect(jsonPath("$.items[0].newText", startsWith("SU " + number + " KGD → SVO, вылет ")));
    }

    @Test
    void turnaroundAroundMidnightIsFoundInBothMonths() throws Exception {
        LocalDate lastDay = LocalDate.now().withDayOfMonth(1).plusMonths(1).minusDays(1);   // последний день месяца
        LocalDateTime before = lastDay.atTime(23, 55);
        LocalDateTime after = lastDay.plusDays(1).atTime(0, 10);
        long t = data.turnaround("RA-73725", "in_service", before.minusMinutes(5), after.plusMinutes(60), before, null, null);
        data.logRecord(before, "tech07", "on_block", "turnaround", t, t);
        data.logRecord(after, "tech07", "op_start", "turnaround", t, t);

        assertThat(jdbc.sql("select count(distinct tableoid) from log.action_log where turnaround_id = :t")
                .param("t", t).query(Long.class).single()).isEqualTo(2);   // записи в двух секциях

        mvc.perform(get("/api/v1/log").param("turnaroundId", String.valueOf(t))
                        .param("from", lastDay + "T00:00:00").param("to", lastDay.plusDays(1) + "T23:59:59"))
                .andExpect(jsonPath("$.total", is(2)))
                .andExpect(jsonPath("$.items[0].actionType", is("op_start")))
                .andExpect(jsonPath("$.items[1].actionType", is("on_block")));
    }

    private int id(String table, String column, String value) {
        return jdbc.sql("select id from " + table + " where " + column + " = :v").param("v", value)
                .query(Integer.class).single();
    }
}
