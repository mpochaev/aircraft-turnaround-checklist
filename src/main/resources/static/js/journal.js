// Журнал действий на экранах: виды действий для фильтра и подписи значений «Было» и «Стало».
// Пользуются страница журнала (log/index.html) и блок «Мои действия за смену» в профиле.
import { OPERATION_STATUS, TURNAROUND_STATUS, escapeHtml, date } from './format.js';

// Таблица 8.1 спецификации API. Названия совпадают с ActionTypes.java на сервере.
export const ACTION_TYPES = {
    login: 'Вход',
    login_failed: 'Неудачный вход',
    logout: 'Выход',
    account_locked: 'Блокировка',
    user_created: 'Создание пользователя',
    user_changed: 'Изменение пользователя',
    request_created: 'Заявка на изменение данных',
    request_reviewed: 'Решение по заявке',
    role_created: 'Создание роли',
    role_changed: 'Изменение роли',
    ref_created: 'Добавление в справочник',
    ref_changed: 'Изменение справочника',
    flight_created: 'Создание рейса',
    turnaround_created: 'Создание оборота',
    turnaround_cancelled: 'Отмена оборота',
    technician_changed: 'Замена техника',
    on_block: 'Постановка ВС',
    ready: 'Подтверждение готовности',
    off_block: 'Отправление ВС',
    op_start: 'Начало операции',
    op_finish: 'Завершение операции',
    op_problem: 'Проблема',
    op_resolve: 'Проблема решена',
    op_not_required: 'Не требуется',
    op_revert: 'Исправление отметки',
    delay_code: 'Код задержки',
};

const STATUS = { ...OPERATION_STATUS, ...TURNAROUND_STATUS, pending: 'На рассмотрении', approved: 'Подтверждена', rejected: 'Отклонена' };
const TEXT = ['comment', 'cancelReason', 'reason'];
// время отметки совпадает со временем записи, в колонках «Было» и «Стало» его не повторяем
const HIDDEN = ['actualStart', 'actualEnd', 'onBlockAt', 'readyAt', 'offBlockAt'];
const NAMES = { aircraft: 'борт', stand: 'стоянка', technician: 'техник', flight: 'рейс', delayCode: 'код задержки' };

// Запасной вариант на случай, если сервер не прислал oldText и newText: сервер сам подставляет названия вместо id.
// {"status": "problem", "comment": "Треснул шланг"} → «проблема: Треснул шланг»; {"delayCode": "36"} → «36».
export function describeValue(v) {
    if (v === null || v === undefined) {
        return '—';
    }
    if (typeof v !== 'object') {
        return String(v);
    }
    const keys = Object.keys(v).filter((k) => !HIDDEN.includes(k));
    if (keys.length === 1 && !TEXT.includes(keys[0]) && keys[0] !== 'status') {
        return show(v[keys[0]]);
    }
    const parts = [];
    if (v.status) {
        parts.push((STATUS[v.status] || v.status).toLowerCase());
    }
    keys.filter((k) => k !== 'status' && !TEXT.includes(k)).forEach((k) => parts.push(`${NAMES[k] || k} ${show(v[k])}`));
    const text = TEXT.map((k) => v[k]).filter(Boolean).join('; ');
    const s = parts.join(', ');
    return (text ? (s ? `${s}: ${text}` : text) : s) || '—';
}

function show(val) {
    return typeof val === 'object' && val !== null ? JSON.stringify(val) : String(val);
}

// Время записи: ЧЧ:ММ:СС, а если в выборке несколько суток, то с датой.
export function logTime(iso, withDate) {
    const t = iso.substring(11, 19);
    return withDate ? `${date(iso).substring(0, 5)} ${t}` : t;
}

// Строки таблицы журнала. user: показывать колонку «Пользователь».
export function logRows(items, { user = true, withDate = false } = {}) {
    const cols = user ? 6 : 5;
    if (!items.length) {
        return `<tr><td colspan="${cols}" class="center muted">Записей нет</td></tr>`;
    }
    return items.map((r) => `
        <tr${r.turnaroundId ? ` class="clickable" data-turnaround="${r.turnaroundId}" title="Открыть оборот ${r.turnaroundId}"` : ''}>
            <td class="center nowrap">${logTime(r.time, withDate)}</td>
            ${user ? `<td class="nowrap">${escapeHtml(r.user?.shortName ?? '—')}</td>` : ''}
            <td>${escapeHtml(r.actionName)}</td>
            <td>${escapeHtml(r.objectTitle ?? '—')}</td>
            <td>${escapeHtml(r.oldText ?? describeValue(r.oldValue))}</td>
            <td>${escapeHtml(r.newText ?? describeValue(r.newValue))}</td>
        </tr>`).join('');
}
