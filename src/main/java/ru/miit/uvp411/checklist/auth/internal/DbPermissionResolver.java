package ru.miit.uvp411.checklist.auth.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import ru.miit.uvp411.checklist.shared.security.PermissionResolver;

/**
 * Права ролей из таблиц auth.role и auth.role_permission.
 * Ответ хранится в памяти 60 секунд (раздел 3.2 спецификации API), после изменения прав роли
 * кэш нужно сбросить методом {@link #evictAll()}.
 */
@Component
class DbPermissionResolver implements PermissionResolver {

    private static final Duration TTL = Duration.ofSeconds(60);

    private final JdbcClient jdbc;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    DbPermissionResolver(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<String> permissionsOf(Collection<String> roles) {
        Set<String> result = new TreeSet<>();
        for (String role : roles) {
            Cached c = cache.get(role);
            if (c == null || c.loadedAt().plus(TTL).isBefore(Instant.now())) {
                c = new Cached(load(role), Instant.now());
                cache.put(role, c);
            }
            result.addAll(c.permissions());
        }
        return result;
    }

    void evictAll() {
        cache.clear();
    }

    private List<String> load(String role) {
        return jdbc.sql("""
                        select p.code
                        from auth.role r
                        join auth.role_permission rp on rp.role_id = r.id
                        join auth.permission p on p.id = rp.permission_id
                        where r.code = :role
                        """)
                .param("role", role)
                .query(String.class)
                .list();
    }

    private record Cached(List<String> permissions, Instant loadedAt) {
    }
}
