function applicationOriginFromLoginRequest() {
    const parameters = new URLSearchParams(location.search);
    let redirectUri = parameters.get('redirect_uri');

    if (!redirectUri) {
        const clientData = parameters.get('client_data');
        if (clientData) {
            const base64 = clientData.replace(/-/g, '+').replace(/_/g, '/');
            const paddedBase64 = base64.padEnd(Math.ceil(base64.length / 4) * 4, '=');
            redirectUri = JSON.parse(atob(paddedBase64)).ru;
        }
    }

    if (typeof redirectUri !== 'string' || !redirectUri) {
        return null;
    }

    const url = new URL(redirectUri);
    return ['http:', 'https:'].includes(url.protocol) ? url.origin : null;
}

function initPasswordReset() {
    const form = document.getElementById('checklist-reset-form');

    if (!form) {
        return;
    }

    const status = document.getElementById('checklist-reset-status');

    form.addEventListener('submit', async (event) => {
        event.preventDefault();
        status.textContent = '';

        let applicationOrigin;
        try {
            applicationOrigin = applicationOriginFromLoginRequest();
        } catch (error) {
            console.error('Could not resolve application origin from Keycloak login request:', error);
        }
        if (!applicationOrigin) {
            status.textContent = 'Не удалось определить адрес приложения. Повторите попытку со страницы входа.';
            return;
        }

        const endpoint = new URL('/api/v1/auth/password-reset', applicationOrigin);
        const submit = form.querySelector('button[type="submit"]');
        submit.disabled = true;
        try {
            const response = await fetch(endpoint, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    login: new FormData(form).get('login'),
                    reason: 'Заявка со страницы входа',
                }),
            });
            const result = await response.json();
            if (!response.ok) {
                throw new Error(result?.error?.message || `HTTP ${response.status}`);
            }
            status.textContent = result.message || 'Заявка отправлена супервайзеру.';
            form.reset();
        } catch (error) {
            status.textContent = 'Не удалось отправить заявку. Попробуйте позже.';
            console.error('Password reset request failed:', error);
        } finally {
            submit.disabled = false;
        }
    });
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initPasswordReset, { once: true });
} else {
    initPasswordReset();
}
