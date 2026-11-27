package ru.miit.uvp411.checklist.ops.internal;

import jakarta.validation.constraints.NotBlank;

public record TurnaroundTransitionRequest(@NotBlank String status) {
}
