package ru.miit.uvp411.checklist.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ru.miit.uvp411.checklist.IntegrationTest;

/** Хранение 3 года: сценарий 56 ПМИ, ФТ-45. Старые месяцы журнала удаляются целыми секциями. */
class RetentionTest extends IntegrationTest {

    @Test
    void deletesWholeMonthsOlderThanThreeYears() {
        LocalDateTime old = LocalDate.now().minusYears(4).withDayOfMonth(10).atTime(10, 0);
        LocalDateTime recent = LocalDate.now().minusMonths(1).withDayOfMonth(10).atTime(10, 0);
        // секция журнала за старый месяц: процедура должна удалить её целиком
        String oldPartition = jdbc.sql("select log.create_month_partition(:m)").param("m", old.toLocalDate())
                .query(String.class).single();

        long oldTurnaround = data.closedTurnaround("RA-73725", old, old.plusMinutes(60));
        long oldOp = data.operation(oldTurnaround, "FUEL", "done", old.plusMinutes(15), old.plusMinutes(35), old.plusMinutes(36), null);
        data.logRecord(old.plusMinutes(1), "tech07", "on_block", "turnaround", oldTurnaround, oldTurnaround);
        data.logRecord(old.plusMinutes(36), "tech07", "op_finish", "operation_exec", oldOp, oldTurnaround);

        // старый оборот, на который ссылается свежая запись журнала: остаётся до следующего запуска
        long linked = data.closedTurnaround("RA-73711", old, old.plusMinutes(60));
        data.logRecord(LocalDateTime.now(), "sup01", "op_revert", "turnaround", linked, linked);

        long recentTurnaround = data.closedTurnaround("RA-89041", recent, recent.plusMinutes(120));
        data.operation(recentTurnaround, "FUEL", "done", recent.plusMinutes(20), recent.plusMinutes(55), recent.plusMinutes(50), null);
        data.logRecord(recent.plusMinutes(1), "tech07", "on_block", "turnaround", recentTurnaround, recentTurnaround);

        long refBefore = refRows();
        Map<String, Object> result = jdbc.sql("select * from log.purge_expired()").query().singleRow();

        assertThat(((Number) result.get("turnarounds")).longValue()).isGreaterThanOrEqualTo(1);
        assertThat(((Number) result.get("log_records")).longValue()).isGreaterThanOrEqualTo(2);
        assertThat(exists("ops.turnaround", oldTurnaround)).isFalse();
        assertThat(jdbc.sql("select to_regclass('log.' || :p) is null").param("p", oldPartition)
                .query(Boolean.class).single()).isTrue();
        assertThat(exists("ops.operation_exec", oldOp)).isFalse();
        assertThat(count("select count(*) from log.action_log where turnaround_id = " + oldTurnaround)).isZero();
        assertThat(count("select count(*) from ops.flight where std < '" + old.plusDays(1) + "' and std > '" + old.minusDays(1) + "'"))
                .isEqualTo(2);   // рейсы оборота linked, остальные старые удалены

        assertThat(exists("ops.turnaround", linked)).isTrue();
        assertThat(exists("ops.turnaround", recentTurnaround)).isTrue();
        assertThat(count("select count(*) from log.action_log where turnaround_id = " + recentTurnaround)).isEqualTo(1);
        assertThat(refRows()).isEqualTo(refBefore);
    }

    private boolean exists(String table, long id) {
        return count("select count(*) from " + table + " where id = " + id) > 0;
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private long refRows() {
        return count("""
                select (select count(*) from ref.airport) + (select count(*) from ref.airline) + (select count(*) from ref.stand)
                     + (select count(*) from ref.aircraft) + (select count(*) from ref.operation_norm)
                     + (select count(*) from ref.delay_code)
                """);
    }
}
