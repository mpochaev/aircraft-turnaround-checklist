package ru.miit.uvp411.checklist.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import ru.miit.uvp411.checklist.IntegrationTest;

/** Секционирование журнала по месяцам (миграция V202611051000). */
class PartitioningTest extends IntegrationTest {

    private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("'action_log_y'yyyy'm'MM");

    @Test
    void partitionsExistForCurrentAndNextMonths() {
        List<String> partitions = jdbc.sql("""
                        select c.relname from pg_inherits i join pg_class c on c.oid = i.inhrelid
                        where i.inhparent = 'log.action_log'::regclass
                        """).query(String.class).list();
        LocalDate month = LocalDate.now().withDayOfMonth(1);
        for (int i = 0; i <= 3; i++) {
            assertThat(partitions).contains(month.plusMonths(i).format(NAME));
        }
        assertThat(partitions).contains("action_log_default");
    }

    @Test
    void newRecordGoesToItsMonthAndDayQueryReadsOnlyIt() {
        data.logRecord(LocalDateTime.now(), "tech07", "login", "user", null, null);
        String partition = jdbc.sql("""
                        select tableoid::regclass::text from log.action_log
                        where id = (select max(id) from log.action_log)
                        """).query(String.class).single();
        String current = LocalDate.now().withDayOfMonth(1).format(NAME);
        assertThat(partition).isEqualTo("log." + current);

        LocalDate today = LocalDate.now();
        String plan = String.join("\n", jdbc.sql("explain (costs off) select count(*) from log.action_log "
                        + "where action_time >= '" + today + "' and action_time < '" + today.plusDays(1) + "'")
                .query(String.class).list());
        assertThat(plan).contains(current);
        assertThat(plan).doesNotContain(today.withDayOfMonth(1).plusMonths(1).format(NAME));
    }

    @Test
    void partitionIsProtectedToo() {
        data.logRecord(LocalDateTime.now(), "tech07", "login", "user", null, null);
        String current = "log." + LocalDate.now().withDayOfMonth(1).format(NAME);
        assertThatThrownBy(() -> jdbc.sql("update " + current + " set login = 'sup01'").update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("изменять нельзя");
        assertThatThrownBy(() -> jdbc.sql("truncate " + current).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("очищать нельзя");
    }
}
