import { api, ApiError } from './api.js';
import { confirmDialog } from './ui.js';
import { showError } from './layout.js';
import { escapeHtml } from './format.js';

export const OPERATION_ACTIONS = {
    start: {
        label: 'Начать',
        status: 'in_progress',
        confirm: { title: 'Начать операцию?', ok: 'Начать' },
    },
    finish: {
        label: 'Завершить',
        status: 'done',
        confirm: { title: 'Завершить операцию?', ok: 'Завершить' },
    },
    problem: {
        label: 'Проблема',
        status: 'problem',
        needsComment: true,
        commentTitle: (text) => (text ? `Проблема: ${text}` : 'Проблема'),
        commentLabel: 'Что случилось (обязательно)',
        commentOk: 'Сообщить',
    },
    resolve: {
        label: 'Решено',
        status: 'in_progress',
        needsComment: true,
        confirm: { title: 'Отметить проблему как решённую?', ok: 'Решено' },
        commentTitle: () => 'Решение проблемы',
        commentLabel: 'Комментарий (обязательно)',
        commentOk: 'Решено',
    },
    not_required: {
        label: 'Не требуется',
        status: 'not_required',
        needsComment: true,
        confirm: { title: 'Отметить «Не требуется»?', ok: 'Подтвердить' },
        commentTitle: () => 'Не требуется',
        commentLabel: 'Комментарий (обязательно)',
        commentOk: 'Подтвердить',
    },
    revert: {
        label: 'Исправить',
        needsComment: true,
        confirm: { title: 'Исправить отметку?', ok: 'Исправить' },
        commentTitle: () => 'Исправление отметки',
        commentLabel: 'Комментарий (обязательно)',
        commentOk: 'Исправить',
        statusFor: (op) => (op.status === 'done' ? 'in_progress' : 'not_started'),
    },
};

export const TURNAROUND_ACTIONS = {
    on_block: {
        label: 'Постановка ВС',
        body: { status: 'in_service' },
        confirm: { title: 'Подтвердить постановку ВС?', ok: 'Постановка ВС' },
    },
    in_service: {
        label: 'Постановка ВС',
        body: { status: 'in_service' },
        confirm: { title: 'Подтвердить постановку ВС?', ok: 'Постановка ВС' },
    },
    ready: {
        label: 'Подтвердить готовность',
        body: { status: 'ready' },
        confirm: { title: 'Подтвердить готовность ВС?', ok: 'Подтвердить' },
    },
    off_block: {
        label: 'Отправление ВС',
        body: { status: 'closed' },
        confirm: { title: 'Подтвердить отправление ВС?', ok: 'Отправление ВС' },
    },
    closed: {
        label: 'Отправление ВС',
        body: { status: 'closed' },
        confirm: { title: 'Подтвердить отправление ВС?', ok: 'Отправление ВС' },
    },
    cancel: {
        label: 'Отмена',
        body: { status: 'cancelled' },
        needsReason: true,
        confirm: { title: 'Отменить оборот?', ok: 'Отменить' },
    },
    cancelled: {
        label: 'Отмена',
        body: { status: 'cancelled' },
        needsReason: true,
        confirm: { title: 'Отменить оборот?', ok: 'Отменить' },
    },
};

function enrichActionError(err) {
    if (!(err instanceof ApiError)) {
        return err;
    }
    if (err.code === 'INVALID_TRANSITION') {
        err.message = err.message && err.message !== 'Ошибка 409'
            ? err.message
            : 'Нельзя сменить статус: переход не разрешён (INVALID_TRANSITION)';
    } else if (err.code === 'MANDATORY_NOT_DONE') {
        const ops = (err.details || [])
            .map((d) => d.message || d.field)
            .filter(Boolean)
            .join(', ');
        const base = 'Нельзя подтвердить готовность: не выполнены обязательные операции (MANDATORY_NOT_DONE)';
        err.message = ops ? `${base}: ${ops}` : (err.message && !err.message.startsWith('Ошибка') ? err.message : base);
    } else if (err.code === 'PREDECESSORS_NOT_DONE') {
        const ops = (err.details || [])
            .map((d) => d.message || d.field)
            .filter(Boolean)
            .join('; ');
        err.message = err.message && !err.message.startsWith('Ошибка')
            ? err.message
            : (ops ? `Сначала завершите предшественников (PREDECESSORS_NOT_DONE): ${ops}`
                : 'Сначала завершите предшествующие операции (PREDECESSORS_NOT_DONE)');
    } else if (err.code === 'DELAY_CODE_REQUIRED') {
        err.message = err.message && !err.message.startsWith('Ошибка')
            ? err.message
            : 'Укажите код задержки при отставании больше 10 минут (DELAY_CODE_REQUIRED)';
    } else if (err.code === 'COMMENT_REQUIRED') {
        err.message = err.message && !err.message.startsWith('Ошибка')
            ? err.message
            : 'Укажите комментарий (COMMENT_REQUIRED)';
    }
    return err;
}

