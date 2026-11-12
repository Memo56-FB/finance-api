package dev.memofb.finance.auth;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class RegistrationService {

    private final JdbcUserDetailsManager users;
    private final PasswordEncoder passwords;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    RegistrationService(JdbcUserDetailsManager users, PasswordEncoder passwords,
            JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.users = users;
        this.passwords = passwords;
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    RegisteredUser register(RegistrationRequest request) {
        var hash = passwords.encode(request.password());
        transactions.executeWithoutResult(status -> {
            try {
                users.createUser(User.withUsername(request.email()).password(hash).roles("USER").build());
            } catch (DuplicateKeyException exception) {
                throw new EmailAlreadyRegisteredException();
            }
            jdbc.update("UPDATE users SET name = ? WHERE username = ?", request.name(), request.email());
        });
        return new RegisteredUser(request.name(), request.email());
    }

    record RegisteredUser(String name, String email) {
    }

    static class EmailAlreadyRegisteredException extends RuntimeException {
    }
}
