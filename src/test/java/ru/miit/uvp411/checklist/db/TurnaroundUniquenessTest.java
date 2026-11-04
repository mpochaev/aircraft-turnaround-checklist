package ru.miit.uvp411.checklist.db;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import ru.miit.uvp411.checklist.IntegrationTest;

/** Уникальность оборота (миграция V202611051010, ошибки FLIGHT_ALREADY_USED и AIRCRAFT_BUSY спецификации 7.4). */
class TurnaroundUniquenessTest extends IntegrationTest {

    private final LocalDateTime sta = LocalDateTime.now().withNano(0).plusHours(2);

    @Test
    void flightBelongsToOneActiveTurnaround() {
        long arr = data.flight("KGD", "SVO", sta.minusMinutes(120), sta);
        long dep = data.flight("SVO", "LED", sta.plusMinutes(70), sta.plusMinutes(155));
        long first = data.turnaround(data.aircraft("RA-73725"), data.stand(), "planned", arr, dep, null, null, null);

        assertThatThrownBy(() -> data.turnaround(data.aircraft("RA-73725"), data.stand(), "planned", arr, dep, null, null, null))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("turnaround_arr_flight_uq");

        // после отмены те же рейсы можно взять в новый оборот на другой борт
        jdbc.sql("update ops.turnaround set status = 'cancelled', cancel_reason = 'Замена ВС' where id = :id")
                .param("id", first).update();
        assertThatCode(() -> data.turnaround(data.aircraft("RA-73725"), data.stand(), "planned", arr, dep, null, null, null))
                .doesNotThrowAnyException();
    }

    @Test
    void aircraftAndStandServeOneTurnaroundAtATime() {
        int aircraft = data.aircraft("RA-73725");
        int stand = data.stand();
        data.turnaround(aircraft, stand, "in_service", arr(), dep(), sta, null, null);

        // запланированных оборотов у борта может быть несколько
        assertThatCode(() -> data.turnaround(aircraft, data.stand(), "planned", arr(), dep(), null, null, null))
                .doesNotThrowAnyException();
        // но в работе он только в одном
        assertThatThrownBy(() -> data.turnaround(aircraft, data.stand(), "in_service", arr(), dep(), sta, null, null))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("turnaround_aircraft_busy_uq");
        // и стоянку занимает только один оборот в работе
        assertThatThrownBy(() -> data.turnaround(data.aircraft("RA-73725"), stand, "ready", arr(), dep(), sta, sta.plusMinutes(50), null))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("turnaround_stand_busy_uq");
    }

    private long arr() {
        return data.flight("KGD", "SVO", sta.minusMinutes(120), sta);
    }

    private long dep() {
        return data.flight("SVO", "LED", sta.plusMinutes(70), sta.plusMinutes(155));
    }
}
