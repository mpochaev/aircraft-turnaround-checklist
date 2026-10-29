package ru.miit.uvp411.checklist.log.internal;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import ru.miit.uvp411.checklist.log.ActionTypes;
import ru.miit.uvp411.checklist.shared.jdbc.Rows;
import ru.miit.uvp411.checklist.shared.web.Paging;

/**
 * Чтение журнала для GET /log (спецификация API, раздел 8.1).
 * Сначала выбирается страница записей, потом к ней добавляются имена пользователей и названия объектов:
 * так запрос не зависит от размера журнала, который копится 3 года.
 */
@Repository
public class LogQueries {

    private static final String WHERE = """
            where l.action_time >= :from and l.action_time <= :to
              and (cast(:userId as int) is null or l.user_id = :userId)
              and (cast(:login as varchar) is null or l.login = :login)
              and (cast(:actionType as varchar) is null or l.action_type = :actionType)
              and (cast(:turnaroundId as bigint) is null or l.turnaround_id = :turnaroundId)
            """;

    /** Названия справочников для колонки «Объект». Ключ: таблица схемы ref, она же entity в записи журнала. */
    private static final Map<String, String> DICTIONARIES = Map.of(
            "airport", "Аэропорты", "airline", "Авиакомпании", "stand", "Стоянки",
            "aircraft_type", "Типы ВС", "aircraft", "Воздушные суда", "operation", "Наземные операции",
            "operation_norm", "Нормативы операций", "delay_code", "Коды задержек");

    private final JdbcClient jdbc;

    LogQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param userId пользователь из фильтра экрана или null
     * @param login  только записи этого логина (техник видит свои записи) или null
     */
    public record Filter(LocalDateTime from, LocalDateTime to, Integer userId, String login,
                         String actionType, Long turnaroundId) {
    }

    public record Page(List<Map<String, Object>> items, long total) {
    }

    public Page find(Filter f, Paging paging) {
        long total = bind(jdbc.sql("select count(*) from log.action_log l " + WHERE), f)
                .query(Long.class).single();
        List<Map<String, Object>> rows = Rows.clean(bind(jdbc.sql("""
                        with page as (
                            select l.* from log.action_log l
                            """ + WHERE + """
                            order by l.action_time desc, l.id desc
                            limit :size offset :offset
                        )
                        select l.id, l.action_time, l.user_id, l.login, l.action_type, l.entity, l.entity_id,
                               l.turnaround_id, l.old_value, l.new_value,
                               u.login as user_login, u.last_name || ' ' || left(u.first_name, 1) || '.' as user_short,
                               ot.title as object_title,
                               tu.last_name || ' ' || left(tu.first_name, 1) || '.' as target_short
                        from page l
                        left join auth.app_user u on u.id = l.user_id
                        left join ops.v_object_title ot on ot.entity = l.entity and ot.entity_id = l.entity_id
                        left join auth.app_user tu on l.entity = 'user' and tu.id = l.entity_id
                        order by l.action_time desc, l.id desc
                        """), f)
                .param("size", paging.size())
                .param("offset", paging.offset())
                .query().listOfRows());
        return new Page(rows.stream().map(LogQueries::item).toList(), total);
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, Filter f) {
        return spec.param("from", f.from())
                .param("to", f.to())
                .param("userId", f.userId())
                .param("login", f.login())
                .param("actionType", f.actionType())
                .param("turnaroundId", f.turnaroundId());
    }

    /** Строка базы в элемент ответа по таблицам 8.2 и 8.3 спецификации. */
    private static Map<String, Object> item(Map<String, Object> r) {
        Map<String, Object> user = null;
        if (r.get("user_id") != null) {
            user = new LinkedHashMap<>();
            user.put("id", r.get("user_id"));
            user.put("login", r.get("user_login"));
            user.put("shortName", r.get("user_short"));
        }
        String type = (String) r.get("action_type");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", r.get("id"));
        out.put("time", r.get("action_time"));
        out.put("user", user);
        out.put("actionType", type);
        out.put("actionName", actionName(type, r.get("new_value")));
        out.put("entity", r.get("entity"));
        out.put("entityId", r.get("entity_id"));
        out.put("objectTitle", objectTitle(r));
        out.put("turnaroundId", r.get("turnaround_id"));
        out.put("oldValue", r.get("old_value"));
        out.put("newValue", r.get("new_value"));
        return out;
    }

    private static String actionName(String type, Object newValue) {
        if ("request_reviewed".equals(type) && newValue instanceof Map<?, ?> v) {
            if ("approved".equals(v.get("status"))) {
                return "Заявка подтверждена";
            }
            if ("rejected".equals(v.get("status"))) {
                return "Заявка отклонена";
            }
        }
        return ActionTypes.name(type);
    }

    private static String objectTitle(Map<String, Object> r) {
        if (r.get("object_title") != null) {
            return (String) r.get("object_title");
        }
        String entity = (String) r.get("entity");
        Object id = r.get("entity_id");
        if (entity == null) {
            return null;
        }
        if (entity.equals("user")) {
            if (r.get("target_short") != null) {
                return "Пользователь " + r.get("target_short");
            }
            return ("login_failed".equals(r.get("action_type")) ? "Логин " : "Пользователь ") + r.get("login");
        }
        if (entity.equals("change_request")) {
            return "Заявка " + id;
        }
        if (entity.equals("role")) {
            return "Роль " + valueOf(r.get("new_value"), "name", id);
        }
        if (DICTIONARIES.containsKey(entity)) {
            Object key = valueOf(r.get("new_value"), "code", null);
            if (key == null) {
                key = valueOf(r.get("new_value"), "iataCode", valueOf(r.get("new_value"), "tailNumber", id));
            }
            return "Справочник «" + DICTIONARIES.get(entity) + "» · " + key;
        }
        // объект уже удалён по сроку хранения или ещё не описан в ops.v_object_title
        return entity + " " + (id == null ? "" : id);
    }

    private static Object valueOf(Object json, String field, Object fallback) {
        if (json instanceof Map<?, ?> m && m.get(field) != null) {
            return m.get(field);
        }
        return fallback;
    }
}
