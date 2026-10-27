package ru.miit.uvp411.checklist.shared.error;

import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * Ошибка предметной области или проверки данных. Бросается из сервисов, в ответ превращается
 * в {@link ApiError} обработчиком {@link GlobalExceptionHandler}.
 * <p>
 * Коды ошибок берутся из таблиц «Ошибки» спецификации API, например
 * {@code new ApiException(HttpStatus.CONFLICT, "PREDECESSORS_NOT_DONE", "Сначала завершите: Высадка пассажиров")}.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<ApiError.Detail> details;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, List.of());
    }

    public ApiException(HttpStatus status, String code, String message, List<ApiError.Detail> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " не найден");
    }

    public static ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Недостаточно прав");
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<ApiError.Detail> details() {
        return details;
    }
}
