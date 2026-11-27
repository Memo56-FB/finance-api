package dev.memofb.finance.auth;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
class RegistrationService {

    private final PasswordEncoder passwords;
    private final JdbcTemplate jdbc;
    RegistrationService(PasswordEncoder passwords, JdbcTemplate jdbc) {
        this.passwords = passwords;
        this.jdbc = jdbc;
    }

    RegisteredUser register(RegistrationRequest request) {
        var hash = passwords.encode(request.password());
        try {
            jdbc.update("INSERT INTO users (username, password, enabled) VALUES (?, ?, true)",
                    request.username(), hash);
        } catch (DuplicateKeyException exception) {
            throw new UsernameAlreadyRegisteredException();
        }
        return new RegisteredUser(request.username());
    }

    record RegisteredUser(String username) {
    }

    static class UsernameAlreadyRegisteredException extends RuntimeException {
    }
}
