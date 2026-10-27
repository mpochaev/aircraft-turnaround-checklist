package ru.miit.uvp411.checklist.shared.jdbc;

import java.sql.Date;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.postgresql.util.PGobject;

import tools.jackson.databind.json.JsonMapper;

/**
 * Приводит строки JdbcClient к виду, который удобно отдавать в JSON:
 * timestamp в LocalDateTime (без часового пояса, как в спецификации), date в LocalDate,
 * jsonb в объект.
 * <p>
 * Имена полей задаются в SQL через кавычки: {@code select iata_code as "iataCode"}.
 */
public final class Rows {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Rows() {
    }

    public static List<Map<String, Object>> clean(List<Map<String, Object>> rows) {
        return rows.stream().map(Rows::clean).toList();
    }

    public static Map<String, Object> clean(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        row.forEach((k, v) -> out.put(k, value(v)));
        return out;
    }

    private static Object value(Object v) {
        if (v instanceof Timestamp t) {
            return t.toLocalDateTime();
        }
        if (v instanceof Date d) {
            return d.toLocalDate();
        }
        if (v instanceof PGobject pg && pg.getValue() != null && pg.getType() != null && pg.getType().startsWith("json")) {
            return JSON.readValue(pg.getValue(), Object.class);
        }
        return v;
    }
}
