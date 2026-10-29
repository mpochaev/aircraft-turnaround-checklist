package ru.miit.uvp411.checklist.log;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ru.miit.uvp411.checklist.IntegrationTest;

/**
 * GET /metrics: сценарий 51 и проверка 27 приложения 2 ПМИ. Значения в проверках посчитаны вручную
 * по данным ниже, так же их считает испытатель по docs/metrics.md.
 */
class MetricsApiTest extends IntegrationTest {

    private static final String FROM = "2025-03-01";
    private static final String TO = "2025-03-07";

    @BeforeEach
    void turnarounds() {
        if (jdbc.sql("select count(*) from ops.turnaround where off_block_at between '2025-03-01' and '2025-03-08'")
                .query(Long.class).single() > 0) {
            return;   // данные уже есть: их создал предыдущий тест этого класса
        }
        // A320, 60 минут: заправка +5 мин с кодом 36, посадка и уборка в срок
        long t1 = data.closedTurnaround("RA-73725", at(3, "10:00"), at(3, "11:00"));
        data.operation(t1, "FUEL", "done", at(3, "10:15"), at(3, "10:35"), at(3, "10:40"), "36");
        data.operation(t1, "BOARD", "done", at(3, "10:35"), at(3, "10:55"), at(3, "10:55"), null);
        data.operation(t1, "CLEAN", "done", at(3, "10:15"), at(3, "10:30"), at(3, "10:29"), null);
        // A320, 54 минуты: заправка +2,5 мин (считается 3), бортпитание +15 мин с кодом 37
        long t2 = data.closedTurnaround("RA-73711", at(4, "12:00"), at(4, "12:54"));
        data.operation(t2, "FUEL", "done", at(4, "12:15"), at(4, "12:35"), at(4, "12:37").plusSeconds(30), null);
        data.operation(t2, "CATERING", "done", at(4, "12:15"), at(4, "12:25"), at(4, "12:40"), "37");
        // B777, 120 минут, всё в срок
        long t3 = data.closedTurnaround("RA-89041", at(5, "09:00"), at(5, "11:00"));
        data.operation(t3, "FUEL", "done", at(5, "09:20"), at(5, "09:55"), at(5, "09:50"), null);
        data.operation(t3, "BOARD", "done", at(5, "10:00"), at(5, "10:30"), at(5, "10:30"), null);
        // не считаются: отменённый оборот и оборот в работе
        data.turnaround("RA-73110", "cancelled", at(6, "10:00"), at(6, "11:00"), null, null, null);
        long t5 = data.turnaround("RA-73110", "in_service", at(6, "14:00"), at(6, "15:00"), at(6, "14:02"), null, null);
        data.operation(t5, "FUEL", "done", at(6, "14:15"), at(6, "14:35"), at(6, "15:30"), "36");
    }

    @Test
    void countsClosedTurnaroundsOnly() throws Exception {
        mvc.perform(get("/api/v1/metrics").param("dateFrom", FROM).param("dateTo", TO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turnaroundsClosed", is(3)))
                .andExpect(jsonPath("$.avgTurnaroundMin", is(78)))      // (60 + 54 + 120) / 3
                .andExpect(jsonPath("$.onTimeShare", is(0.57)))         // 4 операции в срок из 7
                .andExpect(jsonPath("$.topLateOperations[0].operationCode", is("FUEL")))
                .andExpect(jsonPath("$.topLateOperations[0].lateCount", is(2)))
                .andExpect(jsonPath("$.topLateOperations[0].avgDelayMin", is(4)))   // (5 + 3) / 2
                .andExpect(jsonPath("$.topLateOperations[1].operationCode", is("CATERING")))
                .andExpect(jsonPath("$.topLateOperations[1].avgDelayMin", is(15)))
                .andExpect(jsonPath("$.topLateOperations.length()", is(2)))
                .andExpect(jsonPath("$.topDelayCodes[0].code", is("36")))
                .andExpect(jsonPath("$.topDelayCodes[0].count", is(1)))
                .andExpect(jsonPath("$.topDelayCodes[1].code", is("37")));
    }

    @Test
    void filtersByAircraftType() throws Exception {
        mvc.perform(get("/api/v1/metrics").param("dateFrom", FROM).param("dateTo", TO)
                        .param("aircraftTypeId", String.valueOf(data.aircraftTypeId("A320"))))
                .andExpect(jsonPath("$.turnaroundsClosed", is(2)))
                .andExpect(jsonPath("$.avgTurnaroundMin", is(57)))
                .andExpect(jsonPath("$.onTimeShare", is(0.4)));
    }

    @Test
    void emptyPeriodGivesZeroAndNulls() throws Exception {
        mvc.perform(get("/api/v1/metrics").param("dateFrom", "2025-01-01").param("dateTo", "2025-01-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turnaroundsClosed", is(0)))
                .andExpect(jsonPath("$.avgTurnaroundMin", nullValue()))
                .andExpect(jsonPath("$.onTimeShare", nullValue()))
                .andExpect(jsonPath("$.topLateOperations.length()", is(0)));
    }

    @Test
    void rejectsWrongPeriodAndMissingRight() throws Exception {
        mvc.perform(get("/api/v1/metrics"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code", is("VALIDATION_ERROR")));
        mvc.perform(get("/api/v1/metrics").param("dateFrom", "2025-01-01").param("dateTo", "2025-04-03"))   // 93 дня
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field", is("dateTo")));
        mvc.perform(get("/api/v1/metrics").param("dateFrom", "2025-01-01").param("dateTo", "2025-04-02"))   // ровно 92 дня
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/metrics").param("dateFrom", FROM).param("dateTo", TO)
                        .header("X-Dev-User", "tech07").header("X-Dev-Roles", "technician"))
                .andExpect(status().isForbidden());
    }

    private static LocalDateTime at(int day, String time) {
        return LocalDateTime.parse(String.format("2025-03-%02dT%s:00", day, time));
    }
}
