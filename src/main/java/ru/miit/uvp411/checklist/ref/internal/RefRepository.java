package ru.miit.uvp411.checklist.ref.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.StringJoiner;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import ru.miit.uvp411.checklist.shared.jdbc.Rows;
import ru.miit.uvp411.checklist.shared.web.Paging;
import ru.miit.uvp411.checklist.shared.error.ApiException;

/**
 * SQL справочников. Имя справочника в адресе API сопоставлено с запросом, поэтому подставить
 * в SQL чужую таблицу через адрес нельзя.
 */
@Repository
class RefRepository {

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
                select ac.id, ac.tail_number as "tailNumber", ac.aircraft_type_id as "aircraftTypeId",
                       ac.airline_id as "airlineId", t.code as "aircraftType", l.iata_code as "airline",
                       ac.is_active as "isActive"
                from ref.aircraft ac
                join ref.aircraft_type t on t.id = ac.aircraft_type_id
                join ref.airline l on l.id = ac.airline_id""");
        put("operations", "ref.operation", """
                select id, code, name, is_active as "isActive" from ref.operation""");
        put("operation-norms", "ref.operation_norm", """
                select n.id, n.aircraft_type_id as "aircraftTypeId", n.operation_id as "operationId",
                       t.code as "aircraftType", o.code as "operationCode", o.name as "operationName",
                       jsonb_build_object('id', o.id, 'code', o.code, 'name', o.name) as operation,
                       coalesce((select jsonb_agg(d.prev_norm_id order by d.prev_norm_id)
                                 from ref.norm_dependency d where d.norm_id = n.id), '[]'::jsonb) as "prevNormIds",
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

    Map<String, Object> page(
        String dictionary, String active, String search,
        Integer type, Integer airport, Paging paging, String sort, String direction
    ) {
        Dictionary settings = DICTIONARIES.get(dictionary);
        Map<String, Object> params = new LinkedHashMap<>();
        List<String> filters = new ArrayList<>();

        if (!"all".equals(active)) {
            filters.add("q.\"isActive\" = :active");
            params.put("active", Boolean.valueOf(active));
        }

        if (!search.isBlank()) {
            String searchColumns = searchColumns(dictionary);
            filters.add("strpos(lower(" + searchColumns + "), lower(:search)) > 0");
            params.put("search", search);
        }

        if (type != null) {
            filters.add("q.\"aircraftTypeId\" = :type");
            params.put("type", type);
        }

        if (airport != null) {
            filters.add("q.\"airportId\" = :airport");
            params.put("airport", airport);
        }

        String from = " from (" + settings.select() + ") q";

        if (!filters.isEmpty()) {
            from += " where " + String.join(" and ", filters);
        }

        long total = jdbc.sql("select count(*)" + from)
            .params(params)
            .query(Long.class)
            .single();

        params.put("limit", paging.size());
        params.put("offset", paging.offset());

        String sql = "select *" + from
            + " order by " + (sort.isEmpty() ? orderColumns(settings)
                : "q.\"" + sort + "\" " + direction)
            + ", q.id limit :limit offset :offset";

        List<Map<String, Object>> items = jdbc.sql(sql)
            .params(params)
            .query()
            .listOfRows();

        return Map.of("items", Rows.clean(items), "total", total);
    }

    private String searchColumns(String dictionary) {
        switch (dictionary) {
            case "airports":
            case "airlines":
                return "q.\"iataCode\" || ' ' || q.name"
                    + (dictionary.equals("airports") ? " || ' ' || q.city" : "");
            case "stands":
                return "q.code || ' ' || q.\"airport\"";
            case "aircraft":
                return "q.\"tailNumber\" || ' ' || q.\"aircraftType\" || ' ' || q.\"airline\"";
            case "operation-norms":
                return "q.\"operationCode\" || ' ' || q.\"operationName\"";
            default:
                return "q.code || ' ' || q.name";
        }
    }

    Map<String, Object> one(String dictionary, long id) {
        Dictionary settings = DICTIONARIES.get(dictionary);
        String sql = "select * from (" + settings.select() + ") q where q.id = :id";

        List<Map<String, Object>> rows = jdbc.sql(sql)
            .param("id", id)
            .query()
            .listOfRows();

        if (rows.isEmpty()) {
            throw ApiException.notFound("Запись");
        }

        return Rows.clean(rows.get(0));
    }

