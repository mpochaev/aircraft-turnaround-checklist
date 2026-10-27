package ru.miit.uvp411.checklist.ref.web;

import java.util.List;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ru.miit.uvp411.checklist.ref.internal.RefService;

/**
 * Справочники, раздел 6 спецификации API.
 * Готово: GET /ref/{dictionary}. TODO команда «Справочники»: POST и PATCH (6.2, 6.3), поиск, постраничный вывод.
 */
@RestController
@RequestMapping("/api/v1/ref")
class RefController {

    private final RefService service;

    RefController(RefService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ref.read')")
    Map<String, Object> dictionaries() {
        return Map.of("items", service.dictionaries());
    }

    /** ?isActive=all показывает и деактивированные записи (по умолчанию только активные). */
    @GetMapping("/{dictionary}")
    @PreAuthorize("hasAuthority('ref.read')")
    Map<String, Object> list(@PathVariable String dictionary,
                             @RequestParam(defaultValue = "true") String isActive) {
        List<Map<String, Object>> items = service.list(dictionary, "all".equals(isActive));
        return Map.of("items", items, "total", items.size());
    }
}
