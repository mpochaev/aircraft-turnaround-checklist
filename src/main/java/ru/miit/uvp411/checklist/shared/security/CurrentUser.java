package ru.miit.uvp411.checklist.shared.security;

import java.util.Set;
import java.util.TreeSet;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Пользователь текущего запроса. Модули получают его отсюда и токен не разбирают.
 *
 * @param login       логин (в Keycloak это preferred_username, в базе auth.app_user.login)
 * @param roles       роли: technician, supervisor и роли, которые завёл супервайзер
 * @param permissions коды прав: turnarounds.create, log.read_all и т. д.
 */
public record CurrentUser(String login, Set<String> roles, Set<String> permissions) {

    public static final String ROLE_PREFIX = "ROLE_";

    public boolean can(String permission) {
        return permissions.contains(permission);
    }

    /** Текущий пользователь. Внутри HTTP-запроса к /api всегда есть. */
    public static CurrentUser get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new IllegalStateException("Нет пользователя: вызов вне запроса или без входа");
        }
        Set<String> roles = new TreeSet<>();
        Set<String> perms = new TreeSet<>();
        for (GrantedAuthority a : auth.getAuthorities()) {
            String s = a.getAuthority();
            if (s.startsWith(ROLE_PREFIX)) {
                roles.add(s.substring(ROLE_PREFIX.length()));
            } else {
                perms.add(s);
            }
        }
        return new CurrentUser(auth.getName(), roles, perms);
    }
}
