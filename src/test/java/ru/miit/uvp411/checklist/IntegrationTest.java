package ru.miit.uvp411.checklist;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Основа интеграционных тестов: приложение целиком и настоящий PostgreSQL 16 в Docker.
 * Схему создаёт Flyway теми же миграциями, что и на стенде. Нужен запущенный Docker.
 * <p>
 * Профиль dev: запрос идёт от sup01 (супервайзер), другого пользователя задают заголовки
 * X-Dev-User и X-Dev-Roles, как в Postman. База одна на все тесты, поэтому тест проверяет только
 * свои данные: свой оборот, свой период или свой вид действия.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
public abstract class IntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcClient jdbc;

    protected TestData data;

    @BeforeEach
    void testData() {
        data = new TestData(jdbc);
        data.users();
    }
}
