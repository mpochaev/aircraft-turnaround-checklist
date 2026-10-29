package ru.miit.uvp411.checklist.log.internal;

import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ru.miit.uvp411.checklist.log.ActionTypes;
import ru.miit.uvp411.checklist.log.AuditLog;
import ru.miit.uvp411.checklist.shared.security.CurrentUser;
import tools.jackson.databind.json.JsonMapper;

@Component
class JdbcAuditLog implements AuditLog {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;

    JdbcAuditLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** MANDATORY: без открытой транзакции Spring бросит ошибку, запись «сама по себе» невозможна. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Entry e) {
        if (!ActionTypes.exists(e.actionType())) {
            throw new IllegalArgumentException("Неизвестный вид действия журнала: " + e.actionType()
                    + ". Коды перечислены в ActionTypes и в таблице 8.1 спецификации API");
        }
        String login = CurrentUser.get().login();
        jdbc.sql("""
                        insert into log.action_log (user_id, login, action_type, entity, entity_id, turnaround_id, old_value, new_value)
                        values ((select id from auth.app_user where login = :login), :login, :type, :entity, :entityId, :turnaroundId,
                                cast(:old as jsonb), cast(:new as jsonb))
                        """)
                .param("login", login)
                .param("type", e.actionType())
                .param("entity", e.entity())
                .param("entityId", e.entityId())
                .param("turnaroundId", e.turnaroundId())
                .param("old", json(e.oldValue()))
                .param("new", json(e.newValue()))
                .update();
    }

    private static String json(Map<String, Object> map) {
        return map == null ? null : JSON.writeValueAsString(map);
    }
}
