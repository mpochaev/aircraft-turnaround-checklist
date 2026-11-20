package ru.miit.uvp411.checklist.ref.internal;

import java.util.*;
import org.springframework.http.HttpStatus;
import ru.miit.uvp411.checklist.shared.error.ApiException;

final class RefValidator {
    private static final int NUMBER = -1;
    private static final int BOOLEAN = -2;
    private static final int ID_LIST = -3;

    static void dictionary(String name) {
        if (!Set.of("airports", "airlines", "stands", "aircraft-types", "aircraft",
                "operations", "operation-norms", "delay-codes").contains(name)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "UNKNOWN_DICTIONARY", "Неизвестный справочник");
        }
    }

    static Map<String, Object> validate(String name, Map<String, Object> body, boolean create) {
        dictionary(name);
        if (body == null || (!create && body.isEmpty())) {
            throw ApiException.validation("body", body, "Передайте поля записи");
        }
        return validateFields(name, body, rules(name), create);
    }
    private static Map<String, Object> validateFields(
        String name,
        Map<String, Object> body,
        Map<String, Integer> rules,
        boolean isNew
    ) {
        for (String field : body.keySet()) {
            if (!rules.containsKey(field) && !field.equals("isActive")) {
                invalid(field, body.get(field),
                    "Неизвестное или неизменяемое поле");
            }
        }

        Map<String, Object> values = new LinkedHashMap<>();

        for (Map.Entry<String, Integer> rule : rules.entrySet()) {
            String field = rule.getKey();
            int fieldType = rule.getValue();

            if (!body.containsKey(field)) {
                if (isNew && !field.equals("prevNormIds")) {
                    invalid(field, null, "Обязательное поле");
                }

                continue;
            }

            Object value = body.get(field);

            if (fieldType > 0) {
                if (!(value instanceof String)) {
                    invalid(field, value, "Введите текст");
                }

                String text = ((String) value).strip();

                if (text.isEmpty() || text.length() > fieldType) {
                    invalid(field, value,
                        "Длина от 1 до " + fieldType + " символов");
                }

                if (field.equals("iataCode")) {
                    String pattern = name.equals("airports")
                        ? "[A-Z]{3}"
                        : "[A-Z0-9]{2}";

                    if (!text.matches(pattern)) {
                        invalid(field, value, "Неверный формат кода IATA");
                    }
                }

                if (name.equals("operations")
                    && field.equals("code")
                    && !text.matches("[A-Z_]+")) {
                    invalid(field, value, "Допустимы A–Z и _");
                }

                values.put(field, text);

            } else if (fieldType == NUMBER) {
                int max = Integer.MAX_VALUE;

                if (field.equals("durationMin")) {
                    max = 600;
                } else if (field.equals("seqNo")) {
                    max = 32767;
                }

                values.put(field, integer(field, value, max));

            } else if (fieldType == BOOLEAN) {
                if (!(value instanceof Boolean)) {
                    invalid(field, value, "Выберите да или нет");
                }

                values.put(field, value);

            } else if (fieldType == ID_LIST) {
                if (!(value instanceof List<?>)) {
                    invalid(field, value, "Ожидается массив нормативов");
                }

                List<Integer> ids = new ArrayList<>();

                for (Object item : (List<?>) value) {
                    ids.add(integer(field, item, Integer.MAX_VALUE));
                }

                if (new HashSet<>(ids).size() != ids.size()) {
                    invalid(field, value,
                        "Предшественники не должны повторяться");
                }

                values.put(field, ids);
            }
        }

        if (body.containsKey("isActive")) {
            Object active = body.get("isActive");

            if (!(active instanceof Boolean)) {
                invalid("isActive", active, "Ожидается true или false");
            }

            values.put("isActive", active);
        }

        return values;
    }

    private static Map<String, Integer> rules(String name) {
        Map<String, Integer> fields = new LinkedHashMap<>();

        switch (name) {
            case "airports" -> {
                fields.put("iataCode", 3);
                fields.put("name", 100);
                fields.put("city", 60);
            }
            case "airlines" -> {
                fields.put("iataCode", 2);
                fields.put("name", 100);
            }
            case "stands" -> {
                fields.put("airportId", NUMBER);
                fields.put("code", 10);
            }
            case "aircraft-types" -> {
                fields.put("code", 10);
                fields.put("name", 60);
            }
            case "aircraft" -> {
                fields.put("tailNumber", 10);
                fields.put("aircraftTypeId", NUMBER);
                fields.put("airlineId", NUMBER);
            }
            case "operations" -> {
                fields.put("code", 20);
                fields.put("name", 100);
            }
            case "operation-norms" -> {
                fields.put("aircraftTypeId", NUMBER);
                fields.put("operationId", NUMBER);
                fields.put("seqNo", NUMBER);
                fields.put("durationMin", NUMBER);
                fields.put("isMandatory", BOOLEAN);
                fields.put("prevNormIds", ID_LIST);

            }
            case "delay-codes" -> {
                fields.put("code", 4);
                fields.put("name", 150);
            }
            default -> throw new IllegalArgumentException(name);
        }

        return fields;
    }

    private static int integer(String field, Object value, int max) {
        if (!(value instanceof Number)) {
            throw ApiException.validation(
                field, value, "Ожидается целое число от 1 до " + max
            );
        }

        double number = ((Number) value).doubleValue();

        if (!Double.isFinite(number)
            || number != Math.rint(number)
            || number < 1
            || number > max) {
            throw ApiException.validation(
                field, value, "Ожидается целое число от 1 до " + max
            );
        }

        return ((Number) value).intValue();
    }

    private static void invalid(String field, Object value, String message) {
        throw ApiException.validation(field, value, message);
    }

}
