package ru.miit.uvp411.checklist.shared.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Права и роли пользователя в виде GrantedAuthority: права как есть, роли с префиксом ROLE_. */
final class Authorities {

    private Authorities() {
    }

    static List<GrantedAuthority> of(Collection<String> roles, PermissionResolver resolver) {
        List<GrantedAuthority> list = new ArrayList<>();
        roles.forEach(r -> list.add(new SimpleGrantedAuthority(CurrentUser.ROLE_PREFIX + r)));
        resolver.permissionsOf(roles).forEach(p -> list.add(new SimpleGrantedAuthority(p)));
        return list;
    }
}
