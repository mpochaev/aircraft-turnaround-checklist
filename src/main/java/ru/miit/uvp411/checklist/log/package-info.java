/**
 * Модуль «Журнал и отчёты». Команда: Данные. Схема БД: log.
 * <p>
 * Журнал действий (ФТ-2, ФТ-40–ФТ-42), история и показатели (ФТ-43–ФТ-45), миграции и резервные копии.
 * Другим модулям открыт {@link ru.miit.uvp411.checklist.log.AuditLog}: запись в журнал в той же транзакции,
 * что и само изменение.
 */
@ApplicationModule(displayName = "Журнал и отчёты")
package ru.miit.uvp411.checklist.log;

import org.springframework.modulith.ApplicationModule;
