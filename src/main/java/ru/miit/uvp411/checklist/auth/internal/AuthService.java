package ru.miit.uvp411.checklist.auth.internal;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import ru.miit.uvp411.checklist.auth.AuthApi;

@Service
class AuthService implements AuthApi {

    private final JdbcClient jdbc;

    AuthService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UserInfo> findUser(String login) {
        return jdbc.sql("""
                        select id, login, last_name, first_name, middle_name, is_active
                        from auth.app_user where login = :login
                        """)
                .param("login", login)
                .query((rs, n) -> new UserInfo(rs.getInt("id"), rs.getString("login"), rs.getString("last_name"),
                        rs.getString("first_name"), rs.getString("middle_name"), rs.getBoolean("is_active")))
                .optional();
    }
}
