package ru.miit.uvp411.checklist.ref;

import java.util.List;
import java.util.Map;

/**
 * Что модуль «Справочники» даёт другим модулям («Описание архитектуры», таблица 4).
 * Модуль «Обороты» берёт отсюда нормативы при создании оборота и проверяет активность записей.
 */
public interface RefApi {

    /** Справочники, которые можно читать: airports, airlines, stands, aircraft-types, aircraft, operations, operation-norms, delay-codes. */
    List<String> dictionaries();

    /** Активна ли запись справочника. Для неизвестного справочника или id: false. */
    boolean isActive(String dictionary, long id);

    /**
     * Активные нормативы типа ВС с зависимостями: snapshot для чек-листа (ФТ-21, ФТ-27).
     * TODO команда «Справочники»: заменить Map на запись Norm(operationCode, seqNo, durationMin, mandatory, after).
     */
    List<Map<String, Object>> activeNorms(String aircraftTypeCode);
}
