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
            jdbc.update("INSERT INTO users (username, password, enabled, name) VALUES (?, ?, true, ?)",
                    request.email(), hash, request.name());
        } catch (DuplicateKeyException exception) {
            throw new EmailAlreadyRegisteredException();
        }
        return new RegisteredUser(request.name(), request.email());
    }

    record RegisteredUser(String name, String email) {
    }

    static class EmailAlreadyRegisteredException extends RuntimeException {
    }
}
