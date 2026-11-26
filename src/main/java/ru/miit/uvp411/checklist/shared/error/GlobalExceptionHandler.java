package ru.miit.uvp411.checklist.shared.error;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import ru.miit.uvp411.checklist.shared.web.RequestIdFilter;

/** Превращает исключения в ответ формата раздела 2.4 спецификации API. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> api(ApiException e) {
        return respond(e.status(), e.code(), e.getMessage(), e.details());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException e) {
        List<ApiError.Detail> details = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new ApiError.Detail(f.getField(), f.getRejectedValue(), f.getDefaultMessage()))
                .toList();
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Проверьте заполнение полей", details);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> typeMismatch(MethodArgumentTypeMismatchException e) {
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Неверный формат параметра",
                List.of(new ApiError.Detail(e.getName(), e.getValue(), "неверный формат")));
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(org.springframework.http.converter.HttpMessageNotReadableException e) {
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Проверьте формат JSON и типы полей",
                List.of(new ApiError.Detail("body", null, "Ожидается JSON-объект")));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> denied(AccessDeniedException e) {
        return respond(HttpStatus.FORBIDDEN, "FORBIDDEN", "Недостаточно прав", List.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> noResource(NoResourceFoundException e) {
        return respond(HttpStatus.NOT_FOUND, "NOT_FOUND", "Не найдено", List.of());
    }

    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<ApiError> database(org.springframework.dao.DataAccessException e) {
        if (e instanceof org.springframework.dao.IncorrectResultSizeDataAccessException result && result.getActualSize() == 0) {
            return respond(HttpStatus.NOT_FOUND, "NOT_FOUND", "Запись не найдена", List.of());
        }
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (!(cause instanceof java.sql.SQLException)) continue;
            java.sql.SQLException sql = (java.sql.SQLException) cause;
            String state = sql.getSQLState();
            if ("23505".equals(state)) {
                return respond(HttpStatus.CONFLICT, "DUPLICATE_CODE", "Код или сочетание ключей уже существует", List.of());
            }
            if ("23503".equals(state)) {
                return respond(HttpStatus.UNPROCESSABLE_ENTITY, "INACTIVE_REFERENCE", "Связанная запись отсутствует", List.of());
            }
            if ("23502".equals(state) || "23514".equals(state) || "22001".equals(state) || "22P02".equals(state)) {
                return respond(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Проверьте значения полей записи", List.of());
            }
            if ("P0001".equals(state) && sql.getMessage() != null && sql.getMessage().contains("IMMUTABLE_FIELD")) {
                return respond(HttpStatus.CONFLICT, "IMMUTABLE_FIELD", "Запись используется: ключевые поля менять нельзя", List.of());
            }
        }
        return other(e);
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> method(org.springframework.web.HttpRequestMethodNotSupportedException e) {
        return respond(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Метод не поддерживается", List.of());
    }

    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> parameter(org.springframework.web.bind.MissingServletRequestParameterException e) {
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Не указан параметр",
                List.of(new ApiError.Detail(e.getParameterName(), null, "Обязательный параметр")));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> other(Exception e) {
        log.error("Необработанная ошибка, requestId={}", RequestIdFilter.current(), e);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Ошибка сервера, повторите позже", List.of());
    }

    private ResponseEntity<ApiError> respond(HttpStatus status, String code, String message, List<ApiError.Detail> details) {
        log.warn("HTTP {} code={} requestId={} message={}", status.value(), code, RequestIdFilter.current(), message);
        return ResponseEntity.status(status).body(ApiError.of(code, message, details, RequestIdFilter.current()));
    }
}


