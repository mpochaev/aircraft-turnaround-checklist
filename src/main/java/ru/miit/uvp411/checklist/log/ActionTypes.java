package ru.miit.uvp411.checklist.log;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Виды действий журнала, таблица 8.1 спецификации API. Код пишется в log.action_log.action_type,
 * название показывается в колонке «Действие». {@link AuditLog#record} принимает только коды из этого списка.
 * Новый вид действия добавляет команда «Данные» по просьбе команды, которой он нужен.
 */
public final class ActionTypes {

    private static final Map<String, String> NAMES = new LinkedHashMap<>();

    static {
        // вход
        NAMES.put("login", "Вход");
        NAMES.put("login_failed", "Неудачный вход");
        NAMES.put("logout", "Выход");
        NAMES.put("account_locked", "Блокировка");
        // учётные записи, заявки, роли
        NAMES.put("user_created", "Создание пользователя");
        NAMES.put("user_changed", "Изменение пользователя");
        NAMES.put("request_created", "Заявка на изменение данных");
        NAMES.put("request_reviewed", "Решение по заявке");
        NAMES.put("role_created", "Создание роли");
        NAMES.put("role_changed", "Изменение роли");
        // справочники
        NAMES.put("ref_created", "Добавление в справочник");
        NAMES.put("ref_changed", "Изменение справочника");
        // рейсы и обороты
        NAMES.put("flight_created", "Создание рейса");
        NAMES.put("turnaround_created", "Создание оборота");
        NAMES.put("turnaround_cancelled", "Отмена оборота");
        NAMES.put("technician_changed", "Замена техника");
        NAMES.put("on_block", "Постановка ВС");
        NAMES.put("ready", "Подтверждение готовности");
        NAMES.put("off_block", "Отправление ВС");
        // отметки операций
        NAMES.put("op_start", "Начало операции");
        NAMES.put("op_finish", "Завершение операции");
        NAMES.put("op_problem", "Проблема");
        NAMES.put("op_resolve", "Проблема решена");
        NAMES.put("op_not_required", "Не требуется");
        NAMES.put("op_revert", "Исправление отметки");
        NAMES.put("delay_code", "Код задержки");
    }

    private ActionTypes() {
    }

    public static boolean exists(String code) {
        return NAMES.containsKey(code);
    }

    /** Название действия по коду, для неизвестного кода сам код. */
    public static String name(String code) {
        return NAMES.getOrDefault(code, code);
    }
}
