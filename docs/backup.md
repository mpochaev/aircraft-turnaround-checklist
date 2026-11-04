# Резервные копии и хранение данных

Отвечает команда «Данные». Требования: НФТ-4 (копия раз в сутки, хранится 14 дней, раз в месяц проверка восстановления), ФТ-45 (обороты и журнал хранятся 3 года), сценарии ПМИ 55 и 56.

## Резервные копии

Копии делает сервис `backup` из `infra/docker-compose.yml`, скрипт лежит в `infra/backup/backup.sh`. Каждый день в 03:00 он выгружает базу командой `pg_dump` в файл `infra/backup/data/checklist_ГГГГ-ММ-ДД_ЧЧММ.dump`, а копии старше 14 дней удаляет.

На стенде копии включаются вместе с остальными контейнерами:

```bash
docker compose -f infra/docker-compose.yml --env-file .env --profile backup up -d
```

Сделать копию сейчас:

```bash
docker compose -f infra/docker-compose.yml --env-file .env run --rm backup now
```

На своём компьютере копии не нужны: профиль `backup` без флага не запускается.

## Восстановление (сценарий 55)

Копия восстанавливается в отдельную базу, рабочая база при этом не меняется:

```bash
docker compose -f infra/docker-compose.yml --env-file .env run --rm backup restore checklist_2026-10-28_0300.dump
```

Появится база `checklist_restore`. Чтобы проверить, что система на ней работает, запустите приложение с `DB_NAME=checklist_restore` и откройте таблицу оборотов и журнал за день копии. Число оборотов, операций и записей журнала должно совпасть с рабочей базой на момент копии:

```sql
select (select count(*) from ops.turnaround), (select count(*) from ops.operation_exec),
       (select count(*) from log.action_log), (select max(version) from flyway_schema_history);
```

Раз в месяц это делается на тестовом стенде, результат записывается в протокол испытаний.

Если нужно вернуть рабочую базу после сбоя, остановите приложение и восстановите её вручную:

```bash
docker compose -f infra/docker-compose.yml exec db dropdb -U checklist checklist
docker compose -f infra/docker-compose.yml exec db createdb -U checklist checklist
docker compose -f infra/docker-compose.yml run --rm --entrypoint pg_restore backup --no-owner -d checklist /backups/<файл>
```

## Хранение 3 года (сценарий 56)

Раз в месяц, 1-го числа в 03:30, приложение вызывает процедуру `log.purge_expired()` (класс `RetentionJob`). Она удаляет обороты с операциями и рейсами и записи журнала за месяцы, которые закончились больше 3 лет назад. Журнал разбит на секции по месяцам (миграция `V202611051000`), поэтому старый месяц журнала удаляется целой секцией. Секции на текущий месяц и 3 месяца вперёд создаёт `log.ensure_partitions()` при запуске приложения и раз в месяц. Справочники и пользователи остаются. В лог сервера пишется, сколько записей удалено.

Вручную процедура запускается в Adminer или psql:

```sql
select * from log.purge_expired();
```

Как проверить на стенде, написано в `tools/pmi/56_old_data.sql`.

Журнал защищён триггерами: изменить запись нельзя, удалить можно только запись старше 3 лет, очистить таблицу командой `TRUNCATE` нельзя (ФТ-41). Поэтому процедура удаляет только то, что разрешено по сроку.