    void lock(String dictionary, long id) {
        jdbc.sql("select id from " + DICTIONARIES.get(dictionary).table() + " where id = :id for update")
            .param("id", id)
            .query(Long.class)
            .optional()
            .orElseThrow(() -> ApiException.notFound("Запись"));
    }

    void lockNorms() {
        // Один изменяющий граф запрос за раз: два параллельных PATCH не создадут цикл.
        jdbc.sql("lock table ref.operation_norm, ref.norm_dependency in share row exclusive mode")
            .update();
    }

    static String column(String field) {
        // В SQL разрешены только заранее перечисленные столбцы.
        switch (field) {
            case "iataCode": return "iata_code";
            case "airportId": return "airport_id";
            case "aircraftTypeId": return "aircraft_type_id";
            case "airlineId": return "airline_id";
            case "tailNumber": return "tail_number";
            case "operationId": return "operation_id";
            case "seqNo": return "seq_no";
            case "durationMin": return "duration_min";
            case "isMandatory": return "is_mandatory";
            case "isActive": return "is_active";
            case "code":
            case "name":
            case "city":
                return field;
            default:
                throw new IllegalArgumentException("Недопустимое поле: " + field);
        }
    }

    long save(String dictionary, Long id, Map<String, Object> values) {
        String table = DICTIONARIES.get(dictionary).table();
        Map<String, Object> params = new LinkedHashMap<>(values);

        if (id == null) {
            StringJoiner columns = new StringJoiner(", ");
            StringJoiner parameters = new StringJoiner(", ");

            for (String field : values.keySet()) {
                columns.add(column(field));
                parameters.add(":" + field);
            }

            String sql = "insert into " + table + " (" + columns + ")"
                + " values (" + parameters + ") returning id";

            return jdbc.sql(sql)
                .params(params)
                .query(Long.class)
                .single();
        }

        if (!values.isEmpty()) {
            StringJoiner assignments = new StringJoiner(", ");

            for (String field : values.keySet()) {
                assignments.add(column(field) + " = :" + field);
            }

            params.put("id", id);
            String sql = "update " + table + " set " + assignments + " where id = :id";

            jdbc.sql(sql)
                .params(params)
                .update();
        }

        return id;
    }

    boolean activeLocked(String dictionary, long id) {
        return jdbc.sql("select is_active from " + DICTIONARIES.get(dictionary).table() + " where id = :id for share")
            .param("id", id)
            .query(Boolean.class)
            .optional()
            .orElse(false);
    }

    void dependencies(long id, List<Integer> predecessors) {
        jdbc.sql("delete from ref.norm_dependency where norm_id = :id")
            .param("id", id)
            .update();

        for (int predecessor : predecessors) {
            jdbc.sql("insert into ref.norm_dependency(norm_id, prev_norm_id) values (:id, :prev)")
                .param("id", id)
                .param("prev", predecessor)
                .update();
        }
    }

    List<Map<String, Object>> graph() {
        return jdbc.sql("select norm_id, prev_norm_id from ref.norm_dependency")
            .query()
            .listOfRows();
    }

    List<Map<String, Object>> list(String dictionary, boolean includeInactive) {
        Dictionary d = DICTIONARIES.get(dictionary);
        String sql = "select * from (" + d.select() + ") q"
            + (includeInactive ? "" : " where q.\"isActive\"")
            + " order by " + orderColumns(d);
        List<Map<String, Object>> rows = jdbc.sql(sql).query().listOfRows();
        return Rows.clean(rows);
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

    // Имена столбцов берутся только из этого списка, значения — из параметров SQL.
    static java.util.Set<String> sortFields(String dictionary) {
        String fields = switch (dictionary) {
            case "airports" -> "iataCode name city";
            case "airlines" -> "iataCode name";
            case "stands" -> "airportId airport code";
            case "aircraft-types", "operations", "delay-codes" -> "code name";
            case "aircraft" -> "tailNumber aircraftTypeId aircraftType airlineId airline";
            case "operation-norms" -> "aircraftTypeId aircraftType operationId operationName operationCode seqNo durationMin isMandatory";
            default -> "";
        };
        var result = new java.util.HashSet<>(List.of(fields.split(" ")));
        result.addAll(List.of("id", "isActive"));
        return result;
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
