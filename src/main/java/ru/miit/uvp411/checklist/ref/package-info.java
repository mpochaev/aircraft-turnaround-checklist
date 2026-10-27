/**
 * Модуль «Справочники». Команда: Справочники. Схема БД: ref.
 * <p>
 * Восемь справочников (ФТ-16–ФТ-22): аэропорты, авиакомпании, стоянки, типы ВС, воздушные суда,
 * наземные операции, нормативы операций, коды задержек.
 * Другим модулям открыт {@link ru.miit.uvp411.checklist.ref.RefApi}.
 */
@ApplicationModule(displayName = "Справочники")
package ru.miit.uvp411.checklist.ref;

import org.springframework.modulith.ApplicationModule;
