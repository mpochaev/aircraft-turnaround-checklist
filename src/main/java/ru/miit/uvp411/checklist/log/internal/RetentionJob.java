package ru.miit.uvp411.checklist.log.internal;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Плановое удаление данных старше 3 лет (ФТ-45, сценарий 56). Раз в месяц, 1-го числа в 03:30
 * по времени сервера, вызывает процедуру log.purge_expired() из миграции V202610281200.
 * Удаляются обороты с операциями и рейсами и записи журнала за месяцы старше 3 лет, справочники остаются.
 * Расписание можно поменять настройкой app.retention.cron.
 * <p>
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

    @Scheduled(cron = "${app.retention.cron:0 30 3 1 * *}")
    void purge() {
        Map<String, Object> r = jdbc.sql("select * from log.purge_expired()").query().singleRow();
        log.info("Удалены данные до {}: оборотов {}, операций {}, рейсов {}, записей журнала {}",
                r.get("cutoff"), r.get("turnarounds"), r.get("operations"), r.get("flights"), r.get("log_records"));
    }
}
