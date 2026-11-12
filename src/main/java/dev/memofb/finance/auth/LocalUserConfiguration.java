package dev.memofb.finance.auth;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;

@Configuration(proxyBeanMethods = false)
@Profile("local")
class LocalUserConfiguration {

    @Bean
    ApplicationRunner localUser(JdbcUserDetailsManager users, PasswordEncoder passwords,
            TransactionTemplate transactions,
            @Value("${finance.bootstrap.email}") String email,
            @Value("${finance.bootstrap.password}") String password) {
        var username = email.strip().toLowerCase(Locale.ROOT);
        Assert.hasText(username, "FINANCE_BOOTSTRAP_EMAIL must not be blank");
        Assert.isTrue(username.length() <= 254, "FINANCE_BOOTSTRAP_EMAIL is too long");
        Assert.hasText(password, "FINANCE_BOOTSTRAP_PASSWORD must not be blank");
        Assert.isTrue(password.length() >= 12 && password.getBytes(StandardCharsets.UTF_8).length <= 72,
                "FINANCE_BOOTSTRAP_PASSWORD must have at least 12 characters and at most 72 UTF-8 bytes");

        return args -> transactions.executeWithoutResult(status -> {
            if (!users.userExists(username)) {
                users.createUser(User.withUsername(username)
                        .password(passwords.encode(password))
                        .roles("USER")
                        .build());
            }
        });
    }
}
