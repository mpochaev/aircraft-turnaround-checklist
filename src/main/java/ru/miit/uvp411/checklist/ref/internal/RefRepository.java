package ru.miit.uvp411.checklist.ref.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import ru.miit.uvp411.checklist.shared.jdbc.Rows;

/**
 * SQL справочников. Имя справочника в адресе API сопоставлено с запросом, поэтому подставить
 * в SQL чужую таблицу через адрес нельзя.
 */
@Repository
class RefRepository {

    /** Справочник → (запрос, таблица для проверки активности). Поля в camelCase, как в спецификации API. */
    static final Map<String, Dictionary> DICTIONARIES = new LinkedHashMap<>();

    static {
        put("airports", "ref.airport", """
                select id, iata_code as "iataCode", name, city, is_active as "isActive" from ref.airport""");
        put("airlines", "ref.airline", """
                select id, iata_code as "iataCode", name, is_active as "isActive" from ref.airline""");
        put("stands", "ref.stand", """
                select s.id, s.airport_id as "airportId", a.iata_code as "airport", s.code, s.is_active as "isActive"
                from ref.stand s join ref.airport a on a.id = s.airport_id""");
        put("aircraft-types", "ref.aircraft_type", """
                select id, code, name, is_active as "isActive" from ref.aircraft_type""");
        put("aircraft", "ref.aircraft", """
                select ac.id, ac.tail_number as "tailNumber", t.code as "aircraftType", l.iata_code as "airline",
                       ac.is_active as "isActive"
                from ref.aircraft ac
                join ref.aircraft_type t on t.id = ac.aircraft_type_id
                join ref.airline l on l.id = ac.airline_id""");
        put("operations", "ref.operation", """
                select id, code, name, is_active as "isActive" from ref.operation""");
        put("operation-norms", "ref.operation_norm", """
                select n.id, t.code as "aircraftType", o.code as "operationCode", o.name as "operationName",
                       n.seq_no as "seqNo", n.duration_min as "durationMin", n.is_mandatory as "isMandatory",
                       (select string_agg(po.code, ',' order by pn.seq_no)
                          from ref.norm_dependency d
                          join ref.operation_norm pn on pn.id = d.prev_norm_id
                          join ref.operation po on po.id = pn.operation_id
                         where d.norm_id = n.id) as "after",
                       n.is_active as "isActive"
                from ref.operation_norm n
                join ref.aircraft_type t on t.id = n.aircraft_type_id
                join ref.operation o on o.id = n.operation_id""");
        put("delay-codes", "ref.delay_code", """
                select id, code, name, is_active as "isActive" from ref.delay_code""");
    }

    private static void put(String name, String table, String select) {
        DICTIONARIES.put(name, new Dictionary(table, select));
    }

    record Dictionary(String table, String select) {
    }

    private final JdbcClient jdbc;

    RefRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<Map<String, Object>> list(String dictionary, boolean includeInactive) {
        Dictionary d = DICTIONARIES.get(dictionary);
        String sql = "select * from (" + d.select() + ") q"
                + (includeInactive ? "" : " where q.\"isActive\"")
                + " order by " + orderColumns(d);
        return Rows.clean(jdbc.sql(sql).query().listOfRows());
    }

    boolean isActive(String dictionary, long id) {
        Dictionary d = DICTIONARIES.get(dictionary);
        if (d == null) {
            return false;
        }
        return jdbc.sql("select is_active from " + d.table() + " where id = :id")
                .param("id", id)
                .query(Boolean.class)
                .optional()
                .orElse(false);
    }

    private static String orderColumns(Dictionary d) {
        // во внешнем запросе доступны только псевдонимы, поэтому сортируем по id и по первому полю
        return switch (d.table()) {
            case "ref.operation_norm" -> "q.\"aircraftType\", q.\"seqNo\"";
            case "ref.stand" -> "q.\"airport\", q.code";
            default -> "q.id";
        };
    }
}
