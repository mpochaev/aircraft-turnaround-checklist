package ru.miit.uvp411.checklist.log.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Значения «Было» и «Стало» журнала понятным текстом. В базе в old_value и new_value лежат id
 * (airlineId: 1, depAirportId: 3), а на экране нужны названия (SU, KGD). Названия берутся одним
 * запросом на каждый справочник для всей страницы журнала.
 */
@Component
public class LogTexts {

    /** Поле с id → справочник, из которого берётся название. */
    private static final Map<String, String> ID_FIELDS = Map.ofEntries(
            Map.entry("airlineId", "airline"), Map.entry("depAirportId", "airport"),
            Map.entry("arrAirportId", "airport"), Map.entry("airportId", "airport"),
            Map.entry("aircraftId", "aircraft"), Map.entry("aircraftTypeId", "aircraft_type"),
            Map.entry("standId", "stand"), Map.entry("technicianId", "user"),
            Map.entry("executorId", "user"), Map.entry("userId", "user"),
            Map.entry("delayCodeId", "delay_code"), Map.entry("operationId", "operation"),
            Map.entry("arrFlightId", "flight"), Map.entry("depFlightId", "flight"),
            Map.entry("roleIds", "role"));

    private static final Map<String, String> NAME_SQL = Map.of(
            "airline", "select id, iata_code as name from ref.airline where id in (:ids)",
            "airport", "select id, iata_code as name from ref.airport where id in (:ids)",
            "aircraft", "select id, tail_number as name from ref.aircraft where id in (:ids)",
            "aircraft_type", "select id, code as name from ref.aircraft_type where id in (:ids)",
            "stand", "select id, code as name from ref.stand where id in (:ids)",
            "user", "select id, last_name || ' ' || left(first_name, 1) || '.' as name from auth.app_user where id in (:ids)",
            "delay_code", "select id, code as name from ref.delay_code where id in (:ids)",
            "operation", "select id, name from ref.operation where id in (:ids)",
            "role", "select id, name from auth.role where id in (:ids)",
            "flight", "select f.id, a.iata_code || ' ' || f.flight_number || f.letter as name from ops.flight f join ref.airline a on a.id = f.airline_id where f.id in (:ids)");

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("flightNumber", "рейс"), Map.entry("letter", "литера"), Map.entry("flightDate", "дата"),
            Map.entry("std", "вылет"), Map.entry("sta", "прилёт"), Map.entry("airlineId", "авиакомпания"),
            Map.entry("depAirportId", "из"), Map.entry("arrAirportId", "в"), Map.entry("airportId", "аэропорт"),
            Map.entry("aircraftId", "борт"), Map.entry("aircraftTypeId", "тип ВС"), Map.entry("standId", "стоянка"),
            Map.entry("technicianId", "техник"), Map.entry("executorId", "исполнитель"), Map.entry("userId", "пользователь"),
            Map.entry("delayCodeId", "код задержки"), Map.entry("delayCode", "код задержки"),
            Map.entry("operationId", "операция"), Map.entry("arrFlightId", "рейс прилёта"), Map.entry("depFlightId", "рейс вылета"),
            Map.entry("login", "логин"), Map.entry("lastName", "фамилия"), Map.entry("firstName", "имя"),
            Map.entry("middleName", "отчество"), Map.entry("roleIds", "роли"), Map.entry("roleCodes", "роли"),
            Map.entry("isActive", "активна"), Map.entry("unlock", "разблокировка"), Map.entry("resetPassword", "сброс пароля"),
            Map.entry("name", "название"), Map.entry("code", "код"), Map.entry("permissionCodes", "права"),
            Map.entry("kind", "вид"), Map.entry("iataCode", "код"), Map.entry("tailNumber", "борт"),
            Map.entry("city", "город"), Map.entry("seqNo", "порядок"), Map.entry("durationMin", "длительность, мин"),
            Map.entry("isMandatory", "обязательная"), Map.entry("aircraftType", "тип ВС"), Map.entry("airline", "авиакомпания"),
            Map.entry("operationCode", "операция"), Map.entry("after", "после"),
            Map.entry("aircraft", "борт"), Map.entry("stand", "стоянка"),
            Map.entry("technician", "техник"), Map.entry("flight", "рейс"));

    private static final Map<String, String> STATUS = Map.ofEntries(
            Map.entry("not_started", "не начата"), Map.entry("in_progress", "в работе"), Map.entry("problem", "проблема"),
            Map.entry("done", "выполнена"), Map.entry("not_required", "не требуется"), Map.entry("planned", "запланирован"),
            Map.entry("in_service", "обслуживание"), Map.entry("ready", "готов к вылету"), Map.entry("closed", "закрыт"),
            Map.entry("cancelled", "отменён"), Map.entry("pending", "на рассмотрении"), Map.entry("approved", "подтверждена"),
            Map.entry("rejected", "отклонена"));

    /** Ошибки входа из событий Keycloak. */
    private static final Map<String, String> ERRORS = Map.of(
            "invalid_user_credentials", "неверный пароль",
            "user_not_found", "нет такого пользователя",
            "user_temporarily_disabled", "учётная запись заблокирована на 15 минут",
            "user_disabled", "учётная запись отключена");

    private static final Map<String, String> KINDS = Map.of("personal_data", "изменение ФИО", "password_reset", "сброс пароля");

    private static final List<String> TEXT_FIELDS = List.of("comment", "cancelReason", "reason");
    /** Время отметки совпадает со временем записи журнала, служебные поля на экран не выводим. */
    private static final Set<String> HIDDEN = Set.of("actualStart", "actualEnd", "onBlockAt", "readyAt", "offBlockAt", "source", "session", "id");

    private final JdbcClient jdbc;

    LogTexts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Названия для всех id, которые встречаются в значениях записей страницы журнала. */
    public Names names(List<?> values) {
        Map<String, Set<Long>> ids = new HashMap<>();
        for (Object v : values) {
            if (v instanceof Map<?, ?> m) {
                m.forEach((k, val) -> {
                    String kind = ID_FIELDS.get(String.valueOf(k));
                    if (kind != null && val instanceof Number n) {
                        ids.computeIfAbsent(kind, x -> new LinkedHashSet<>()).add(n.longValue());
                    }
                    if (kind != null && val instanceof java.util.Collection<?> list) {
                        list.stream().filter(Number.class::isInstance).map(x -> ((Number) x).longValue())
                                .forEach(id -> ids.computeIfAbsent(kind, x -> new LinkedHashSet<>()).add(id));
                    }
                });
            }
        }
        Map<String, Map<Long, String>> names = new HashMap<>();
        ids.forEach((kind, set) -> {
            Map<Long, String> byId = new HashMap<>();
            jdbc.sql(NAME_SQL.get(kind)).param("ids", new ArrayList<>(set)).query()
                    .listOfRows().forEach(r -> byId.put(((Number) r.get("id")).longValue(), String.valueOf(r.get("name"))));
            names.put(kind, byId);
        });
        return new Names(names);
    }

    /** Названия справочников по виду и id. */
    public record Names(Map<String, Map<Long, String>> byKind) {

        public static final Names NONE = new Names(Map.of());

        String of(String field, Object value) {
            String kind = ID_FIELDS.get(field);
            if (kind != null && value instanceof Number n) {
                String name = byKind.getOrDefault(kind, Map.of()).get(n.longValue());
                return name != null ? name : "№ " + n;
            }
            return null;
        }
    }

    /** Для изменения записи оставляет в «Было» и «Стало» только поля, которые поменялись. */
    public static Object[] changedOnly(Object oldValue, Object newValue) {
        if (!(oldValue instanceof Map<?, ?> o) || !(newValue instanceof Map<?, ?> n)) {
            return new Object[] {oldValue, newValue};
        }
        Map<Object, Object> before = new LinkedHashMap<>();
        Map<Object, Object> after = new LinkedHashMap<>();
        n.forEach((k, v) -> {
            if (!java.util.Objects.equals(o.get(k), v)) {
                after.put(k, v);
                if (o.containsKey(k)) {
                    before.put(k, o.get(k));
                }
            }
        });
        return after.isEmpty() ? new Object[] {oldValue, newValue} : new Object[] {before, after};
    }

    /** {"status": "problem", "comment": "Треснул шланг"} → «проблема: Треснул шланг». */
    public static String describe(Object value, Names names) {
        if (value == null) {
            return "—";
        }
        if (!(value instanceof Map<?, ?> raw)) {
            return String.valueOf(value);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        raw.forEach((k, v) -> m.put(String.valueOf(k), v));
        if (m.containsKey("flightNumber") && m.containsKey("airlineId")) {
            return flight(m, names);
        }
        List<String> keys = m.keySet().stream().filter(k -> !HIDDEN.contains(k) && m.get(k) != null).toList();
        if (keys.size() == 1 && !keys.get(0).equals("status") && !TEXT_FIELDS.contains(keys.get(0))) {
            return show(keys.get(0), m.get(keys.get(0)), names);
        }
        List<String> parts = new ArrayList<>();
        if (m.get("status") != null) {
            parts.add(STATUS.getOrDefault(String.valueOf(m.get("status")), String.valueOf(m.get("status"))));
        }
        for (String k : keys) {
            if (!k.equals("status") && !TEXT_FIELDS.contains(k)) {
                parts.add(LABELS.getOrDefault(k, k) + " " + show(k, m.get(k), names));
            }
        }
        String text = String.join("; ", TEXT_FIELDS.stream().filter(f -> m.get(f) != null).map(f -> String.valueOf(m.get(f))).toList());
        String s = String.join(", ", parts);
        if (!text.isEmpty()) {
            s = s.isEmpty() ? text : s + ": " + text;
        }
        return s.isEmpty() ? "—" : s;
    }

    /** Рейс одной строкой: «SU 1001 KGD → SVO, вылет 05.11 08:00, прилёт 05.11 10:00». */
    private static String flight(Map<String, Object> m, Names names) {
        String letter = m.get("letter") == null ? "" : String.valueOf(m.get("letter"));
        StringBuilder s = new StringBuilder()
                .append(show("airlineId", m.get("airlineId"), names)).append(' ')
                .append(m.get("flightNumber")).append(letter).append(' ')
                .append(show("depAirportId", m.get("depAirportId"), names)).append(" → ")
                .append(show("arrAirportId", m.get("arrAirportId"), names));
        if (m.get("std") != null) {
            s.append(", вылет ").append(show("std", m.get("std"), names));
        }
        if (m.get("sta") != null) {
            s.append(", прилёт ").append(show("sta", m.get("sta"), names));
        }
        return s.toString();
    }

    private static String show(String field, Object value, Names names) {
        if (value instanceof java.util.Collection<?> list) {
            return list.isEmpty() ? "—" : String.join(", ", list.stream().map(v -> show(field, v, names)).toList());
        }
        if (value instanceof Boolean b) {
            return b ? "да" : "нет";
        }
        if (field.equals("kind")) {
            return KINDS.getOrDefault(String.valueOf(value), String.valueOf(value));
        }
        String name = names.of(field, value);
        if (name != null) {
            return name;
        }
        String s = String.valueOf(value);
        if (field.equals("error")) {
            return ERRORS.getOrDefault(s, s);
        }
        if (field.equals("status")) {
            return STATUS.getOrDefault(s, s);
        }
        if (s.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}.*")) {   // 2026-11-05T10:00:00 → 05.11 10:00
            return s.substring(8, 10) + "." + s.substring(5, 7) + " " + s.substring(11, 16);
        }
        if (s.matches("\\d{4}-\\d{2}-\\d{2}")) {                    // 2026-11-05 → 05.11.2026
            return s.substring(8, 10) + "." + s.substring(5, 7) + "." + s.substring(0, 4);
        }
        return s;
    }
}
