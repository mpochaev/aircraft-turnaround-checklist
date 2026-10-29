#!/bin/sh
# Резервные копии базы (НФТ-4): раз в сутки в BACKUP_AT, копии хранятся KEEP_DAYS дней.
# Запускается в контейнере postgres:16 сервисом backup из infra/docker-compose.yml:
#   ... --profile backup up -d                       копия каждый день
#   ... run --rm backup now                          одна копия сейчас
#   ... run --rm backup restore <файл> [база]        восстановить копию в отдельную базу (по умолчанию checklist_restore)
# Подробно: docs/backup.md
set -eu
DIR=/backups
KEEP_DAYS=${KEEP_DAYS:-14}
BACKUP_AT=${BACKUP_AT:-03:00}

dump() {
    file="$DIR/checklist_$(date +%Y-%m-%d_%H%M).dump"
    pg_dump -Fc -f "$file.part"
    mv "$file.part" "$file"
    find "$DIR" -name 'checklist_*.dump' -mtime +$((KEEP_DAYS - 1)) -delete
    echo "$(date '+%F %T') копия $file, $(du -h "$file" | cut -f1)"
}

restore() {
    file="$1"
    db="${2:-checklist_restore}"
    [ -f "$file" ] || file="$DIR/$file"
    if [ "$db" = "$PGDATABASE" ]; then
        echo "Рабочую базу $db так не восстанавливаем: остановите приложение и восстановите вручную (docs/backup.md)" >&2
        exit 1
    fi
    dropdb --if-exists "$db"
    createdb "$db"
    pg_restore --no-owner --exit-on-error -d "$db" "$file"
    echo "$(date '+%F %T') база $db восстановлена из $file"
}

case "${1:-daily}" in
    now)
        dump
        ;;
    restore)
        shift
        restore "$@"
        ;;
    daily)
        echo "копии каждый день в $BACKUP_AT, храним $KEEP_DAYS дней"
        while true; do
            now=$(date +%s)
            next=$(date -d "today $BACKUP_AT" +%s)
            [ "$next" -gt "$now" ] || next=$(date -d "tomorrow $BACKUP_AT" +%s)
            sleep $((next - now))
            dump
        done
        ;;
    *)
        echo "Команды: daily, now, restore <файл> [база]" >&2
        exit 1
        ;;
esac
