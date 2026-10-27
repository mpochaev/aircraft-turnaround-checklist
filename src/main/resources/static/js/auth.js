// Вход в систему. Режим берётся с сервера (/api/v1/config):
//   keycloak: страница отправляет пользователя на вход в Keycloak и получает токен;
//   dev:      токен не нужен, сервер считает пользователем sup01 (профиль dev).
let keycloak = null;
let config = null;

export async function initAuth() {
    if (config) {
        return config;
    }
    config = await (await fetch('/api/v1/config')).json();
    if (config.securityMode === 'keycloak') {
        const { default: Keycloak } = await import('/vendor/keycloak.js');
        keycloak = new Keycloak({
            url: config.keycloak.url,
            realm: config.keycloak.realm,
            clientId: config.keycloak.clientId,
        });
        await keycloak.init({ onLoad: 'login-required', pkceMethod: 'S256', checkLoginIframe: false });
    }
    return config;
}

// Заголовок с токеном для запроса к API. Токен живёт 5 минут, перед запросом продлеваем его.
export async function authHeaders() {
    if (!keycloak) {
        return {};
    }
    try {
        await keycloak.updateToken(30);
    } catch (e) {
        await keycloak.login();
    }
    return { Authorization: `Bearer ${keycloak.token}` };
}

export function logout() {
    if (keycloak) {
        keycloak.logout({ redirectUri: `${location.origin}/` });
    } else {
        location.href = '/';
    }
}

export function login() {
    if (keycloak) {
        keycloak.login();
    } else {
        location.reload();
    }
}
