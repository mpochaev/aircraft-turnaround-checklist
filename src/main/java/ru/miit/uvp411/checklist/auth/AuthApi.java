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

    record UserInfo(int id, String login, String lastName, String firstName, String middleName, boolean active) {

        public String shortName() {
            return lastName + " " + firstName.charAt(0) + ".";
        }
    }
}
