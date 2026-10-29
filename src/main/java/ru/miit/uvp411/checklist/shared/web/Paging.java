package ru.miit.uvp411.checklist.shared.web;

import ru.miit.uvp411.checklist.shared.error.ApiException;

/**
 * Постраничный вывод списков, раздел 2.3 спецификации API: page с 1, size от 1 до 100, по умолчанию 50.
 * <pre>
 * Map&lt;String, Object&gt; list(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "50") int size) {
 *     Paging paging = Paging.of(page, size);   // неверные значения дают 400 VALIDATION_ERROR
 *     ... limit :size offset :offset  с paging.size() и paging.offset()
 * }
 * </pre>
 */
public record Paging(int page, int size) {

    public static final int MAX_SIZE = 100;

    public static Paging of(int page, int size) {
        if (page < 1) {
            throw ApiException.validation("page", page, "Номер страницы начинается с 1");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw ApiException.validation("size", size, "На странице от 1 до 100 записей");
        }
        return new Paging(page, size);
    }

    public long offset() {
        return (long) (page - 1) * size;
    }
}
