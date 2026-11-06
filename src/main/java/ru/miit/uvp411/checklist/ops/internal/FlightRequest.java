package ru.miit.uvp411.checklist.ops.internal;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record FlightRequest(
        @NotNull @Positive Integer airlineId,
        @NotBlank @Pattern(regexp = "[0-9]{1,4}") String flightNumber,
        @Pattern(regexp = "[A-Z]?") String letter,
        @NotNull LocalDate flightDate,
        @NotNull @Positive Integer depAirportId,
        @NotNull @Positive Integer arrAirportId,
        @NotNull LocalDateTime std,
        @NotNull LocalDateTime sta) {

    String normalizedLetter() {
        return letter == null ? "" : letter;
    }
}
