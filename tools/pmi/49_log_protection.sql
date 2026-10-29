-- Сценарий 49: защита журнала (ФТ-41).
-- Выполняйте запросы по одному. Каждый должен завершиться ошибкой базы с текстом в скобках,
-- а запись журнала после этого не должна измениться.

-- 1. Изменение записи («Записи журнала изменять нельзя»)
UPDATE log.action_log SET login = 'pmi' WHERE id = (SELECT max(id) FROM log.action_log);

-- 2. Удаление записи («Записи журнала моложе 3 лет удалять нельзя»)
DELETE FROM log.action_log WHERE id = (SELECT max(id) FROM log.action_log);

-- 3. Очистка таблицы («Журнал очищать нельзя»)
TRUNCATE log.action_log;

-- 4. Запись на месте
SELECT id, action_time, login, action_type FROM log.action_log ORDER BY id DESC LIMIT 1;
