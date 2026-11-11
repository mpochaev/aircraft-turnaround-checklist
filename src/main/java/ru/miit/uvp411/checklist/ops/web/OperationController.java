package ru.miit.uvp411.checklist.ops.web;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ru.miit.uvp411.checklist.ops.internal.OperationRequest;
import ru.miit.uvp411.checklist.ops.internal.OperationService;
import ru.miit.uvp411.checklist.shared.error.ApiError;
import ru.miit.uvp411.checklist.shared.error.ApiException;
import ru.miit.uvp411.checklist.shared.web.RequestIdFilter;

/** Общий маршрут раздела 7.7; обработчики действий подключаются отдельными Spring-компонентами. */
@RestController
@RequestMapping("/api/v1/operations")
class OperationController {

    private final OperationService service;

    OperationController(OperationService service) {
        this.service = service;
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('operations.execute', 'operations.manage')")
    public Map<String, Object> update(@PathVariable long id, @Valid @RequestBody OperationRequest request) {
        if (id <= 0) {
            throw ApiException.validation("id", id, "Идентификатор должен быть положительным");
        }
        return service.update(id, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> invalidJson(HttpMessageNotReadableException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiError.of("VALIDATION_ERROR",
                "Проверьте JSON: допустимы только status, comment и delayCodeId", List.of(), RequestIdFilter.current()));
    }
}
