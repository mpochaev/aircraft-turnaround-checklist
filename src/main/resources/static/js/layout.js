// Общая обвязка страницы: вход, шапка с меню по правам, вывод ошибок.
//   const me = await startPage({ active: 'turnarounds' });
import { initAuth, logout } from './auth.js';
import { api, ApiError } from './api.js';
import { escapeHtml } from './format.js';

// Пункты меню. Пункт виден, если у пользователя есть право perm (и нет права hideIf).
const NAV = [
    { id: 'turnarounds', href: '/supervisor/turnarounds.html', text: 'Обороты', perm: 'turnarounds.read_all' },
    { id: 'my', href: '/technician/my-turnarounds.html', text: 'Мои обороты', perm: 'turnarounds.service', hideIf: 'turnarounds.read_all' },
    { id: 'ref', href: '/ref/index.html', text: 'Справочники', perm: 'ref.write' },
    { id: 'users', href: '/users/index.html', text: 'Пользователи', perm: 'users.read' },
    { id: 'log', href: '/log/index.html', text: 'Журнал', perm: 'log.read_all' },
    { id: 'history', href: '/history/index.html', text: 'История', perm: 'metrics.read' },
    { id: 'profile', href: '/profile.html', text: 'Профиль' },
];

export function can(me, perm) {
    return !perm || me.permissions.includes(perm);
}

export async function startPage({ active } = {}) {
    await initAuth();
    const me = await api.get('/me');
    const header = document.querySelector('header.app-header') || document.body.insertAdjacentElement('afterbegin', document.createElement('header'));
    header.className = 'app-header';
    const items = NAV.filter((n) => can(me, n.perm) && !(n.hideIf && can(me, n.hideIf)));
    const roleNames = { technician: 'техник', supervisor: 'супервайзер' };
    const roles = me.roles.map((r) => roleNames[r] || r).join(', ');
    header.innerHTML = `
        <nav class="app-nav">${items.map((n) => `<a href="${n.href}" class="${n.id === active ? 'active' : ''}">${n.text}</a>`).join('')}</nav>
        <div class="app-user">
            <span id="header-counters"></span>
            <span>${escapeHtml(me.shortName)} (${escapeHtml(roles)})</span>
            <span class="sep">|</span>
            <a href="#" id="logout">Выход</a>
        </div>`;
    header.querySelector('#logout').addEventListener('click', (e) => { e.preventDefault(); logout(); });
    return me;
}

// Ошибка API или любая другая: показываем текст и requestId, по которому её можно найти в логах сервера.
export function showError(container, err) {
    const box = document.createElement('div');
    box.className = 'alert alert-error';
    const rid = err instanceof ApiError && err.requestId ? `<div class="request-id">requestId: ${escapeHtml(err.requestId)}</div>` : '';
    box.innerHTML = `${escapeHtml(err.message || String(err))}${rid}`;
    container.prepend(box);
    setTimeout(() => box.remove(), 10000);
}

// Повтор каждые seconds секунд (таблицы обновляются раз в 15 секунд, ФТ-52).
export function every(seconds, fn) {
    fn();
    return setInterval(fn, seconds * 1000);
}
