package ru.miit.uvp411.checklist.log.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** События Keycloak в записи журнала (ФТ-40). */
class KeycloakEventImporterTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 13, 9, 0);

    @Test
    void mapsLoginFailedLockedAndLogout() {
        List<Map<String, Object>> events = List.of(   // Keycloak отдаёт события от новых к старым
                Map.of("time", ms(T0.plusMinutes(9)), "type", "LOGOUT", "userId", "u-7", "ipAddress", "10.0.0.5"),
                Map.of("time", ms(T0.plusMinutes(5)), "type", "LOGIN_ERROR", "error", "user_temporarily_disabled",
                        "details", Map.of("username", "tech12")),
                Map.of("time", ms(T0.plusMinutes(2)), "type", "LOGIN_ERROR", "error", "invalid_user_credentials",
                        "details", Map.of("username", "tech12")),
                Map.of("time", ms(T0.plusMinutes(1)), "type", "LOGIN", "details", Map.of("username", "tech07"),
                        "ipAddress", "10.0.0.5"),
                Map.of("time", ms(T0.minusMinutes(1)), "type", "LOGIN", "details", Map.of("username", "sup01")));

        List<KeycloakEventImporter.Record> records =
                KeycloakEventImporter.toRecords(events, T0, id -> id.equals("u-7") ? "tech07" : null);

        assertThat(records).extracting(KeycloakEventImporter.Record::actionType)
                .containsExactly("login", "login_failed", "account_locked", "logout");   // по времени, без старого события
        assertThat(records).extracting(KeycloakEventImporter.Record::login)
                .containsExactly("tech07", "tech12", "tech12", "tech07");
        assertThat(records.get(0).value()).containsEntry("result", "сессия открыта").containsEntry("source", "keycloak");
        assertThat(records.get(1).value()).containsEntry("error", "invalid_user_credentials");
        assertThat(records.get(3).ip()).isEqualTo("10.0.0.5");
    }

    private static long ms(LocalDateTime t) {
        return t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
