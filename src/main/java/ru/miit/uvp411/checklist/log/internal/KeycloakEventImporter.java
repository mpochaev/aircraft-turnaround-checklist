package ru.miit.uvp411.checklist.log.internal;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.json.JsonMapper;

/**
 * События входа из Keycloak в журнал (ФТ-40): вход, неудачный вход, блокировка, выход.
 * Пароли проверяет Keycloak, поэтому сервер узнаёт о входах из журнала событий Keycloak:
 * раз в 30 секунд забирает новые события через Admin API и пишет их в log.action_log.
 * <p>
 * Доступ к событиям даёт служебный клиент checklist-log из infra/keycloak/realm-checklist.json
 * (роли view-events и view-users). В профиле dev Keycloak не нужен, и загрузка выключена.
 */
@Component
@ConditionalOnProperty(name = "app.keycloak-events.enabled", havingValue = "true")
class KeycloakEventImporter {

    private static final Logger log = LoggerFactory.getLogger(KeycloakEventImporter.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ParameterizedTypeReference<List<Map<String, Object>>> EVENTS = new ParameterizedTypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final RestClient http;
    private final String realm;
    private final String clientId;
    private final String clientSecret;
    private final Map<String, String> usernames = new HashMap<>();
    private String token;
    private Instant tokenExpires = Instant.EPOCH;
    private boolean failing;

    KeycloakEventImporter(JdbcClient jdbc, KeycloakEventsProperties props) {
        this.jdbc = jdbc;
        this.http = RestClient.create(props.url());
        this.realm = props.realm();
        this.clientId = props.clientId();
        this.clientSecret = props.clientSecret();
    }

    @Scheduled(initialDelayString = "${app.keycloak-events.initial-delay:10000}", fixedDelayString = "${app.keycloak-events.delay:30000}")
    void importNew() {
        try {
            int count = importSince(lastImported());
            if (count > 0) {
                log.info("Из Keycloak в журнал записано событий входа: {}", count);
            }
            failing = false;
        } catch (RuntimeException e) {
            if (!failing) {   // пишем в лог один раз, пока Keycloak недоступен
                log.warn("Не удалось забрать события входа из Keycloak: {}", e.getMessage());
            }
            failing = true;
        }
    }

    int importSince(LocalDateTime after) {
        List<Map<String, Object>> events = http.get()
                .uri(u -> u.path("/admin/realms/{realm}/events")
                        .queryParam("type", "LOGIN", "LOGIN_ERROR", "LOGOUT")
                        .queryParam("dateFrom", after.toLocalDate().toString())
                        .queryParam("max", 1000)
                        .build(realm))
                .header("Authorization", "Bearer " + token())
                .retrieve()
                .body(EVENTS);
        List<Record> records = toRecords(events == null ? List.of() : events, after, this::username);
        for (Record r : records) {
            jdbc.sql("""
                            insert into log.action_log (action_time, user_id, login, action_type, entity, new_value, ip)
                            values (:time, (select id from auth.app_user where login = :login), :login, :type, 'user',
                                    cast(:value as jsonb), cast(:ip as inet))
                            """)
                    .param("time", r.time()).param("login", r.login()).param("type", r.actionType())
                    .param("value", JSON.writeValueAsString(r.value())).param("ip", r.ip())
                    .update();
        }
        return records.size();
    }

    /** Время последнего события Keycloak в журнале. Первый раз берём события за последние сутки. */
    private LocalDateTime lastImported() {
        return jdbc.sql("""
                        select max(action_time) from log.action_log
                        where action_time > localtimestamp - interval '7 days'
                          and action_type in ('login', 'login_failed', 'logout', 'account_locked')
                          and new_value ->> 'source' = 'keycloak'
                        """)
                .query(LocalDateTime.class).optional()
                .orElse(LocalDateTime.now().minusDays(1));
    }

    /** Запись журнала из события Keycloak. */
    record Record(LocalDateTime time, String login, String actionType, Map<String, Object> value, String ip) {
    }

    /**
     * События Keycloak в записи журнала, от старых к новым, только новее after.
     * LOGIN: вход; LOGIN_ERROR: неудачный вход, а при заблокированной учётной записи блокировка; LOGOUT: выход.
     */
    static List<Record> toRecords(List<Map<String, Object>> events, LocalDateTime after,
                                  Function<String, String> usernameById) {
        List<Record> out = new ArrayList<>();
        for (Map<String, Object> e : events) {
            LocalDateTime time = LocalDateTime.ofInstant(Instant.ofEpochMilli(((Number) e.get("time")).longValue()),
                    ZoneId.systemDefault());
            if (!time.isAfter(after)) {
                continue;
            }
            Map<?, ?> details = e.get("details") instanceof Map<?, ?> d ? d : Map.of();
            String login = details.get("username") != null ? String.valueOf(details.get("username"))
                    : e.get("userId") != null ? usernameById.apply(String.valueOf(e.get("userId"))) : null;
            String error = e.get("error") == null ? null : String.valueOf(e.get("error"));
            Map<String, Object> value = new LinkedHashMap<>();
            String type = switch (String.valueOf(e.get("type"))) {
                case "LOGIN" -> {
                    value.put("result", "сессия открыта");
                    yield "login";
                }
                case "LOGOUT" -> {
                    value.put("result", "сессия закрыта");
                    yield "logout";
                }
                default -> {
                    value.put("error", error);
                    yield "user_temporarily_disabled".equals(error) ? "account_locked" : "login_failed";
                }
            };
            value.put("source", "keycloak");
            out.add(new Record(time, login, type, value, (String) e.get("ipAddress")));
        }
        out.sort(Comparator.comparing(Record::time));
        return out;
    }

    private String username(String userId) {
        return usernames.computeIfAbsent(userId, id -> {
            Map<?, ?> user = http.get().uri("/admin/realms/{realm}/users/{id}", realm, id)
                    .header("Authorization", "Bearer " + token())
                    .retrieve().body(Map.class);
            return user == null ? null : String.valueOf(user.get("username"));
        });
    }

    /** Токен служебного клиента checklist-log, обновляется за 30 секунд до окончания срока. */
    private String token() {
        if (token == null || Instant.now().isAfter(tokenExpires)) {
            LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "client_credentials");
            form.add("client_id", clientId);
            form.add("client_secret", clientSecret);
            Map<?, ?> body = http.post().uri("/realms/{realm}/protocol/openid-connect/token", realm)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve().body(Map.class);
            token = String.valueOf(body.get("access_token"));
            tokenExpires = Instant.now().plusSeconds(((Number) body.get("expires_in")).longValue() - 30);
        }
        return token;
    }
}
