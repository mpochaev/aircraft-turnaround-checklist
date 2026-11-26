package ru.miit.uvp411.checklist;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Цифровой чек-лист подготовки ВС к вылету.
 * <p>
 * Монолит из четырёх модулей, каждый модуль ведёт своя команда:
 * {@code auth} (Авторизация), {@code ref} (Справочники), {@code ops} (Бизнес-логика), {@code log} (Данные).
 * Общий код лежит в {@code shared}. Границы модулей проверяет тест {@code ModularityTests}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ChecklistApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChecklistApplication.class, args);
    }
}
