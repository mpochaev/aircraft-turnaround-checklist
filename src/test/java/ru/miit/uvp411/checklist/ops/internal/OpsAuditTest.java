package ru.miit.uvp411.checklist.ops.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import ru.miit.uvp411.checklist.log.AuditLog;

class OpsAuditTest {

    private final List<AuditLog.Entry> entries = new ArrayList<>();
    private final OpsAudit audit = new OpsAudit(entries::add);

    @ParameterizedTest
    @ValueSource(strings = {"flight_created", "turnaround_created", "turnaround_cancelled", "technician_changed",
            "on_block", "ready", "off_block", "op_start", "op_finish", "op_problem", "op_resolve",
            "op_not_required", "op_revert", "delay_code", "login", "ref_changed"})
    void acceptsExistingActionTypesWithoutChangingTheEntry(String action) {
        audit.record(action, "operation_exec", 21L, 7L, Map.of("status", "in_progress"), Map.of("status", "done"));

        assertThat(entries).containsExactly(new AuditLog.Entry(action, "operation_exec", 21L, 7L,
                Map.of("status", "in_progress"), Map.of("status", "done")));
    }

    @Test
    void keepsBeforeAndAfterSnapshotsWithNullValues() {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("actualStart", null);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "in_progress");
        audit.operation("op_start", 21, 7, before, after);
        before.put("actualStart", "changed");
        after.put("status", "done");

        assertThat(entries.getFirst().oldValue()).containsEntry("actualStart", null);
        assertThat(entries.getFirst().newValue()).containsEntry("status", "in_progress");
        assertThat(entries.getFirst().turnaroundId()).isEqualTo(7L);
    }

    @Test
    void creationAndTurnaroundEventsKeepObjectAndTurnaroundIds() {
        audit.flightCreated(12, Map.of("flightNumber", "1001"));
        audit.turnaround("turnaround_created", 7, null, Map.of("status", "planned"));

        assertThat(entries.get(0)).isEqualTo(new AuditLog.Entry("flight_created", "flight", 12L, null,
                null, Map.of("flightNumber", "1001")));
        assertThat(entries.get(1)).isEqualTo(new AuditLog.Entry("turnaround_created", "turnaround", 7L, 7L,
                null, Map.of("status", "planned")));
    }

    @Test
    void unknownEventDoesNotReachTheJournal() {
        assertThatThrownBy(() -> audit.operation("op_delete", 21, 7, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(entries).isEmpty();
    }
}
