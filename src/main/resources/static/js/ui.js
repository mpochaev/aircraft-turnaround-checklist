// Общие части страниц: окно подтверждения, ошибки полей, выбор из справочника, листалка страниц.
// Как выглядят и как подключаются: /styleguide.html, раздел «Общие части страниц».
import { api } from './api.js';
import { escapeHtml } from './format.js';

// Окно подтверждения. Возвращает true, если нажата главная кнопка, и false при «Отмена» или Esc.
//   if (await confirmDialog({ title: 'Подтвердить готовность ВС?', text: 'SU 1001 / 1002 · RA-73725 · стоянка 124' })) { ... }
// На экранах техника передайте touch: true, тогда кнопки будут крупными.
export function confirmDialog({ title, text = '', ok = 'Подтвердить', cancel = 'Отмена', touch = false }) {
    return new Promise((resolve) => {
        const dlg = document.createElement('dialog');
        dlg.className = touch ? 'dialog touch' : 'dialog';
        dlg.innerHTML = `
            <h2>${escapeHtml(title)}</h2>
            ${text ? `<p>${escapeHtml(text)}</p>` : ''}
            <div class="actions">
                <button type="button" class="btn" value="cancel">${escapeHtml(cancel)}</button>
                <button type="button" class="btn btn-primary" value="ok">${escapeHtml(ok)}</button>
            </div>`;
        dlg.addEventListener('click', (e) => {
            const button = e.target.closest('button[value]');
            if (button) {
                dlg.close(button.value);
            }
        });
        dlg.addEventListener('close', () => {
            resolve(dlg.returnValue === 'ok');
            dlg.remove();
        });
        document.body.append(dlg);
        dlg.showModal();
    });
}

// Ошибки проверки под полями формы (ФТ-18). Поле ищется по name из details[].field ответа API.
// Возвращает false, если ни одно поле не нашлось: тогда покажите ошибку целиком через showError().
//   try { await api.post('/ref/stands', body) }
//   catch (e) { if (!showFieldErrors(form, e)) showError(page, e) }
export function showFieldErrors(form, err) {
    clearFieldErrors(form);
    let shown = false;
    for (const d of err?.details || []) {
        const input = d.field ? form.querySelector(`[name="${CSS.escape(d.field)}"]`) : null;
        const field = input?.closest('.field, label');
        if (!field) {
            continue;
        }
        field.classList.add('invalid');
        const msg = document.createElement('span');
        msg.className = 'field-error';
        msg.textContent = d.message || err.message;
        field.append(msg);
        shown = true;
    }
    return shown;
}

export function clearFieldErrors(form) {
    form.querySelectorAll('.invalid').forEach((f) => f.classList.remove('invalid'));
    form.querySelectorAll('.field-error').forEach((m) => m.remove());
}

// Выпадающий список из справочника, только активные записи (ФТ-25). Возвращает записи справочника.
//   await refSelect(form.aircraftTypeId, 'aircraft-types', { label: (r) => r.code, empty: 'Все' });
// empty: текст пустого пункта или null, если пустой пункт не нужен.
export async function refSelect(select, dictionary, { label = (r) => r.name ?? r.code, empty = 'Все', selected = '' } = {}) {
    const { items } = await api.get(`/ref/${dictionary}`, { isActive: 'true' });
    select.innerHTML = (empty !== null ? `<option value="">${escapeHtml(empty)}</option>` : '')
        + items.map((r) => `<option value="${r.id}">${escapeHtml(label(r))}</option>`).join('');
    select.value = String(selected);
    return items;
}

// Листалка страниц под таблицей (спецификация API, раздел 2.3: page с 1, size до 100).
//   pager(document.getElementById('pager'), { page, size, total, onPage: (p) => load(p) });
export function pager(container, { page, size, total, onPage }) {
    if (total <= size && page === 1) {
        container.innerHTML = '';
        return;
    }
    const first = total === 0 ? 0 : (page - 1) * size + 1;
    const last = Math.min(page * size, total);
    container.className = 'pager';
    container.innerHTML = `
        <span>Записи ${first}–${last} из ${total}</span>
        <button type="button" class="btn" data-page="${page - 1}" ${page <= 1 ? 'disabled' : ''}>Назад</button>
        <button type="button" class="btn" data-page="${page + 1}" ${last >= total ? 'disabled' : ''}>Вперёд</button>`;
    container.querySelectorAll('button').forEach((b) => b.addEventListener('click', () => onPage(Number(b.dataset.page))));
}
