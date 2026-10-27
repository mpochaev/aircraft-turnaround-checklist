// Вызовы API (/api/v1). Ошибка сервера превращается в ApiError с полями из раздела 2.4 спецификации:
//   try { await api.patch(`/operations/${id}`, { status: 'done' }) }
//   catch (e) { if (e.code === 'DELAY_CODE_REQUIRED') ... }
import { authHeaders, login } from './auth.js';

export class ApiError extends Error {
    constructor(status, body) {
        super(body?.error?.message || `Ошибка ${status}`);
        this.status = status;
        this.code = body?.error?.code || 'UNKNOWN';
        this.details = body?.error?.details || [];
        this.requestId = body?.requestId || null;
    }
}

async function request(method, path, { params, body, background } = {}) {
    const url = new URL(`/api/v1${path}`, location.origin);
    Object.entries(params || {}).forEach(([k, v]) => {
        if (v !== undefined && v !== null && v !== '') {
            url.searchParams.set(k, v);
        }
    });
    const headers = { ...(await authHeaders()) };
    if (body !== undefined) {
        headers['Content-Type'] = 'application/json';
    }
    if (background) {
        headers['X-Background'] = 'true';   // автообновление не продлевает сессию (ФТ-52)
    }
    const res = await fetch(url, { method, headers, body: body !== undefined ? JSON.stringify(body) : undefined });
    if (res.status === 401) {
        login();
    }
    if (res.status === 204) {
        return null;
    }
    const data = await res.json().catch(() => null);
    if (!res.ok) {
        throw new ApiError(res.status, data);
    }
    return data;
}

export const api = {
    get: (path, params, opts) => request('GET', path, { params, ...opts }),
    post: (path, body) => request('POST', path, { body }),
    patch: (path, body) => request('PATCH', path, { body }),
};
