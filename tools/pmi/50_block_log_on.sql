-- Сценарий 50, шаг 1: запретить запись в журнал на стенде.
-- После этого техник завершает операцию. Ожидается ошибка 500, операция остаётся «В работе».
-- Потом обязательно выполните 50_block_log_off.sql.
CREATE FUNCTION public.pmi50_no_log() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Запись в журнал запрещена для сценария 50';
END $$;
CREATE TRIGGER pmi50_no_log BEFORE INSERT ON log.action_log
    FOR EACH ROW EXECUTE FUNCTION public.pmi50_no_log();
