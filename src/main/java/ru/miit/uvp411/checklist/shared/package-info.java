/**
 * Общий код всех модулей: формат ошибок API, безопасность (токен, текущий пользователь, права),
 * идентификатор запроса, помощники для работы с JDBC.
 * <p>
 * Изменения в этом пакете затрагивают все команды, поэтому их ревьюит представитель каждой команды.
 */
@ApplicationModule(displayName = "Общее", type = ApplicationModule.Type.OPEN)
package ru.miit.uvp411.checklist.shared;

import org.springframework.modulith.ApplicationModule;
