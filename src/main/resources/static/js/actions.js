import { api } from './api.js';
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
        confirm: { title: 'Сообщить о проблеме?', ok: 'Сообщить' },
    },
    resolve: {
        label: 'Решено',
        status: 'in_progress',
        needsComment: true,
        confirm: { title: 'Отметить проблему как решённую?', ok: 'Решено' },
    },
    not_required: {
        label: 'Не требуется',
        status: 'not_required',
        needsComment: true,
        confirm: { title: 'Отметить «Не требуется»?', ok: 'Подтвердить' },
    },
    revert: {
        label: 'Исправить',
        needsComment: true,
        confirm: { title: 'Исправить отметку?', ok: 'Исправить' },
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

export async function runOperationAction({
    operationId,
    action,
    operation = {},
    extra = {},
    confirmText = '',
    touch = true,
    errorContainer = document.getElementById('page'),
    onDone,
} = {}) {
    const meta = OPERATION_ACTIONS[action];
    if (!meta) {
        throw new Error(`Неизвестное действие операции: ${action}`);
    }
    const status = meta.statusFor ? meta.statusFor(operation) : meta.status;
    let comment = extra.comment;
    if (meta.needsComment && (comment === undefined || comment === null || comment === '')) {
        comment = window.prompt('Комментарий (обязательно):') || '';
        if (!comment.trim()) {
            if (errorContainer) {
                showError(errorContainer, new Error('Нужен комментарий'));
            }
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
    const body = { status, ...extra };
    if (comment !== undefined && comment !== null && comment !== '') {
        body.comment = comment;
    }
    try {
        const result = await api.patch(`/operations/${operationId}`, body);
        if (onDone) {
            await onDone(result);
        }
        return result;
    } catch (e) {
        if (errorContainer) {
            showError(errorContainer, e);
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
        cancelReason = window.prompt('Причина отмены (обязательно):') || '';
        if (!cancelReason.trim()) {
            if (errorContainer) {
                showError(errorContainer, new Error('Нужна причина отмены'));
            }
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
        if (errorContainer) {
            showError(errorContainer, e);
        }
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
