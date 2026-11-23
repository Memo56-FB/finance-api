package dev.memofb.finance.auth;

import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.Assert;

@Configuration(proxyBeanMethods = false)
@Profile("local")
class LocalUserConfiguration {

    @Bean
    ApplicationRunner localUser(JdbcTemplate jdbc, PasswordEncoder passwords,
            @Value("${finance.bootstrap.username}") String configuredUsername,
            @Value("${finance.bootstrap.password}") String password) {
        var username = RegistrationRequest.normalizeUsername(configuredUsername);
        Assert.isTrue(username != null && username.matches(RegistrationRequest.USERNAME_PATTERN),
                "FINANCE_BOOTSTRAP_USERNAME must contain only letters, numbers, dots, underscores or hyphens");
        Assert.isTrue(username.length() <= 254, "FINANCE_BOOTSTRAP_USERNAME is too long");
        Assert.hasText(password, "FINANCE_BOOTSTRAP_PASSWORD must not be blank");
        Assert.isTrue(password.length() >= 12 && password.getBytes(StandardCharsets.UTF_8).length <= 72,
                "FINANCE_BOOTSTRAP_PASSWORD must have at least 12 characters and at most 72 UTF-8 bytes");

        return args -> jdbc.update("INSERT INTO users (username, password, enabled) VALUES (?, ?, true) "
                + "ON CONFLICT (username) DO NOTHING", username, passwords.encode(password));
    }
}
