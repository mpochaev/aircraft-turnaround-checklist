package ru.miit.uvp411.checklist.ref.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import java.util.*;
import java.util.stream.Stream;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

import ru.miit.uvp411.checklist.shared.error.ApiException;

class RefValidationTest {

    static Map<String, Object> record(String dict) {
        Map<String, Object> body = new LinkedHashMap<>();

        switch (dict) {
            case "airports" -> {
                body.put("iataCode", "ABC");
                body.put("name", "A");
                body.put("city", "B");
            }
            case "airlines" -> {
                body.put("iataCode", "A1");
                body.put("name", "A");
            }
            case "stands" -> {
                body.put("airportId", 1);
                body.put("code", "A");
            }
            case "aircraft-types", "operations", "delay-codes" -> {
                body.put("code", "A");
                body.put("name", "B");
            }
            case "aircraft" -> {
                body.put("tailNumber", "RA-1");
                body.put("aircraftTypeId", 1);
                body.put("airlineId", 1);
            }
            case "operation-norms" -> {
                body.put("aircraftTypeId", 1);
                body.put("operationId", 1);
                body.put("seqNo", 1);
                body.put("durationMin", 1);
                body.put("isMandatory", false);
            }
        }

        return body;
    }

    // правильная запись принимается
    // если убрать обязательное поле, ошибка должна указать его имя
    @ParameterizedTest
    @ValueSource(strings = {
        "airports", "airlines", "stands", "aircraft-types",
        "aircraft", "operations", "operation-norms", "delay-codes"
    })
    void acceptsValidAndRejectsMissingFields(String dict) {
        Map<String, Object> body = record(dict);

        assertDoesNotThrow(() -> RefValidator.validate(dict, body, true));

        for (String field : body.keySet()) {
            Map<String, Object> missing = new LinkedHashMap<>(body);
            missing.remove(field);

            ApiException error = assertThrows(
                ApiException.class,
                () -> RefValidator.validate(dict, missing, true)
            );

            assertEquals(field, error.details().getFirst().field());
        }
    }

    // проверяет длину текста: минимум и максимум разрешены
    // пустота, пробелы, слишком длинный текст, число и null запрещены
    @ParameterizedTest
    @CsvSource({
        "airports,name,100",
        "airports,city,60",
        "airlines,name,100",
        "stands,code,10",
        "aircraft-types,code,10",
        "aircraft-types,name,60",
        "aircraft,tailNumber,10",
        "operations,code,20",
        "operations,name,100",
        "delay-codes,code,4",
        "delay-codes,name,150"
    })
    void textBoundaries(String dict, String field, int max) {
        Map<String, Object> body = record(dict);

        for (int length : new int[]{1, max}) {
            body.put(field, "A".repeat(length));
            assertDoesNotThrow(() -> RefValidator.validate(dict, body, true));
        }

        for (Object value : new Object[]{"", "   ", "A".repeat(max + 1), 42}) {
            body.put(field, value);
            assertThrows(
                ApiException.class,
                () -> RefValidator.validate(dict, body, true)
            );
        }

        body.put(field, null);
        assertThrows(
            ApiException.class,
            () -> RefValidator.validate(dict, body, true)
        );
    }

    // проверяет, что неправильные коды аэропорта отклоняются
    // допустимы только 3 заглавные латинские буквы
    @ParameterizedTest
    @ValueSource(strings = {"AB", "ABCD", "123", "abc", "АБВ"})
    void invalidAirportIata(String code) {
        Map<String, Object> body = record("airports");
        body.put("iataCode", code);

        assertThrows(
            ApiException.class,
            () -> RefValidator.validate("airports", body, true)
        );
    }

    // проверяет, что неправильные коды авиакомпании отклоняются
    // допустимы 2 символа: заглавные латинские буквы или цифры
    @ParameterizedTest
    @ValueSource(strings = {"A", "ABC", "a1", "!!"})
    void invalidAirlineIata(String code) {
        Map<String, Object> body = record("airlines");
        body.put("iataCode", code);

        assertThrows(
            ApiException.class,
            () -> RefValidator.validate("airlines", body, true)
        );
    }

    // проверяет границы чисел
    // ноль, отрицательные числа, дроби, превышение максимума
    // и значения неправильного типа должны отклоняться
    @ParameterizedTest
    @CsvSource({
        "durationMin,600",
        "seqNo,32767",
        "aircraftTypeId,2147483647",
        "operationId,2147483647"
    })
    void numberBoundaries(String field, int max) {
        for (Number value : new Number[]{1, max}) {
            assertDoesNotThrow(() -> RefValidator.validate(
                "operation-norms", Map.of(field, value), false
            ));
        }

        for (Object value : new Object[]{
            0, -1, 1.5, (long) max + 1,
            "1", Double.NaN, Double.POSITIVE_INFINITY
        }) {
            assertThrows(ApiException.class, () -> RefValidator.validate(
                "operation-norms", Map.of(field, value), false
            ));
        }
    }

    // проверяет тип isActive, неизвестные поля, пустой запрос,
    // неизвестный справочник и неправильный код операции
    @Test
    void booleansUnknownFieldsAndEmptyPatch() {
        assertDoesNotThrow(() -> RefValidator.validate(
            "airports", Map.of("isActive", false), false
        ));

        assertThrows(ApiException.class, () -> RefValidator.validate(
            "airports", Map.of("isActive", "false"), false
        ));

        assertThrows(ApiException.class, () -> RefValidator.validate(
            "airports", Map.of("id", 1), false
        ));

        assertThrows(ApiException.class, () -> RefValidator.validate(
            "airports", Map.of(), false
        ));

        assertThrows(ApiException.class, () -> RefValidator.validate(
            "airports", null, true
        ));

        assertEquals("UNKNOWN_DICTIONARY", assertThrows(
            ApiException.class,
            () -> RefValidator.validate("bad", Map.of(), true)
        ).code());

        assertThrows(ApiException.class, () -> RefValidator.validate(
            "operations", Map.of("code", "FUEL1"), false
        ));
    }

    // проверяет создание, изменение и деактивацию через сервис
    // вместо настоящей базы используется имитация репозитория
    @Test
    void serviceCreatesUpdatesAndDeactivates() {
        RefRepository repo = mock(RefRepository.class);
        RefService service = new RefService(repo, mock(ru.miit.uvp411.checklist.log.AuditLog.class));

        Map<String, Object> body = Map.of(
            "iataCode", "ABC",
            "name", "Airport",
            "city", "City"
        );

        Map<String, Object> created = Map.of("id", 1, "iataCode", "ABC");

        when(repo.save("airports", null, body)).thenReturn(1L);
        when(repo.one("airports", 1)).thenReturn(created);

        assertEquals(created, service.create("airports", body));
        verify(repo).save("airports", null, body);

        Map<String, Object> patch = Map.of("name", "New");
        Map<String, Object> updated = Map.of("id", 1, "name", "New");

        when(repo.save("airports", 1L, patch)).thenReturn(1L);
        when(repo.one("airports", 1)).thenReturn(updated);

        assertEquals(updated, service.update("airports", 1, patch));
        verify(repo).save("airports", 1L, patch);

        Map<String, Object> deactivated = Map.of("id", 1, "isActive", false);

        when(repo.save("airports", 1L, Map.of("isActive", false))).thenReturn(1L);
        when(repo.one("airports", 1)).thenReturn(deactivated);

        assertEquals(
            deactivated,
            service.update("airports", 1, Map.of("isActive", false))
        );

        verify(repo).save("airports", 1L, Map.of("isActive", false));
    }
}
