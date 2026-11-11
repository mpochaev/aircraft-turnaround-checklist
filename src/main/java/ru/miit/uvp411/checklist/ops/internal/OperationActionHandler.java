package ru.miit.uvp411.checklist.ops.internal;

/**
 * Точка подключения обработчиков Кирилла и Артёма. Реализация - отдельный Spring @Component.
 * Один обработчик на одно значение OperationAction. REVERT обслуживает оба вида исправления.
 * Сервис уже проверил право, назначение и статус оборота и заблокировал только строку операции.
 * handle проверяет условия действия, меняет ops и вызывает OpsAudit в текущей транзакции.
 * При любой ошибке исключение должно выйти наружу: частичный коммит запрещён.
 */
public interface OperationActionHandler {

    OperationAction action();

    void handle(OperationContext context, OperationRequest request);
}
