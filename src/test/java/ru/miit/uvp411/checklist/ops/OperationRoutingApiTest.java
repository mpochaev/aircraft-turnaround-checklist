package ru.miit.uvp411.checklist.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import ru.miit.uvp411.checklist.IntegrationTest;
import ru.miit.uvp411.checklist.ops.internal.OpsAudit;

/** Проверки запуска операции, журнала и подключения обработчиков к API. */
class OperationRoutingApiTest extends IntegrationTest {

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    OpsAudit audit;

    @Test
    void startsOperationAfterCompletedPredecessorAndWritesJournal() throws Exception {
        Fixture fixture = operation("in_service", "not_started");
        LocalDateTime now = LocalDateTime.now().withNano(0);
        long predecessor = data.operation(fixture.turnaround(), "DEBOARD", "done",
                now.minusMinutes(20), now.minusMinutes(10), now.minusMinutes(10), null);
        jdbc.sql("insert into ops.exec_dependency (exec_id, prev_exec_id) values (:id, :prev)")
                .param("id", fixture.operation()).param("prev", predecessor).update();

        mvc.perform(patch("/api/v1/operations/{id}", fixture.operation())
                        .header("X-Dev-User", "tech07").header("X-Dev-Roles", "technician")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"in_progress\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation.status", is("in_progress")))
                .andExpect(jsonPath("$.operation.actualStart").isNotEmpty())
                .andExpect(jsonPath("$.operation.actualEnd").isEmpty())
                .andExpect(jsonPath("$.operation.executor.id", is(data.userId("tech07"))))
                .andExpect(jsonPath("$.operation.waitsFor").isEmpty())
                .andExpect(jsonPath("$.operation.allowedActions", contains("finish", "problem")))
                .andExpect(jsonPath("$.turnaround.id", is((int) fixture.turnaround())))
                .andExpect(jsonPath("$.turnaround.status", is("in_service")))
                .andExpect(jsonPath("$.turnaround.progress.done", is(1)))
                .andExpect(jsonPath("$.turnaround.progress.total", is(2)));

