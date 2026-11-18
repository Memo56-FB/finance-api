package dev.memofb.finance.shared;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class FinancialLock {
    private final JdbcTemplate jdbc;

    public FinancialLock(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void acquire(String username) {
        jdbc.queryForObject("SELECT username FROM users WHERE username = ? FOR UPDATE", String.class, username);
    }
}
