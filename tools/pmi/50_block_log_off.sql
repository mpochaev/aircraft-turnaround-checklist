-- Сценарий 50, шаг 2: вернуть запись в журнал.
DROP TRIGGER pmi50_no_log ON log.action_log;
DROP FUNCTION public.pmi50_no_log();
