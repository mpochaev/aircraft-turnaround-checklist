package ru.miit.uvp411.checklist.auth.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ru.miit.uvp411.checklist.auth.AuthApi;
import ru.miit.uvp411.checklist.shared.security.CurrentUser;

/**
 * GET /me: данные, роли и права текущего пользователя (раздел 5.4 спецификации API).
 * Страницы вызывают его после входа и по списку прав решают, какие пункты меню и кнопки показать.
 */
@RestController
@RequestMapping("/api/v1")
class MeController {

    private final AuthApi auth;

    MeController(AuthApi auth) {
        this.auth = auth;
    }

    @GetMapping("/me")
    Map<String, Object> me() {
        CurrentUser user = CurrentUser.get();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("login", user.login());
        auth.findUser(user.login()).ifPresentOrElse(u -> {
            body.put("id", u.id());
            body.put("lastName", u.lastName());
            body.put("firstName", u.firstName());
            body.put("middleName", u.middleName());
            body.put("shortName", u.shortName());
        }, () -> body.put("shortName", user.login()));
        body.put("roles", user.roles());
        body.put("permissions", user.permissions());
        return body;
    }
}