        Map<String, Object> entry = jdbc.sql("""
                        select login, turnaround_id, old_value ->> 'status' as before,
                               new_value ->> 'status' as after, old_value ->> 'actualStart' as old_start,
                               new_value ->> 'actualStart' as new_start, new_value ->> 'executorId' as executor
                        from log.action_log where entity = 'operation_exec' and entity_id = :id and action_type = 'op_start'
                        """).param("id", fixture.operation()).query().singleRow();
        assertThat(entry).containsEntry("login", "tech07").containsEntry("turnaround_id", fixture.turnaround())
                .containsEntry("before", "not_started").containsEntry("after", "in_progress").containsEntry("old_start", null)
                .containsEntry("executor", String.valueOf(data.userId("tech07")));
        assertThat(entry.get("new_start")).isNotNull();
    }

    @Test
    void checklistAndStartAgreeAboutBlockingPredecessors() throws Exception {
        Fixture fixture = operation("in_service", "not_started");
        LocalDateTime now = LocalDateTime.now().withNano(0);
        long predecessor = data.operation(fixture.turnaround(), "DEICE", "not_started", now, now.plusMinutes(10), null, null);
        jdbc.sql("insert into ops.exec_dependency (exec_id, prev_exec_id) values (:id, :prev)")
                .param("id", fixture.operation()).param("prev", predecessor).update();

        mvc.perform(get("/api/v1/turnarounds/{id}", fixture.turnaround())
                        .header("X-Dev-User", "tech07").header("X-Dev-Roles", "technician"))
                .andExpect(jsonPath("$.operations[0].waitsFor[0]", is((int) predecessor)))
                .andExpect(jsonPath("$.operations[0].allowedActions").isEmpty());
        mvc.perform(start(fixture.operation())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code", is("PREDECESSORS_NOT_DONE")))
                .andExpect(jsonPath("$.error.details[0].value", is((int) predecessor)));
        assertUnchanged(fixture);

        jdbc.sql("update ops.operation_exec set status = 'not_required' where id = :id").param("id", predecessor).update();
        mvc.perform(get("/api/v1/turnarounds/{id}", fixture.turnaround())
                        .header("X-Dev-User", "tech07").header("X-Dev-Roles", "technician"))
                .andExpect(jsonPath("$.operations[0].waitsFor").isEmpty())
                .andExpect(jsonPath("$.operations[0].allowedActions[0]", is("start")));
        mvc.perform(start(fixture.operation())).andExpect(status().isOk());
        mvc.perform(start(fixture.operation())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code", is("INVALID_TRANSITION")));
        assertThat(logCount(fixture.operation())).isEqualTo(1);
    }

    @Test
    void independentOperationsStartWhileAnotherStartTransactionIsOpen() {
        Fixture fixture = operation("in_service", "not_started");
        LocalDateTime now = LocalDateTime.now().withNano(0);
        long independent = data.operation(fixture.turnaround(), "CLEAN", "not_started", now, now.plusMinutes(10), null, null);

        try (var worker = Executors.newSingleThreadExecutor()) {
            transaction.executeWithoutResult(result -> {
                try {
                    mvc.perform(start(fixture.operation())).andExpect(status().isOk());
                    worker.submit(() -> mvc.perform(start(independent)).andExpect(status().isOk()))
                            .get(5, TimeUnit.SECONDS);
                    assertThat(operationStatus(independent)).isEqualTo("in_progress");
                } catch (Exception error) {
                    throw new AssertionError("Независимая операция должна начаться до завершения первой транзакции", error);
                }
            });
        }
        assertThat(operationStatus(fixture.operation())).isEqualTo("in_progress");
        assertThat(logCount(independent)).isEqualTo(1);
    }

    private static MockHttpServletRequestBuilder start(long id) {
        return patch("/api/v1/operations/{id}", id)
                .header("X-Dev-User", "tech07").header("X-Dev-Roles", "technician")
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"in_progress\"}");
    }

    @Test
    void missingOperationIs404() throws Exception {
        mvc.perform(patch("/api/v1/operations/{id}", Long.MAX_VALUE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"in_progress\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code", is("NOT_FOUND")))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void userWithoutOperationPermissionsIs403() throws Exception {
        Fixture fixture = operation("in_service", "not_started");
        mvc.perform(patch("/api/v1/operations/{id}", fixture.operation())
                        .header("X-Dev-User", "tech07").header("X-Dev-Roles", "observer")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"in_progress\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code", is("FORBIDDEN")));
        assertUnchanged(fixture);
    }

    @Test
    void anotherTechnicianCannotChangeOperation() throws Exception {
        Fixture fixture = operation("in_service", "not_started");
        mvc.perform(patch("/api/v1/operations/{id}", fixture.operation())
                        .header("X-Dev-User", "tech12").header("X-Dev-Roles", "technician")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"in_progress\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code", is("FORBIDDEN")));
        assertUnchanged(fixture);
    }

    @Test
    void technicianCannotResolveProblem() throws Exception {
        Fixture fixture = operation("in_service", "problem");
        mvc.perform(patch("/api/v1/operations/{id}", fixture.operation())
                        .header("X-Dev-User", "tech07").header("X-Dev-Roles", "technician")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"in_progress\",\"comment\":\"Решено\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code", is("FORBIDDEN")));
        assertThat(operationStatus(fixture.operation())).isEqualTo("problem");
        assertThat(logCount(fixture.operation())).isZero();
    }

    @Test
    void supervisorCanSkipOptionalOperationAndUnblockItsSuccessor() throws Exception {
        Fixture fixture = operation("in_service", "not_started");
        LocalDateTime now = LocalDateTime.now().withNano(0);
        long optional = data.operation(fixture.turnaround(), "DEICE", "not_started", now, now.plusMinutes(10), null, null);

        jdbc.sql("insert into ops.exec_dependency (exec_id, prev_exec_id) values (:id, :prev)")
                .param("id", fixture.operation()).param("prev", optional).update();
        mvc.perform(technicianPatch(optional, "{\"status\":\"not_required\",\"comment\":\"Летние условия\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/operations/{id}", optional).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"not_required\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code", is("COMMENT_REQUIRED")));
        mvc.perform(patch("/api/v1/operations/{id}", fixture.operation()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"not_required\",\"comment\":\"Пропуск\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code", is("MANDATORY_OPERATION")));
        assertUnchanged(fixture);
        assertThat(logCount(optional)).isZero();

        mvc.perform(patch("/api/v1/operations/{id}", optional).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"not_required\",\"comment\":\"Летние условия\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.operation.status", is("not_required")))
                .andExpect(jsonPath("$.operation.comment", is("Летние условия")));
        assertThat(logActions(optional)).containsExactly("op_not_required");
        mvc.perform(start(fixture.operation())).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"status\":\"problem\"}", "{\"status\":\"problem\",\"comment\":\"\"}",
            "{\"status\":\"problem\",\"comment\":\"   \"}"})
    void problemRequiresNonBlankComment(String body) throws Exception {
        Fixture fixture = operation("in_service", "in_progress");
        mvc.perform(technicianPatch(fixture.operation(), body))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code", is("COMMENT_REQUIRED")));
        assertThat(operationStatus(fixture.operation())).isEqualTo("in_progress");
        assertThat(logCount(fixture.operation())).isZero();
    }

    @Test
    void problemStoresCommentAndWritesAudit() throws Exception {
        Fixture fixture = operation("in_service", "in_progress");
        mvc.perform(technicianPatch(fixture.operation(), "{\"status\":\"problem\",\"comment\":\"Неисправность оборудования\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation.status", is("problem")))
                .andExpect(jsonPath("$.operation.comment", is("Неисправность оборудования")))
                .andExpect(jsonPath("$.operation.actualEnd").isEmpty());
        assertThat(logActions(fixture.operation())).containsExactly("op_problem");
    }

    @Test
    void finishSavesTimeExecutorAndProgressWithoutRequiringDelayCodeWhenOnTime() throws Exception {
        Fixture fixture = operation("in_service", "in_progress");
        mvc.perform(technicianPatch(fixture.operation(), "{\"status\":\"done\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation.status", is("done")))
                .andExpect(jsonPath("$.operation.actualEnd").isNotEmpty())
                .andExpect(jsonPath("$.operation.executor.id", is(data.userId("tech07"))))
                .andExpect(jsonPath("$.turnaround.progress.done", is(1)));
        assertThat(logActions(fixture.operation())).containsExactly("op_finish");
    }

    @Test
    void lateFinishRequiresAnActiveDelayCodeAndRecordsBothEvents() throws Exception {
        Fixture fixture = operation("in_service", "in_progress");
        jdbc.sql("""
                update ops.operation_exec
                set planned_start = localtimestamp - interval '30 minutes',
                    actual_start = localtimestamp - interval '30 minutes',
                    planned_end = localtimestamp - interval '11 minutes'
                where id = :id
                """).param("id", fixture.operation()).update();
        int codeId = jdbc.sql("select id from ref.delay_code where code = '36'").query(Integer.class).single();
        int inactiveCode = jdbc.sql("""
                insert into ref.delay_code (code, name, is_active) values ('99', 'Неактивный тестовый код', false) returning id
                """).query(Integer.class).single();

        mvc.perform(technicianPatch(fixture.operation(), "{\"status\":\"done\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code", is("DELAY_CODE_REQUIRED")));
        for (int invalid : List.of(Integer.MAX_VALUE, inactiveCode)) {
            mvc.perform(technicianPatch(fixture.operation(), "{\"status\":\"done\",\"delayCodeId\":" + invalid + "}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code", is("VALIDATION_ERROR")))
                    .andExpect(jsonPath("$.error.details[0].field", is("delayCodeId")));
        }
        assertThat(operationStatus(fixture.operation())).isEqualTo("in_progress");
        assertThat(logCount(fixture.operation())).isZero();

        mvc.perform(technicianPatch(fixture.operation(), "{\"status\":\"done\",\"delayCodeId\":" + codeId + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.operation.delayCode.id", is(codeId)));
        assertThat(logActions(fixture.operation())).containsExactlyInAnyOrder("op_finish", "delay_code");
    }

    @ParameterizedTest
    @ValueSource(strings = {"planned", "ready", "closed", "cancelled"})
    void onlyInServiceTurnaroundCanBeChanged(String turnaroundStatus) throws Exception {
        Fixture fixture = operation(turnaroundStatus, "not_started");
        mvc.perform(patch("/api/v1/operations/{id}", fixture.operation())
                        .header("X-Dev-User", "tech07").header("X-Dev-Roles", "technician")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"in_progress\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code", is("TURNAROUND_NOT_IN_SERVICE")));
        assertUnchanged(fixture);
    }

    @Test
    void invalidTransitionIs409() throws Exception {
        Fixture fixture = operation("in_service", "not_started");
        mvc.perform(patch("/api/v1/operations/{id}", fixture.operation())
                        .header("X-Dev-User", "tech07").header("X-Dev-Roles", "technician")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"done\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code", is("INVALID_TRANSITION")));
        assertUnchanged(fixture);
    }

    @Test
    void unregisteredActionDoesNotPretendToChangeData() throws Exception {
        Fixture fixture = operation("in_service", "problem");
        mvc.perform(patch("/api/v1/operations/{id}", fixture.operation())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"in_progress\",\"comment\":\"Решено\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code", is("INVALID_TRANSITION")));
        assertThat(operationStatus(fixture.operation())).isEqualTo("problem");
        assertThat(logCount(fixture.operation())).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"comment\":\"Только комментарий\"}", "{\"status\":\"unknown\"}",
            "{\"delayCodeId\":0}", "{\"status\":\"in_progress\",\"actualStart\":\"2026-11-11T10:00:00\"}", "{broken"})
    void invalidOrClientSuppliedTimeFieldsAre400(String body) throws Exception {
        Fixture fixture = operation("in_service", "not_started");
        mvc.perform(patch("/api/v1/operations/{id}", fixture.operation())
                        .header("X-Dev-User", "tech07").header("X-Dev-Roles", "technician")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code", is("VALIDATION_ERROR")))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
        assertUnchanged(fixture);
    }

    @Test
    void standaloneDelayCodePassesRequestValidationAndReachesDispatcher() throws Exception {
        Fixture fixture = operation("in_service", "not_started");
        int codeId = jdbc.sql("select id from ref.delay_code where code = '36'").query(Integer.class).single();
        mvc.perform(patch("/api/v1/operations/{id}", fixture.operation())
                        .header("X-Dev-User", "tech07").header("X-Dev-Roles", "technician")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"delayCodeId\":" + codeId + "}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code", is("INVALID_TRANSITION")));
        assertUnchanged(fixture);
    }

    @Test
    void operationChangeIsRolledBackWhenJournalInsertFails() throws Exception {
        Fixture fixture = operation("in_service", "not_started");
        jdbc.sql("""
                create function public.kirill_block_log() returns trigger language plpgsql as $$
                begin raise exception 'Тест: журнал недоступен'; end $$;
                """).update();
        jdbc.sql("create trigger kirill_block_log before insert on log.action_log for each row execute function public.kirill_block_log()")
                .update();
        try {
            mvc.perform(patch("/api/v1/operations/{id}", fixture.operation())
                            .header("X-Dev-User", "tech07").header("X-Dev-Roles", "technician")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"in_progress\"}"))
                    .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error.code", is("INTERNAL_ERROR")));
        } finally {
            jdbc.sql("drop trigger kirill_block_log on log.action_log").update();
            jdbc.sql("drop function public.kirill_block_log()").update();
        }
        assertUnchanged(fixture);
    }

    @Test
    void auditWrapperCannotStartAnIndependentTransaction() {
        assertThatThrownBy(() -> audit.operation("op_start", 1, 1, null, Map.of("status", "in_progress")))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void flightIndexHasDateAndDepartureTimeInTheRequiredOrder() {
        String definition = jdbc.sql("select indexdef from pg_indexes where schemaname = 'ops' and indexname = 'flight_date_std_idx'")
                .query(String.class).single();
        assertThat(definition).contains("(flight_date, std)");
    }

    private Fixture operation(String turnaroundStatus, String operationStatus) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        long turnaround = data.turnaround("RA-73725", turnaroundStatus, now.minusMinutes(30), now.plusMinutes(60),
                "planned".equals(turnaroundStatus) || "cancelled".equals(turnaroundStatus) ? null : now.minusMinutes(25),
                "ready".equals(turnaroundStatus) || "closed".equals(turnaroundStatus) ? now.minusMinutes(5) : null,
                "closed".equals(turnaroundStatus) ? now : null);
        long operation = data.operation(turnaround, "FUEL", operationStatus, now.minusMinutes(5), now.plusMinutes(15), null, null);
        return new Fixture(turnaround, operation);
    }

    private String operationStatus(long id) {
        return jdbc.sql("select status from ops.operation_exec where id = :id").param("id", id).query(String.class).single();
    }

    private long logCount(long id) {
        return jdbc.sql("select count(*) from log.action_log where entity = 'operation_exec' and entity_id = :id")
                .param("id", id).query(Long.class).single();
    }

    private List<String> logActions(long id) {
        return jdbc.sql("select action_type from log.action_log where entity = 'operation_exec' and entity_id = :id")
                .param("id", id).query(String.class).list();
    }

    private static MockHttpServletRequestBuilder technicianPatch(long id, String body) {
        return patch("/api/v1/operations/{id}", id)
                .header("X-Dev-User", "tech07").header("X-Dev-Roles", "technician")
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private void assertUnchanged(Fixture fixture) {
        Map<String, Object> row = jdbc.sql("select status, actual_start, executor_id from ops.operation_exec where id = :id")
                .param("id", fixture.operation()).query().singleRow();
        assertThat(row).containsEntry("status", "not_started").containsEntry("actual_start", null).containsEntry("executor_id", null);
        assertThat(logCount(fixture.operation())).isZero();
    }

    private record Fixture(long turnaround, long operation) {
    }

}
