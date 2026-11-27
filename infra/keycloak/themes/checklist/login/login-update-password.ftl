<#import "template.ftl" as layout>
<#import "field.ftl" as field>
<#import "password-commons.ftl" as passwordCommons>
<#import "password-validation.ftl" as validator>
<#import "buttons.ftl" as buttons>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('password','password-confirm'); section>
    <#if section = "header">
        <div class="checklist-heading">Смена пароля</div>
    <#elseif section = "form">
        <p class="checklist-password-intro">Задайте новый пароль для продолжения работы.</p>
        <form id="kc-passwd-update-form" class="${properties.kcFormClass!} checklist-password-form"
              onsubmit="login.disabled = true; return true;" action="${url.loginAction}" method="post" novalidate="novalidate">
            <@field.password name="password-new" label="Новый пароль" fieldName="password"
                autocomplete="new-password" autofocus=true />
            <@field.password name="password-confirm" label="Повторите новый пароль"
                autocomplete="new-password" />

            <div class="checklist-password-options">
                <@passwordCommons.logoutOtherSessions />
            </div>

            <div class="checklist-password-actions">
                <@buttons.actionGroup horizontal=true>
                    <#if isAppInitiatedAction??>
                        <@buttons.button id="kc-submit" name="login" label="doSubmit" />
                        <@buttons.button id="kc-cancel" label="doCancel" name="cancel-aia" type="secondary" />
                    <#else>
                        <@buttons.button id="kc-submit" name="login" label="doSubmit" />
                    </#if>
                </@buttons.actionGroup>
            </div>
        </form>

        <@validator.templates />
        <@validator.script field="password-new" />
    </#if>
</@layout.registrationLayout>
