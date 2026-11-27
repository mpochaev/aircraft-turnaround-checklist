<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=false; section>
    <#if section = "header">
        <div class="checklist-heading">Цифровой чек-лист подготовки ВС к вылету</div>
    <#elseif section = "form">
        <main class="checklist-login">
            <form id="kc-form-login" action="${url.loginAction}" method="post">
                <div class="checklist-field">
                    <label for="username">Логин</label>
                    <input id="username" name="username" value="${(login.username!'')}" type="text"
                           autocomplete="username" autofocus required minlength="3" maxlength="50" dir="ltr">
                </div>
                <div class="checklist-field">
                    <label for="password">Пароль</label>
                    <input id="password" name="password" type="password"
                           autocomplete="current-password" required>
                </div>
                <button id="kc-login" name="login" type="submit">Войти</button>
            </form>

            <#if message?has_content>
                <div class="checklist-message" role="alert">${kcSanitize(message.summary)?no_esc}</div>
            </#if>

            <a class="checklist-forgot" href="#checklist-password-reset">Забыли пароль?</a>

            <section class="checklist-reset" id="checklist-password-reset">
                <h2>Заявка на сброс пароля</h2>
                <form id="checklist-reset-form">
                    <div class="checklist-reset-row">
                        <label for="reset-login">Логин</label>
                        <div class="checklist-reset-controls">
                            <input id="reset-login" name="login" type="text" autocomplete="username"
                                   minlength="3" maxlength="50" required>
                            <button type="submit">Отправить заявку</button>
                        </div>
                    </div>
                    <p class="checklist-reset-status" id="checklist-reset-status" aria-live="polite"></p>
                </form>
            </section>
        </main>
    </#if>
</@layout.registrationLayout>
