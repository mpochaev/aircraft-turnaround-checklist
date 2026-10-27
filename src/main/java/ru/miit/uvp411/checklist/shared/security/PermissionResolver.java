package ru.miit.uvp411.checklist.shared.security;

import java.util.Collection;
import java.util.Set;

/**
 * Переводит роли пользователя в коды прав (таблица 3.1 спецификации API).
 * Реализует модуль {@code auth} по таблицам auth.role и auth.role_permission.
 */
public interface PermissionResolver {

    Set<String> permissionsOf(Collection<String> roles);
}
