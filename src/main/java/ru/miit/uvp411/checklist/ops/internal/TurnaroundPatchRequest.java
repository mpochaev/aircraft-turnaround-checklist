package ru.miit.uvp411.checklist.ops.internal;

public record TurnaroundPatchRequest(String status, String cancelReason, Integer technicianId) {
}
