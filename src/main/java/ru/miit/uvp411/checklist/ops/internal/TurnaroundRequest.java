package ru.miit.uvp411.checklist.ops.internal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record TurnaroundRequest(
        @NotNull @Positive Long arrFlightId,
        @NotNull @Positive Long depFlightId,
        @NotNull @Positive Integer aircraftId,
        @NotNull @Positive Integer standId,
        @NotNull @Positive Integer technicianId) {
}
