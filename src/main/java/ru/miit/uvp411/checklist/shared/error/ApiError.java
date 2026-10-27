package ru.miit.uvp411.checklist.shared.error;

import java.util.List;

/**
 * Тело ответа с ошибкой, раздел 2.4 спецификации API:
 * <pre>{ "error": { "code": "...", "message": "...", "details": [ ... ] }, "requestId": "..." }</pre>
 */
public record ApiError(Body error, String requestId) {

    public record Body(String code, String message, List<Detail> details) {
    }

    /** Подробность ошибки: к какому полю относится и что с ним не так. */
    public record Detail(String field, Object value, String message) {
    }

    public static ApiError of(String code, String message, List<Detail> details, String requestId) {
        return new ApiError(new Body(code, message, details == null ? List.of() : details), requestId);
    }

    /** JSON без зависимостей: нужен там, где Spring MVC ещё не работает (фильтры безопасности). */
    public String toJson() {
        StringBuilder sb = new StringBuilder("{\"error\":{\"code\":").append(str(error.code()))
                .append(",\"message\":").append(str(error.message())).append(",\"details\":[");
        for (int i = 0; i < error.details().size(); i++) {
            Detail d = error.details().get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"field\":").append(str(d.field()))
                    .append(",\"value\":").append(d.value() == null ? "null" : str(String.valueOf(d.value())))
                    .append(",\"message\":").append(str(d.message())).append('}');
        }
        return sb.append("]},\"requestId\":").append(str(requestId)).append('}').toString();
    }

    private static String str(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
