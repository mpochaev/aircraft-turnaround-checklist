package ru.miit.uvp411.checklist.log.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Значения журнала понятным текстом: названия вместо id. */
class LogTextsTest {

    private final LogTexts.Names names = new LogTexts.Names(Map.of(
            "airline", Map.of(1L, "SU"),
            "airport", Map.of(1L, "SVO", 2L, "KGD"),
            "user", Map.of(7L, "Иванов П.")));

    @Test
    void flightIsOneLine() {
        Map<String, Object> flight = new LinkedHashMap<>();
        flight.put("airlineId", 1);
        flight.put("flightNumber", "1001");
        flight.put("letter", "");
        flight.put("flightDate", "2026-11-13");
        flight.put("depAirportId", 2);
        flight.put("arrAirportId", 1);
        flight.put("std", "2026-11-13T08:00:00");
        flight.put("sta", "2026-11-13T10:00:00");
        assertThat(LogTexts.describe(flight, names)).isEqualTo("SU 1001 KGD → SVO, вылет 13.11 08:00, прилёт 13.11 10:00");
    }

    @Test
    void statusWithCommentAndIds() {
        assertThat(LogTexts.describe(Map.of("status", "problem", "comment", "Треснул шланг"), names))
                .isEqualTo("проблема: Треснул шланг");
        assertThat(LogTexts.describe(Map.of("technicianId", 7), names)).isEqualTo("Иванов П.");
        assertThat(LogTexts.describe(Map.of("technicianId", 99), names)).isEqualTo("№ 99");
        assertThat(LogTexts.describe(Map.of("status", "in_progress", "actualStart", "2026-11-13T10:02:00"), names))
                .isEqualTo("в работе");
    }

    @Test
    void loginEvents() {
        assertThat(LogTexts.describe(Map.of("result", "сессия открыта", "source", "keycloak"), names)).isEqualTo("сессия открыта");
        assertThat(LogTexts.describe(Map.of("error", "invalid_user_credentials", "source", "keycloak"), names))
                .isEqualTo("неверный пароль");
        assertThat(LogTexts.describe(null, names)).isEqualTo("—");
    }
}
