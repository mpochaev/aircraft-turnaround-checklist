package ru.miit.uvp411.checklist.ref.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;
import ru.miit.uvp411.checklist.IntegrationTest;
import ru.miit.uvp411.checklist.ref.RefApi;

class RefIntegrationTest extends IntegrationTest {
    static final AtomicInteger COUNTER = new AtomicInteger();
    @Autowired ObjectMapper json;
    @Autowired RefApi refs;

    String code() {
        int n = COUNTER.getAndIncrement();
        return "X" + (char) ('A' + n / 26) + (char) ('A' + n % 26);
    }

    Map<String, Object> create(String dictionary, Map<String, Object> body) throws Exception {
        String result = mvc.perform(post("/api/v1/ref/" + dictionary)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readValue(result, Map.class);
    }

    int id(Map<String, Object> row) { return ((Number) row.get("id")).intValue(); }

    Map<String, Object> fixture(String dictionary) throws Exception {
        String value = code();
        return switch (dictionary) {
            case "airports" -> Map.of("iataCode", value, "name", "Merge " + value, "city", "Москва");
            case "airlines" -> Map.of("iataCode", value.substring(1), "name", "Merge " + value);
            case "aircraft-types", "operations", "delay-codes" -> Map.of("code", value, "name", "Merge " + value);
            case "stands" -> Map.of("airportId", id(create("airports", fixture("airports"))), "code", value);
            case "aircraft" -> Map.of("tailNumber", value,
                "aircraftTypeId", id(create("aircraft-types", fixture("aircraft-types"))),
                "airlineId", id(create("airlines", fixture("airlines"))));
            case "operation-norms" -> Map.of("aircraftTypeId", id(create("aircraft-types", fixture("aircraft-types"))),
                "operationId", id(create("operations", fixture("operations"))), "seqNo", 1, "durationMin", 10, "isMandatory", true);
            default -> throw new IllegalArgumentException(dictionary);
        };
    }

    // Все восемь справочников: создание, изменение и деактивация в настоящей БД.
    @ParameterizedTest
    @ValueSource(strings = {"airports", "airlines", "stands", "aircraft-types", "aircraft", "operations", "operation-norms", "delay-codes"})
    void crudForEveryDictionary(String dictionary) throws Exception {
        var created = create(dictionary, fixture(dictionary));
        int recordId = id(created);
        assertEquals(true, created.get("isActive"));
        String field = switch (dictionary) {
            case "stands" -> "code";
            case "aircraft" -> "tailNumber";
            case "operation-norms" -> "durationMin";
            default -> "name";
        };
        Object value = dictionary.equals("operation-norms") ? 15 : "NEW";
        mvc.perform(patch("/api/v1/ref/" + dictionary + "/" + recordId)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(field, value))))
            .andExpect(status().isOk()).andExpect(jsonPath("$." + field).value(value));
        mvc.perform(patch("/api/v1/ref/" + dictionary + "/" + recordId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"isActive\":false}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.isActive").value(false));
        assertFalse(refs.isActive(dictionary, recordId));
        String entity = switch (dictionary) {
            case "airports" -> "airport"; case "airlines" -> "airline"; case "stands" -> "stand";
            case "aircraft-types" -> "aircraft_type"; case "operations" -> "operation";
            case "operation-norms" -> "operation_norm"; case "delay-codes" -> "delay_code";
            default -> "aircraft";
        };
        assertEquals(3L, jdbc.sql("select count(*) from log.action_log where entity = :entity and entity_id = :id and action_type in ('ref_created','ref_changed')")
            .param("entity", entity).param("id", recordId).query(Long.class).single());
    }

    // Поиск, сортировка и пагинация работают совместно; total считает все совпадения.
    @Test
    void searchSortingAndPagination() throws Exception {
        String marker = "MERGE" + code();
        for (String city : List.of("Alpha", "Beta", "Gamma")) {
            create("airports", Map.of("iataCode", code(), "name", marker, "city", city));
        }
        mvc.perform(get("/api/v1/ref/airports").param("search", marker).param("page", "2").param("size", "1")
                .param("sort", "city").param("direction", "desc"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3))
            .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].city").value("Beta"));
        mvc.perform(get("/api/v1/ref/airports").param("search", marker).param("page", "99").param("size", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3)).andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get("/api/v1/ref/airports").param("sort", "name;drop table ref.airport"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    // Фильтр аэропорта не пропускает чужие стоянки, активность true/false/all различается.
    @Test
    void airportAndActivityFilters() throws Exception {
        int airport = id(create("airports", fixture("airports")));
        int stand = id(create("stands", Map.of("airportId", airport, "code", code())));
        create("stands", fixture("stands"));
        mvc.perform(patch("/api/v1/ref/stands/" + stand).contentType(MediaType.APPLICATION_JSON).content("{\"isActive\":false}"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/ref/stands").param("airportId", "" + airport))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        for (String active : List.of("false", "all")) {
            mvc.perform(get("/api/v1/ref/stands").param("airportId", "" + airport).param("isActive", active))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].id").value(stand));
        }
    }

    // Уникальность и активность ссылок проверяются на настоящей базе.
    @Test
    void duplicateAndInactiveReferenceErrors() throws Exception {
        var body = fixture("airports");
        int airport = id(create("airports", body));
        mvc.perform(post("/api/v1/ref/airports").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("DUPLICATE_CODE"));
        mvc.perform(patch("/api/v1/ref/airports/" + airport).contentType(MediaType.APPLICATION_JSON).content("{\"isActive\":false}"))
            .andExpect(status().isOk());
        mvc.perform(post("/api/v1/ref/stands").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("airportId", airport, "code", code()))))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("INACTIVE_REFERENCE"));
    }

    // Сохраняются зависимости, фильтр типа и контракт RefApi; цикл отклоняется без изменения графа.
    @Test
    void normDependenciesAndCycle() throws Exception {
        var firstBody = fixture("operation-norms");
        var first = create("operation-norms", firstBody);
        int type = ((Number) firstBody.get("aircraftTypeId")).intValue();
        var operation = create("operations", fixture("operations"));
        int second = id(create("operation-norms", Map.of("aircraftTypeId", type, "operationId", id(operation),
            "seqNo", 2, "durationMin", 20, "isMandatory", false, "prevNormIds", List.of(id(first)))));
        mvc.perform(get("/api/v1/ref/operation-norms").param("aircraftTypeId", "" + type))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2))
            .andExpect(jsonPath("$.items[1].prevNormIds[0]").value(id(first)));
        String typeCode = jdbc.sql("select code from ref.aircraft_type where id=:id").param("id", type).query(String.class).single();
        var norms = refs.activeNorms(typeCode);
        assertEquals(2, norms.size());
        assertEquals(List.of(first.get("operationCode")), norms.get(1).get("after"));
        mvc.perform(patch("/api/v1/ref/operation-norms/" + id(first)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("prevNormIds", List.of(second)))))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("DEPENDENCY_CYCLE"));
        assertEquals(0L, jdbc.sql("select count(*) from ref.norm_dependency where norm_id=:id")
            .param("id", id(first)).query(Long.class).single());
    }

    // Техник может смотреть справочники, но не может создавать или изменять записи.
    @Test
    void technicianCannotWrite() throws Exception {
        mvc.perform(get("/api/v1/ref/airports").header("X-Dev-User", "tech07").header("X-Dev-Roles", "technician"))
            .andExpect(status().isOk());
        mvc.perform(post("/api/v1/ref/airports").header("X-Dev-User", "tech07").header("X-Dev-Roles", "technician")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(fixture("airports"))))
            .andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/ref/airports/1").header("X-Dev-User", "tech07").header("X-Dev-Roles", "technician")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"New\"}"))
            .andExpect(status().isForbidden());
    }

    // Ошибка записи журнала откатывает и создание, и изменение справочника.
    @Test
    void auditFailureRollsBackChanges() throws Exception {
        var body = fixture("airports");
        int airport = id(create("airports", body));
        String rejectedCode = code();
        jdbc.sql("""
            create function public.ref_test_no_audit() returns trigger language plpgsql as $$
            begin raise exception 'Audit unavailable'; end $$
            """).update();
        jdbc.sql("create trigger ref_test_no_audit before insert on log.action_log for each row execute function public.ref_test_no_audit()")
            .update();
        try {
            mvc.perform(post("/api/v1/ref/airports").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("iataCode", rejectedCode, "name", "Test", "city", "City"))))
                .andExpect(status().isInternalServerError());
            mvc.perform(patch("/api/v1/ref/airports/" + airport).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Changed\"}"))
                .andExpect(status().isInternalServerError());
            assertEquals(0L, jdbc.sql("select count(*) from ref.airport where iata_code=:code")
                .param("code", rejectedCode).query(Long.class).single());
            assertEquals(body.get("name"), jdbc.sql("select name from ref.airport where id=:id")
                .param("id", airport).query(String.class).single());
        } finally {
            jdbc.sql("drop trigger ref_test_no_audit on log.action_log").update();
            jdbc.sql("drop function public.ref_test_no_audit()").update();
        }
    }

    // Код стоянки, уже используемой оборотом, защищает перенесённая миграция.
    @Test
    void usedStandCannotChangeCode() throws Exception {
        int stand = data.stand();
        var now = java.time.LocalDateTime.now().withNano(0);
        long arrival = data.flight("KGD", "SVO", now.minusHours(2), now);
        long departure = data.flight("SVO", "LED", now.plusHours(1), now.plusHours(2));
        data.turnaround(data.aircraft("RA-73725"), stand, "planned", arrival, departure, null, null, null);
        String old = jdbc.sql("select code from ref.stand where id=:id").param("id", stand).query(String.class).single();
        mvc.perform(patch("/api/v1/ref/stands/" + stand).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"CHANGED\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IMMUTABLE_FIELD"));
        assertEquals(old, jdbc.sql("select code from ref.stand where id=:id").param("id", stand).query(String.class).single());
        // Деактивация остаётся доступной.
        mvc.perform(patch("/api/v1/ref/stands/" + stand).contentType(MediaType.APPLICATION_JSON).content("{\"isActive\":false}"))
            .andExpect(status().isOk());
    }

    // Изменение норматива не переписывает уже скопированные данные операции оборота.
    @Test
    void normChangesKeepExistingSnapshot() throws Exception {
        var body = fixture("operation-norms");
        int norm = id(create("operation-norms", body));
        int type = ((Number) body.get("aircraftTypeId")).intValue();
        int operation = ((Number) body.get("operationId")).intValue();
        int airline = jdbc.sql("select id from ref.airline where iata_code='SU'").query(Integer.class).single();
        int aircraft = id(create("aircraft", Map.of("tailNumber", code(), "aircraftTypeId", type, "airlineId", airline)));
        var now = java.time.LocalDateTime.now().withNano(0);
        long arrival = data.flight("KGD", "SVO", now.minusHours(2), now);
        long departure = data.flight("SVO", "LED", now.plusHours(1), now.plusHours(2));
        long turnaround = data.turnaround(aircraft, data.stand(), "planned", arrival, departure, null, null, null);
        String operationCode = jdbc.sql("select code from ref.operation where id=:id").param("id", operation).query(String.class).single();
        long execution = data.operation(turnaround, operationCode, "not_started", now, now.plusMinutes(10), null, null);
        mvc.perform(patch("/api/v1/ref/operation-norms/" + norm).contentType(MediaType.APPLICATION_JSON)
                .content("{\"durationMin\":25,\"isMandatory\":false,\"seqNo\":5}"))
            .andExpect(status().isOk());
        var snapshot = jdbc.sql("select duration_min, seq_no, is_mandatory from ops.operation_exec where id=:id")
            .param("id", execution).query().singleRow();
        assertEquals(10, ((Number) snapshot.get("duration_min")).intValue());
        assertEquals(1, ((Number) snapshot.get("seq_no")).intValue());
        assertEquals(true, snapshot.get("is_mandatory"));
    }
}
