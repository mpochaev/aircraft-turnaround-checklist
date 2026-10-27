package ru.miit.uvp411.checklist.shared.security;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Режим разработки без Keycloak (профиль dev). Каждый запрос идёт от пользователя из настроек.
 * Другого пользователя можно подставить заголовками, например в Postman:
 * <pre>X-Dev-User: tech07
 * X-Dev-Roles: technician</pre>
 * В рабочем режиме этот фильтр не подключается.
 */
public class DevAuthenticationFilter extends OncePerRequestFilter {

    private final AppSecurityProperties.Dev dev;
    private final PermissionResolver resolver;

    public DevAuthenticationFilter(AppSecurityProperties.Dev dev, PermissionResolver resolver) {
        this.dev = dev;
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String user = header(request, "X-Dev-User", dev.user());
        List<String> roles = request.getHeader("X-Dev-Roles") != null
                ? Arrays.stream(request.getHeader("X-Dev-Roles").split(",")).map(String::trim).toList()
                : dev.roles();
        var auth = new UsernamePasswordAuthenticationToken(user, null, Authorities.of(roles, resolver));
        SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static String header(HttpServletRequest r, String name, String def) {
        String v = r.getHeader(name);
        return v == null || v.isBlank() ? def : v.trim();
    }
}
