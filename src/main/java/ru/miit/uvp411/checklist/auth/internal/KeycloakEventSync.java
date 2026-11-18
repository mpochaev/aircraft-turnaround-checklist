package ru.miit.uvp411.checklist.auth.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import ru.miit.uvp411.checklist.auth.AuthApi;

/**
 * Copies Keycloak login events to the application journal. Event hashes make polling idempotent,
 * including the one-second overlap used to avoid missing events at page boundaries.
 */
@Component
@ConditionalOnProperty(prefix = "app.security.events", name = "enabled", havingValue = "true", matchIfMissing = true)
class KeycloakEventSync {

    private static final Logger log = LoggerFactory.getLogger(KeycloakEventSync.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final KeycloakAdminClient keycloak;
    private final JdbcClient jdbc;

    KeycloakEventSync(KeycloakAdminClient keycloak, JdbcClient jdbc) {
        this.keycloak = keycloak;
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "${app.security.events.poll-delay:15000}")
    @Transactional
    public void sync() {
        try {
            jdbc.sql("delete from auth.keycloak_event where event_time < localtimestamp - interval '30 days'").update();
            Long last = jdbc.sql("select max(event_time) from auth.keycloak_event").query(LocalDateTime.class).optional()
                    .map(t -> t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()).orElse(null);
            long since = last == null ? System.currentTimeMillis() - 300_000 : Math.max(0, last - 1000);
            for (JsonNode event : keycloak.eventsSince(since)) save(event);
        } catch (RuntimeException e) {
            log.warn("Keycloak event sync failed; it will retry on the next poll: {}", e.getMessage());
        }
    }

    private void save(JsonNode event) {
        long millis = event.path("time").asLong(0);
        String type = event.path("type").asText("");
        String error = event.path("error").asText("");
        String action = switch (type) {
            case "LOGIN" -> "login";
            case "LOGOUT" -> "logout";
            case "LOGIN_ERROR" -> error.contains("temporarily_disabled") ? "account_locked" : "login_failed";
            default -> null;
        };
        if (action == null || millis <= 0) return;
        String login = event.path("details").path("username").asText(null);
        if ((login == null || login.isBlank()) && !event.path("userId").isMissingNode()) {
            login = jdbc.sql("select login from auth.app_user where keycloak_id=:kid")
                    .param("kid",event.path("userId").asText()).query(String.class).optional().orElse(null);
        }
        if (login == null || login.isBlank()) login = "unknown";
        LocalDateTime eventTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
        String hash = hash(event);
        int inserted = jdbc.sql("""
                insert into auth.keycloak_event(event_hash,event_time) values(:hash,:time)
                on conflict(event_hash) do nothing
                """).param("hash",hash).param("time",eventTime).update();
        if (inserted == 0) return;
        jdbc.sql("""
                insert into log.action_log (user_id,login,action_type,entity,entity_id,turnaround_id,new_value,action_time)
                values ((select id from auth.app_user where login=:login),:login,:action,'user',
                        (select id from auth.app_user where login=:login),null,cast(:details as jsonb),:time)
                """).param("login",login).param("action",action).param("details",details(event)).param("time",eventTime).update();
    }

    private static String details(JsonNode e) {
        try {
            return JSON.writeValueAsString(java.util.Map.of("clientId",e.path("clientId").asText(""),
                    "ipAddress",e.path("ipAddress").asText(""),"error",e.path("error").asText("")));
        } catch (Exception ex) { return "{}"; }
    }

    private static String hash(JsonNode event) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsString(event).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
