package ru.miit.uvp411.checklist.log;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import ru.miit.uvp411.checklist.IntegrationTest;

/** GET /log: сценарии ПМИ 47 и 48, проверка 26 приложения 2, ограничения таблицы 8.2. */
class LogApiTest extends IntegrationTest {

    @Autowired
    AuditLog auditLog;

    @Autowired
    TransactionTemplate tx;

    long turnaround;
    long fuel;

    @BeforeEach
    void journal() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        turnaround = data.turnaround("RA-73725", "in_service", now.minusMinutes(30), now.plusMinutes(40),
                now.minusMinutes(25), null, null);
        fuel = data.operation(turnaround, "FUEL", "in_progress", now.minusMinutes(10), now.plusMinutes(10), null, null);
        record("tech07", "on_block", "turnaround", turnaround, turnaround, Map.of("status", "planned"), Map.of("status", "in_service"));
        record("tech07", "op_start", "operation_exec", fuel, turnaround, Map.of("status", "not_started"), Map.of("status", "in_progress"));
        record("sup01", "delay_code", "operation_exec", fuel, turnaround, null, Map.of("delayCode", "36"));
        record("tech12", "op_start", "operation_exec", fuel, null, null, null);
    }

    @Test
    void supervisorFiltersByTurnaroundNewestFirst() throws Exception {
        mvc.perform(get("/api/v1/log").param("turnaroundId", String.valueOf(turnaround)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(3)))
                .andExpect(jsonPath("$.items[0].actionType", is("delay_code")))
                .andExpect(jsonPath("$.items[0].actionName", is("Код задержки")))
                .andExpect(jsonPath("$.items[0].user.shortName", is("Петрова А.")))
                .andExpect(jsonPath("$.items[0].objectTitle", is("Оборот " + turnaround + " · Заправка топливом")))
                .andExpect(jsonPath("$.items[0].newValue.delayCode", is("36")))
                .andExpect(jsonPath("$.items[2].actionName", is("Постановка ВС")))
                .andExpect(jsonPath("$.items[2].objectTitle", is("Оборот " + turnaround)))
                .andExpect(jsonPath("$.items[2].turnaroundId", is((int) turnaround)));
    }

    @Test
    void filtersByActionAndPages() throws Exception {
        mvc.perform(get("/api/v1/log").param("turnaroundId", String.valueOf(turnaround))
                        .param("actionType", "op_start"))
                .andExpect(jsonPath("$.total", is(1)))
                .andExpect(jsonPath("$.items[0].user.login", is("tech07")));
        mvc.perform(get("/api/v1/log").param("turnaroundId", String.valueOf(turnaround))
                        .param("size", "1").param("page", "3"))
                .andExpect(jsonPath("$.total", is(3)))
                .andExpect(jsonPath("$.items.length()", is(1)))
                .andExpect(jsonPath("$.items[0].actionType", is("on_block")));
    }

    @Test
    void technicianSeesOnlyOwnRecords() throws Exception {
        mvc.perform(get("/api/v1/log").header("X-Dev-User", "tech07").header("X-Dev-Roles", "technician"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].user.login", everyItem(is("tech07"))))
                .andExpect(jsonPath("$.items[*].actionType", hasItem("op_start")));
        mvc.perform(get("/api/v1/log").param("userId", String.valueOf(data.userId("tech07")))
                        .header("X-Dev-User", "tech07").header("X-Dev-Roles", "technician"))
                .andExpect(status().isOk());
    }

    @Test
    void technicianGetsForbiddenForSomeoneElse() throws Exception {
        mvc.perform(get("/api/v1/log").param("userId", String.valueOf(data.userId("tech12")))
                        .header("X-Dev-User", "tech07").header("X-Dev-Roles", "technician"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code", is("FORBIDDEN")));
    }

    @Test
    void rejectsWrongParameters() throws Exception {
        LocalDate today = LocalDate.now();
        mvc.perform(get("/api/v1/log").param("from", today.minusDays(40) + "T00:00:00").param("to", today + "T23:59:59"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code", is("VALIDATION_ERROR")))
                .andExpect(jsonPath("$.error.details[0].field", is("to")));
        mvc.perform(get("/api/v1/log").param("from", today.minusDays(30) + "T00:00:00").param("to", today + "T23:59:59"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/log").param("actionType", "op_delete"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field", is("actionType")));
        mvc.perform(get("/api/v1/log").param("size", "101"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/log").param("page", "0"))
                .andExpect(status().isBadRequest());
    }

    private void record(String login, String type, String entity, Long entityId, Long turnaroundId,
                        Map<String, Object> oldValue, Map<String, Object> newValue) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login, null, List.of()));
        try {
            tx.executeWithoutResult(s -> auditLog.record(new AuditLog.Entry(type, entity, entityId, turnaroundId, oldValue, newValue)));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
