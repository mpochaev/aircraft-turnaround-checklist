// Форматы и подписи. Время на экранах: ЧЧ:ММ, дата: ДД.ММ.ГГГГ, всё в местном времени базового аэропорта.

export function time(iso) {
    return iso ? iso.substring(11, 16) : '';
}

export function date(iso) {
    if (!iso) {
        return '';
    }
    const [y, m, d] = iso.substring(0, 10).split('-');
    return `${d}.${m}.${y}`;
}

export function today() {
    const d = new Date();
    const pad = (n) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

export function minutesBetween(fromIso, toIso) {
    return Math.round((new Date(toIso) - new Date(fromIso)) / 60000);
}

export const TURNAROUND_STATUS = {
    planned: 'Запланирован',
    in_service: 'Обслуживание',
    ready: 'Готов к вылету',
    closed: 'Закрыт',
    cancelled: 'Отменён',
};

export const OPERATION_STATUS = {
    not_started: 'Не начата',
    in_progress: 'В работе',
    problem: 'Проблема',
    done: 'Выполнена',
    not_required: 'Не требуется',
};

// Класс цвета ячейки операции по таблице 3 ЧТЗ: статус и отставание.
export function operationClass(op, nowIso) {
    if (op.status === 'done' || op.status === 'in_progress') {
        const end = op.status === 'done' ? op.actualEnd : nowIso;
        const lag = end && op.plannedEnd ? minutesBetween(op.plannedEnd, end) : 0;
        if (lag > 10) {
            return 'st-late_critical';
        }
        if (lag > 0) {
            return 'st-late';
        }
        return op.status === 'done' ? 'st-done' : 'st-in_progress';
    }
    return `st-${op.status}`;
}

export function escapeHtml(s) {
    return String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
