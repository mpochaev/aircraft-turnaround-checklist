package ru.miit.uvp411.checklist.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import ru.miit.uvp411.checklist.IntegrationTest;

/** Защита журнала (сценарий 49, ФТ-41) и откат изменения без записи в журнал (сценарий 50, ФТ-2). */
class LogProtectionTest extends IntegrationTest {

    @Autowired
    AuditLog auditLog;

    @Autowired
    TransactionTemplate tx;

    @Test
    void databaseRejectsUpdateDeleteAndTruncate() {
        data.logRecord(LocalDateTime.now().minusMinutes(1), "tech07", "login", "user", null, null);
        long id = jdbc.sql("select max(id) from log.action_log").query(Long.class).single();

        assertThatThrownBy(() -> jdbc.sql("update log.action_log set login = 'sup01' where id = :id").param("id", id).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("изменять нельзя");
        assertThatThrownBy(() -> jdbc.sql("delete from log.action_log where id = :id").param("id", id).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("моложе 3 лет");
        assertThatThrownBy(() -> jdbc.sql("truncate log.action_log").update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("очищать нельзя");
        assertThat(jdbc.sql("select login from log.action_log where id = :id").param("id", id).query(String.class).single())
                .isEqualTo("tech07");
    }

    @Test
    void changeIsRolledBackWhenJournalFails() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        long t = data.turnaround("RA-73725", "in_service", now.minusMinutes(30), now.plusMinutes(40), now.minusMinutes(25), null, null);
        long op = data.operation(t, "CLEAN", "in_progress", now.minusMinutes(10), now.plusMinutes(5), null, null);

        // как на стенде в сценарии 50: запись в журнал запрещена триггером
        jdbc.sql("""
                create or replace function public.scenario50_no_log() returns trigger language plpgsql as $$
                begin raise exception 'Запись в журнал запрещена для сценария 50'; end $$
                """).update();
        jdbc.sql("create trigger scenario50 before insert on log.action_log for each row execute function public.scenario50_no_log()").update();
        try {
            assertThatThrownBy(() -> finish(op, t)).isInstanceOf(DataAccessException.class);
        } finally {
            jdbc.sql("drop trigger scenario50 on log.action_log").update();
            jdbc.sql("drop function public.scenario50_no_log()").update();
        }
        assertThat(status(op)).isEqualTo("in_progress");

        finish(op, t);
        assertThat(status(op)).isEqualTo("done");
    }

    @Test
    void recordNeedsTransactionAndKnownAction() {
        asUser("tech07", () -> {
            AuditLog.Entry entry = new AuditLog.Entry("op_start", "operation_exec", 1L, null, null, null);
            assertThatThrownBy(() -> auditLog.record(entry)).isInstanceOf(IllegalTransactionStateException.class);
            AuditLog.Entry unknown = new AuditLog.Entry("op_delete", "operation_exec", 1L, null, null, null);
            assertThatThrownBy(() -> tx.executeWithoutResult(s -> auditLog.record(unknown)))
                    .isInstanceOf(IllegalArgumentException.class);
        });
    }

    /** Так завершение операции будет устроено в сервисе «Бизнес-логики»: изменение и журнал в одной транзакции. */
    private void finish(long op, long turnaround) {
        asUser("tech07", () -> tx.executeWithoutResult(s -> {
            jdbc.sql("update ops.operation_exec set status = 'done', actual_end = localtimestamp where id = :id").param("id", op).update();
            auditLog.record(new AuditLog.Entry("op_finish", "operation_exec", op, turnaround,
                    Map.of("status", "in_progress"), Map.of("status", "done")));
        }));
    }

    private String status(long op) {
        return jdbc.sql("select status from ops.operation_exec where id = :id").param("id", op).query(String.class).single();
    }

    private static void asUser(String login, Runnable action) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login, null, List.of()));
        try {
            action.run();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