function showActionError(container, err) {
    if (container) {
        showError(container, enrichActionError(err));
    }
}

export function commentDialog({
    title = 'Комментарий',
    label = 'Комментарий (обязательно)',
    ok = 'Сообщить',
    cancel = 'Отмена',
    touch = true,
} = {}) {
    return new Promise((resolve) => {
        const dlg = document.createElement('dialog');
        dlg.className = touch ? 'dialog touch' : 'dialog';
        dlg.innerHTML = `
            <h2>${escapeHtml(title)}</h2>
            <label class="field">
                <span>${escapeHtml(label)}</span>
                <textarea class="textarea" name="comment" rows="4"></textarea>
            </label>
            <div class="actions">
                <button type="button" class="btn" value="cancel">${escapeHtml(cancel)}</button>
                <button type="button" class="btn btn-primary" value="ok">${escapeHtml(ok)}</button>
            </div>`;
        const field = dlg.querySelector('.field');
        const textarea = dlg.querySelector('textarea');
        dlg.addEventListener('click', (e) => {
            const button = e.target.closest('button[value]');
            if (!button) {
                return;
            }
            if (button.value === 'ok') {
                const value = textarea.value.trim();
                if (!value) {
                    field.classList.add('invalid');
                    if (!field.querySelector('.field-error')) {
                        const msg = document.createElement('span');
                        msg.className = 'field-error';
                        msg.textContent = 'Укажите комментарий';
                        field.append(msg);
                    }
                    return;
                }
                dlg.close('ok');
                resolve(value);
                return;
            }
            dlg.close('cancel');
            resolve(null);
        });
        dlg.addEventListener('close', () => {
            if (dlg.returnValue !== 'ok') {
                resolve(null);
            }
            dlg.remove();
        });
        document.body.append(dlg);
        dlg.showModal();
        textarea.focus();
    });
}

export function delayCodeDialog({
    title = 'Код задержки',
    text = 'Отставание больше 10 минут. Выберите код задержки.',
    codes = [],
    ok = 'Завершить',
    cancel = 'Отмена',
    touch = true,
} = {}) {
    return new Promise((resolve) => {
        const dlg = document.createElement('dialog');
        dlg.className = touch ? 'dialog touch' : 'dialog';
        dlg.innerHTML = `
            <h2>${escapeHtml(title)}</h2>
            ${text ? `<p>${escapeHtml(text)}</p>` : ''}
            <label class="field">
                <span>Код задержки</span>
                <select class="select" name="delayCodeId">
                    <option value="">Выберите код</option>
                    ${codes.map((c) => `<option value="${c.id}">${escapeHtml(c.code)}${c.name ? ` — ${escapeHtml(c.name)}` : ''}</option>`).join('')}
                </select>
            </label>
            <div class="actions">
                <button type="button" class="btn" value="cancel">${escapeHtml(cancel)}</button>
                <button type="button" class="btn btn-primary" value="ok">${escapeHtml(ok)}</button>
            </div>`;
        const field = dlg.querySelector('.field');
        const select = dlg.querySelector('select');
        dlg.addEventListener('click', (e) => {
            const button = e.target.closest('button[value]');
            if (!button) {
                return;
            }
            if (button.value === 'ok') {
                if (!select.value) {
                    field.classList.add('invalid');
                    if (!field.querySelector('.field-error')) {
                        const msg = document.createElement('span');
                        msg.className = 'field-error';
                        msg.textContent = 'Выберите код задержки';
                        field.append(msg);
                    }
                    return;
                }
                dlg.close('ok');
                resolve(Number(select.value));
                return;
            }
            dlg.close('cancel');
            resolve(null);
        });
        dlg.addEventListener('close', () => {
            if (dlg.returnValue !== 'ok') {
                resolve(null);
            }
            dlg.remove();
        });
        document.body.append(dlg);
        dlg.showModal();
        select.focus();
    });
}

