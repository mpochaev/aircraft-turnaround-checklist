package ru.miit.uvp411.checklist.ref.web;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import ru.miit.uvp411.checklist.ref.internal.RefService;

@RestController
@RequestMapping("/api/v1/ref")
class RefController {
    private final RefService service;
    RefController(RefService service) { this.service = service; }

    @GetMapping
    @PreAuthorize("hasAuthority('ref.read')")
    Map<String, Object> dictionaries() { return Map.of("items", service.dictionaries()); }

    @GetMapping("/{dictionary}")
    @PreAuthorize("hasAuthority('ref.read')")
    Map<String, Object> list(@PathVariable String dictionary,
            @RequestParam(defaultValue = "true") String isActive,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(required = false) Integer aircraftTypeId,
            @RequestParam(required = false) Integer airportId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(defaultValue = "") String sort,
            @RequestParam(defaultValue = "asc") String direction) {
        return service.list(dictionary, isActive, search, aircraftTypeId, airportId, page, size, sort, direction);
    }

    @PostMapping("/{dictionary}")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ref.write')")
    Map<String, Object> create(@PathVariable String dictionary, @RequestBody Map<String, Object> body) {
        return service.create(dictionary, body);
    }

    @PatchMapping("/{dictionary}/{id}")
    @PreAuthorize("hasAuthority('ref.edit')")
    Map<String, Object> update(@PathVariable String dictionary, @PathVariable long id, @RequestBody Map<String, Object> body) {
        return service.update(dictionary, id, body);
    }
}
