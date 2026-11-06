package ru.miit.uvp411.checklist.ops.web;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import ru.miit.uvp411.checklist.ops.internal.FlightRequest;
import ru.miit.uvp411.checklist.ops.internal.FlightQueries;
import ru.miit.uvp411.checklist.ops.internal.FlightService;
import ru.miit.uvp411.checklist.shared.error.ApiException;

@RestController
@RequestMapping("/api/v1/flights")
class FlightController {

    private final FlightQueries queries;
    private final FlightService service;

    FlightController(FlightQueries queries, FlightService service) {
        this.queries = queries;
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('flights.read')")
    List<Map<String, Object>> search(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Integer airlineId,
            @RequestParam(required = false) String number,
            @RequestParam(required = false) Integer arrAirportId,
            @RequestParam(required = false) Integer depAirportId,
            @RequestParam(defaultValue = "true") boolean free) {
        if (date == null) {
            throw ApiException.validation("date", null, "Укажите дату рейса");
        }
        if (number != null && !number.matches("[0-9]{1,4}")) {
            throw ApiException.validation("number", number, "Номер рейса должен содержать от 1 до 4 цифр");
        }
        return queries.search(date, airlineId, number, arrAirportId, depAirportId, free);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('flights.write')")
    ResponseEntity<Map<String, Object>> create(@Valid @RequestBody FlightRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }
}