export function renderActionButtons(actions, { primary = null, className = 'btn', attrs = '' } = {}) {
    return (actions || [])
        .map((code) => {
            const meta = OPERATION_ACTIONS[code] || TURNAROUND_ACTIONS[code];
            if (!meta) {
                return '';
            }
            const cls = code === primary ? `${className} btn-primary` : className;
            return `<button type="button" class="${cls}" data-action="${escapeHtml(code)}" ${attrs}>${escapeHtml(meta.label)}</button>`;
        })
        .filter(Boolean)
        .join('');
}

async function patchOperation(operationId, body, errorContainer, onDone, { silentCodes = [] } = {}) {
    try {
        const result = await api.patch(`/operations/${operationId}`, body);
        if (onDone) {
            await onDone(result);
        }
        return result;
    } catch (e) {
        if (!(e instanceof ApiError && silentCodes.includes(e.code))) {
            showActionError(errorContainer, e);
        }
        throw e;
    }
}

export async function runOperationAction({
    operationId,
    action,
    operation = {},
    extra = {},
    confirmText = '',
    touch = true,
    errorContainer = document.getElementById('page'),
    delayCodes = null,
    onDone,
} = {}) {
    const meta = OPERATION_ACTIONS[action];
    if (!meta) {
        throw new Error(`Неизвестное действие операции: ${action}`);
    }
    const status = meta.statusFor ? meta.statusFor(operation) : meta.status;
    let comment = extra.comment;
    if (meta.needsComment && (comment === undefined || comment === null || comment === '')) {
        comment = await commentDialog({
            title: meta.commentTitle ? meta.commentTitle(confirmText) : 'Комментарий',
            label: meta.commentLabel || 'Комментарий (обязательно)',
            ok: meta.commentOk || 'Сообщить',
            touch,
        });
        if (!comment) {
            return null;
        }
    }
    if (meta.confirm && action !== 'problem') {
        const ok = await confirmDialog({
            title: meta.confirm.title,
            text: confirmText,
            ok: meta.confirm.ok,
            touch,
        });
        if (!ok) {
            return null;
        }
    }
    const body = { status, ...extra };
    if (comment !== undefined && comment !== null && comment !== '') {
        body.comment = comment;
    }
    try {
        return await patchOperation(operationId, body, errorContainer, onDone, {
            silentCodes: action === 'finish' ? ['DELAY_CODE_REQUIRED'] : [],
        });
    } catch (e) {
        if (e instanceof ApiError && e.code === 'DELAY_CODE_REQUIRED' && action === 'finish') {
            let codes = delayCodes;
            if (!codes) {
                const data = await api.get('/ref/delay-codes', { isActive: 'true' });
                codes = data.items || [];
            }
            const delayCodeId = await delayCodeDialog({
                title: confirmText ? `Завершить: ${confirmText}` : 'Код задержки',
                codes,
                touch,
            });
            if (!delayCodeId) {
                return null;
            }
            return patchOperation(operationId, { ...body, delayCodeId }, errorContainer, onDone);
        }
        throw e;
    }
}

export async function runTurnaroundAction({
    turnaroundId,
    action,
    extra = {},
    confirmText = '',
    touch = true,
    errorContainer = document.getElementById('page'),
    onDone,
} = {}) {
    const meta = TURNAROUND_ACTIONS[action];
    if (!meta) {
        throw new Error(`Неизвестное действие оборота: ${action}`);
    }
    let cancelReason = extra.cancelReason;
    if (meta.needsReason && (cancelReason === undefined || cancelReason === null || cancelReason === '')) {
        cancelReason = await commentDialog({
            title: 'Отмена оборота',
            label: 'Причина отмены (обязательно)',
            ok: 'Отменить',
            touch,
        });
        if (!cancelReason) {
            return null;
        }
    }
    if (meta.confirm) {
        const ok = await confirmDialog({
            title: meta.confirm.title,
            text: confirmText,
            ok: meta.confirm.ok,
            touch,
        });
        if (!ok) {
            return null;
        }
    }
    const body = { ...meta.body, ...extra };
    if (cancelReason) {
        body.cancelReason = cancelReason;
    }
    try {
        const result = await api.patch(`/turnarounds/${turnaroundId}`, body);
        if (onDone) {
            await onDone(result);
        }
        return result;
    } catch (e) {
        showActionError(errorContainer, e);
        throw e;
    }
}

export function bindActionClicks(root, handler) {
    root.addEventListener('click', (e) => {
        const btn = e.target.closest('button[data-action]');
        if (!btn || btn.disabled || !root.contains(btn)) {
            return;
        }
        e.preventDefault();
        handler(btn.dataset.action, btn, e);
    });
}
