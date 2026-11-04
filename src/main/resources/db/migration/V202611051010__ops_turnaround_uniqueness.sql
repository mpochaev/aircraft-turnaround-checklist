-- Команда «Данные» (проверяет «Бизнес-логика» как владелец схемы ops): уникальность оборота
-- по замечанию преподавателя на паре 30.10. Ограничения совпадают с ошибками спецификации API (раздел 7.4).
-- Ключ рейса (авиакомпания, номер, литера, дата, аэропорты) уникален ещё с V1 (ФТ-24).

-- 1. Рейс входит только в один неотменённый оборот (409 FLIGHT_ALREADY_USED).
--    В V1 рейс был уникален и среди отменённых: после отмены оборот нельзя было создать заново на другой борт.
ALTER TABLE ops.turnaround DROP CONSTRAINT turnaround_arr_flight_id_key;
ALTER TABLE ops.turnaround DROP CONSTRAINT turnaround_dep_flight_id_key;
CREATE UNIQUE INDEX turnaround_arr_flight_uq ON ops.turnaround (arr_flight_id) WHERE status <> 'cancelled';
CREATE UNIQUE INDEX turnaround_dep_flight_uq ON ops.turnaround (dep_flight_id) WHERE status <> 'cancelled';

-- 2. Оборот привязан к борту: один борт и одна стоянка одновременно только в одном обороте
--    в статусе «Обслуживание» или «Готов к вылету» (409 AIRCRAFT_BUSY).
--    Запланированных оборотов у борта может быть несколько: за день он прилетает не один раз.
CREATE UNIQUE INDEX turnaround_aircraft_busy_uq ON ops.turnaround (aircraft_id) WHERE status IN ('in_service', 'ready');
CREATE UNIQUE INDEX turnaround_stand_busy_uq ON ops.turnaround (stand_id) WHERE status IN ('in_service', 'ready');
