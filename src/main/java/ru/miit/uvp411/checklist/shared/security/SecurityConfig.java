package ru.miit.uvp411.checklist.shared.security;

import java.io.IOException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

import jakarta.servlet.http.HttpServletResponse;
import ru.miit.uvp411.checklist.shared.error.ApiError;
import ru.miit.uvp411.checklist.shared.web.RequestIdFilter;

/**
 * Кто куда может зайти.
 * <ul>
 *   <li>/api/** только с токеном (в режиме DEV токен не нужен);</li>
 *   <li>страницы, стили и скрипты открыты всем: страница сама отправляет на вход в Keycloak;</li>
 *   <li>конкретное право проверяется на методе контроллера: {@code @PreAuthorize("hasAuthority('ref.read')")}.</li>
 * </ul>
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, AppSecurityProperties props, PermissionResolver resolver) throws Exception {
        http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/v1/config", "/actuator/health/**").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(unauthorized())
                        .accessDeniedHandler(forbidden()));

        if (props.mode() == AppSecurityProperties.Mode.DEV) {
            http.addFilterBefore(new DevAuthenticationFilter(props.dev(), resolver), AnonymousAuthenticationFilter.class);
        } else {
            http.oauth2ResourceServer(o -> o
                    .jwt(j -> j.jwtAuthenticationConverter(new KeycloakJwtConverter(resolver)))
                    .authenticationEntryPoint(unauthorized()));
        }
        return http.build();
    }

    private static AuthenticationEntryPoint unauthorized() {
        return (req, res, ex) -> write(res, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Нужно войти в систему");
    }

    private static AccessDeniedHandler forbidden() {
        return (req, res, ex) -> write(res, HttpStatus.FORBIDDEN, "FORBIDDEN", "Недостаточно прав");
    }

    private static void write(HttpServletResponse res, HttpStatus status, String code, String message) throws IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write(ApiError.of(code, message, null, RequestIdFilter.current()).toJson());
    }
}
