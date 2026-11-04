package ru.miit.uvp411.checklist.log.internal;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Обслуживание журнала и хранение данных.
 * <ul>
 *   <li>Секции журнала по месяцам (миграция V202611051000): при запуске приложения и раз в месяц
 *   создаются секции на текущий месяц и 3 месяца вперёд, функция log.ensure_partitions().</li>
 *   <li>Удаление данных старше 3 лет (ФТ-45, сценарий 56): раз в месяц, 1-го числа в 03:30 по времени сервера,
 *   процедура log.purge_expired(). Месяцы журнала удаляются целыми секциями, обороты с операциями
 *   и рейсами удаляются по дате, справочники остаются.</li>
 * </ul>
 * Расписание можно поменять настройкой app.retention.cron.
 * Аннотация @EnableScheduling стоит здесь, потому что это первая задача по расписанию в приложении.
 */
@Configuration
@EnableScheduling
class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final JdbcClient jdbc;

    RetentionJob(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    void partitionsOnStart() {
        ensurePartitions();
    }

    @Scheduled(cron = "${app.retention.cron:0 30 3 1 * *}")
    void monthly() {
        ensurePartitions();
        Map<String, Object> r = jdbc.sql("select * from log.purge_expired()").query().singleRow();
        log.info("Удалены данные до {}: оборотов {}, операций {}, рейсов {}, записей журнала {}",
                r.get("cutoff"), r.get("turnarounds"), r.get("operations"), r.get("flights"), r.get("log_records"));
    }

    private void ensurePartitions() {
        jdbc.sql("select log.ensure_partitions(3)").query().singleRow();
    }
}
