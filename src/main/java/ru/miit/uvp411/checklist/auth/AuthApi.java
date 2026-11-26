package ru.miit.uvp411.checklist.auth;

import java.util.Optional;

/**
 * Что модуль «Авторизация» даёт другим модулям («Описание архитектуры», таблица 4).
 * Права текущего пользователя проверяются через {@code CurrentUser.get().can(...)}, этот интерфейс
 * нужен для данных о других пользователях.
 */
public interface AuthApi {

    /** ФИО и активность пользователя по логину, например для назначения техника на оборот. */
    Optional<UserInfo> findUser(String login);

    /**
     * Есть ли у пользователя право, например turnarounds.service: можно ли назначить его техником оборота.
     * Роли пользователя хранит Keycloak (ADR-0002), права ролей лежат в auth.role_permission.
     * В режиме dev Keycloak нет, поэтому проверяется только, что пользователь активен.
     */
    boolean hasPermission(int userId, String permission);

    record UserInfo(int id, String login, String lastName, String firstName, String middleName, boolean active) {

        public String shortName() {
            return lastName + " " + firstName.charAt(0) + ".";
        }
    }
}
