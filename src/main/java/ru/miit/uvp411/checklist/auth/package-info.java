/**
 * Модуль «Авторизация». Команда: Авторизация. Схема БД: auth.
 * <p>
 * Пользователи, пароли, сессии и роли пользователя ведёт Keycloak (ADR-0002).
 * В нашей базе: справочник прав, права ролей, ФИО пользователей для экранов и журнала, заявки на изменение данных.
 * <p>
 * Другим модулям открыт только {@link ru.miit.uvp411.checklist.auth.AuthApi}.
 * Пакеты {@code internal} и {@code web} закрыты, обращаться к ним из других модулей нельзя (это проверяет тест).
 */
@ApplicationModule(displayName = "Авторизация")
package ru.miit.uvp411.checklist.auth;

import org.springframework.modulith.ApplicationModule;
