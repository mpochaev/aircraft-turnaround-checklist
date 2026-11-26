package ru.miit.uvp411.checklist.ref.web;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

import java.sql.SQLException;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ru.miit.uvp411.checklist.ref.internal.RefService;
import ru.miit.uvp411.checklist.shared.error.*;
import ru.miit.uvp411.checklist.shared.web.RequestIdFilter;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

class RefExceptionHandlerTest {

    RefService service;
    MockMvc mvc;

    // перед каждым тестом создаёт контроллер с имитацией сервиса
    // подключает обработчик ошибок и присвоение requestId
    @BeforeEach
    void setup() {
        service = mock(RefService.class);

        mvc = MockMvcBuilders.standaloneSetup(new RefController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new RequestIdFilter())
            .build();
    }

    // имитирует ошибки базы при создании и изменении записи
    // проверяет HTTP-код, код ошибки и requestId
    // внутренний текст SQL не должен попадать в ответ пользователю
    @ParameterizedTest
    @CsvSource({
        "23505,409,DUPLICATE_CODE",
        "23503,422,INACTIVE_REFERENCE",
        "23502,400,VALIDATION_ERROR",
        "23514,400,VALIDATION_ERROR",
        "22001,400,VALIDATION_ERROR",
        "22P02,400,VALIDATION_ERROR",
        "P0001,409,IMMUTABLE_FIELD",
        "08006,500,INTERNAL_ERROR"
    })
    void databaseErrorsOnPostAndPatch(
        String state, int status, String code
    ) throws Exception {
        DataAccessException error = new DataAccessResourceFailureException(
            "DB error",
            new SQLException("IMMUTABLE_FIELD private SQL", state)
        );

        when(service.create(anyString(), anyMap())).thenThrow(error);
        when(service.update(anyString(), anyLong(), anyMap())).thenThrow(error);

        for (String method : new String[]{"post", "patch"}) {
            MockHttpServletRequestBuilder request;

            if (method.equals("post")) {
                request = post("/api/v1/ref/airports");
            } else {
                request = patch("/api/v1/ref/airports/1");
            }

            mvc.perform(request
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Test\"}"))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.error.code").value(code))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(content().string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("private SQL")
                    )
                ));
        }
    }

    // проверяет ответ 404 для неизвестного справочника и отсутствующей записи
    // также проверяет, что код ошибки и requestId записаны в лог
    @Test
    void getErrorsAndLoggingIncludeRequestId() throws Exception {
        when(service.list("unknown", "true", "", null, null, 1, 50, "", "asc")).thenThrow(new ApiException(
            HttpStatus.NOT_FOUND,
            "UNKNOWN_DICTIONARY",
            "Неизвестный справочник"
        ));

        Logger logger = (Logger) LoggerFactory.getLogger(
            GlobalExceptionHandler.class
        );

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            MvcResult result = mvc.perform(get("/api/v1/ref/unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("UNKNOWN_DICTIONARY"))
                .andReturn();

            String id = result.getResponse().getHeader("X-Request-Id");
            assertNotNull(id);

            boolean found = false;

            for (ILoggingEvent event : appender.list) {
                String message = event.getFormattedMessage();

                if (message.contains(id)
                    && message.contains("UNKNOWN_DICTIONARY")) {
                    found = true;
                }
            }

            assertTrue(found);

        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        when(service.list("airports", "true", "", null, null, 1, 50, "", "asc"))
            .thenThrow(new EmptyResultDataAccessException(1));

        mvc.perform(get("/api/v1/ref/airports"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // проверяет неправильный JSON, текст вместо числового ID
    // и неподдерживаемый HTTP-метод
    @Test
    void malformedJsonIdAndMethod() throws Exception {
        mvc.perform(post("/api/v1/ref/airports")
                .contentType(MediaType.APPLICATION_JSON)
                .content("[]"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        mvc.perform(patch("/api/v1/ref/airports/abc")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());

        mvc.perform(delete("/api/v1/ref/airports"))
            .andExpect(status().isMethodNotAllowed());
    }

    // проверяет, что в ответе указано имя ошибочного поля
    // по этому имени интерфейс показывает сообщение возле поля
    @Test
    void validationFieldIsReturned() throws Exception {
        when(service.create(anyString(), anyMap())).thenThrow(
            ApiException.validation("iataCode", "12", "Нужно 3 буквы")
        );

        mvc.perform(post("/api/v1/ref/airports")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.details[0].field").value("iataCode"));
    }
}
