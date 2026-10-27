/**
 * Модуль «Обороты». Команда: Бизнес-логика. Схема БД: ops.
 * <p>
 * Рейсы, обороты, чек-лист, статусы и отставания (ФТ-23–ФТ-39).
 * Пользуется {@code RefApi} (нормативы, активность записей), {@code AuthApi} (техник)
 * и {@code AuditLog} (запись журнала в той же транзакции).
 */
@ApplicationModule(displayName = "Обороты")
package ru.miit.uvp411.checklist.ops;

import org.springframework.modulith.ApplicationModule;
