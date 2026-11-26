-- Команда «Данные» для команды «Справочники»: ключ записи справочника нельзя менять, если на запись
-- уже ссылаются рабочие данные (рейсы, обороты, операции). Иначе старые обороты и журнал «поменяют»
-- стоянку, борт или рейс задним числом. Деактивировать такую запись можно (ФТ-19).
-- Ошибка с текстом IMMUTABLE_FIELD превращается в ответ 409 IMMUTABLE_FIELD (GlobalExceptionHandler).

CREATE FUNCTION ref.forbid_used_key_change() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    v_used boolean := false;
BEGIN
    CASE TG_TABLE_NAME
        WHEN 'stand' THEN
            IF (NEW.code, NEW.airport_id) IS DISTINCT FROM (OLD.code, OLD.airport_id) THEN
                v_used := EXISTS (SELECT 1 FROM ops.turnaround WHERE stand_id = OLD.id);
            END IF;
        WHEN 'aircraft' THEN
            IF (NEW.tail_number, NEW.aircraft_type_id, NEW.airline_id)
               IS DISTINCT FROM (OLD.tail_number, OLD.aircraft_type_id, OLD.airline_id) THEN
                v_used := EXISTS (SELECT 1 FROM ops.turnaround WHERE aircraft_id = OLD.id);
            END IF;
        WHEN 'airport' THEN
            IF NEW.iata_code IS DISTINCT FROM OLD.iata_code THEN
                v_used := EXISTS (SELECT 1 FROM ops.flight WHERE dep_airport_id = OLD.id OR arr_airport_id = OLD.id);
            END IF;
        WHEN 'airline' THEN
            IF NEW.iata_code IS DISTINCT FROM OLD.iata_code THEN
                v_used := EXISTS (SELECT 1 FROM ops.flight WHERE airline_id = OLD.id);
            END IF;
        WHEN 'operation' THEN
            IF NEW.code IS DISTINCT FROM OLD.code THEN
                v_used := EXISTS (SELECT 1 FROM ops.operation_exec WHERE operation_id = OLD.id);
            END IF;
        WHEN 'delay_code' THEN
            IF NEW.code IS DISTINCT FROM OLD.code THEN
                v_used := EXISTS (SELECT 1 FROM ops.operation_exec WHERE delay_code_id = OLD.id);
            END IF;
    END CASE;
    IF v_used THEN
        RAISE EXCEPTION 'IMMUTABLE_FIELD: запись %.% используется, ключевые поля менять нельзя', TG_TABLE_NAME, OLD.id;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER stand_used_key BEFORE UPDATE ON ref.stand FOR EACH ROW EXECUTE FUNCTION ref.forbid_used_key_change();
CREATE TRIGGER aircraft_used_key BEFORE UPDATE ON ref.aircraft FOR EACH ROW EXECUTE FUNCTION ref.forbid_used_key_change();
CREATE TRIGGER airport_used_key BEFORE UPDATE ON ref.airport FOR EACH ROW EXECUTE FUNCTION ref.forbid_used_key_change();
CREATE TRIGGER airline_used_key BEFORE UPDATE ON ref.airline FOR EACH ROW EXECUTE FUNCTION ref.forbid_used_key_change();
CREATE TRIGGER operation_used_key BEFORE UPDATE ON ref.operation FOR EACH ROW EXECUTE FUNCTION ref.forbid_used_key_change();
CREATE TRIGGER delay_code_used_key BEFORE UPDATE ON ref.delay_code FOR EACH ROW EXECUTE FUNCTION ref.forbid_used_key_change();
