package ru.miit.uvp411.checklist.log;

import java.util.Map;

/**
 * Запись в журнал действий. Вызывается внутри транзакции изменения (метод сервиса с @Transactional):
 * если транзакции нет, запись не выполняется и бросается ошибка. Так изменение без записи в журнал
 * не может сохраниться (ФТ-2).
 *
 * <pre>
 * auditLog.record(new AuditLog.Entry("op_finish", "operation_exec", execId, turnaroundId,
 *         Map.of("status", "in_progress"), Map.of("status", "done", "actualEnd", now)));
 * </pre>
 */
public interface AuditLog {

    void record(Entry entry);

    /**
     * @param actionType вид действия из таблицы 8.1 спецификации: login, on_block, op_start, op_finish… Список в {@link ActionTypes}
     * @param entity     таблица изменённого объекта, например operation_exec
     * @param entityId   id объекта
     * @param turnaroundId оборот, к которому относится действие, или null
     * @param oldValue   значения полей до изменения или null
     * @param newValue   значения полей после изменения или null
     */
    record Entry(String actionType, String entity, Long entityId, Long turnaroundId,
                 Map<String, Object> oldValue, Map<String, Object> newValue) {
    }
}
