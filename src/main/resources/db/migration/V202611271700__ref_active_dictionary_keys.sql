-- Уникальность ключей только среди активных записей всех справочников.
-- Неактивные записи и исторические ссылки по ID сохраняются.
-- IF EXISTS учитывает предыдущую миграцию уникальности кодов операций.

ALTER TABLE ref.airport DROP CONSTRAINT IF EXISTS airport_iata_code_key;
CREATE UNIQUE INDEX IF NOT EXISTS airport_active_iata_code_uq
    ON ref.airport (iata_code) WHERE is_active;

ALTER TABLE ref.airline DROP CONSTRAINT IF EXISTS airline_iata_code_key;
CREATE UNIQUE INDEX IF NOT EXISTS airline_active_iata_code_uq
    ON ref.airline (iata_code) WHERE is_active;

ALTER TABLE ref.stand DROP CONSTRAINT IF EXISTS stand_airport_id_code_key;
CREATE UNIQUE INDEX IF NOT EXISTS stand_active_airport_code_uq
    ON ref.stand (airport_id, code) WHERE is_active;

ALTER TABLE ref.aircraft_type DROP CONSTRAINT IF EXISTS aircraft_type_code_key;
CREATE UNIQUE INDEX IF NOT EXISTS aircraft_type_active_code_uq
    ON ref.aircraft_type (code) WHERE is_active;

ALTER TABLE ref.aircraft DROP CONSTRAINT IF EXISTS aircraft_tail_number_key;
CREATE UNIQUE INDEX IF NOT EXISTS aircraft_active_tail_number_uq
    ON ref.aircraft (tail_number) WHERE is_active;

ALTER TABLE ref.operation DROP CONSTRAINT IF EXISTS operation_code_key;
CREATE UNIQUE INDEX IF NOT EXISTS operation_active_code_uq
    ON ref.operation (code) WHERE is_active;

ALTER TABLE ref.operation_norm
    DROP CONSTRAINT IF EXISTS operation_norm_aircraft_type_id_operation_id_key;
CREATE UNIQUE INDEX IF NOT EXISTS operation_norm_active_type_operation_uq
    ON ref.operation_norm (aircraft_type_id, operation_id) WHERE is_active;

ALTER TABLE ref.delay_code DROP CONSTRAINT IF EXISTS delay_code_code_key;
CREATE UNIQUE INDEX IF NOT EXISTS delay_code_active_code_uq
    ON ref.delay_code (code) WHERE is_active;
