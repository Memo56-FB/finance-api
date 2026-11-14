package dev.memofb.finance.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;

class LocalUserConfigurationTest {

    private final LocalUserConfiguration configuration = new LocalUserConfiguration();
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PasswordEncoder passwords =
            PasswordEncoderFactories.createDelegatingPasswordEncoder();

    @Test
    void rejectsBlankOrOversizedEmailAndUnsafeBootstrapPasswords() {
        assertThatIllegalArgumentException().isThrownBy(() -> configuration.localUser(
                jdbc, passwords, " ", "local-test-passphrase"));
        assertThatIllegalArgumentException().isThrownBy(() -> configuration.localUser(
                jdbc, passwords, "a".repeat(255), "local-test-passphrase"));
        assertThatIllegalArgumentException().isThrownBy(() -> configuration.localUser(
                jdbc, passwords, "memo@example.com", "short"));
        assertThatIllegalArgumentException().isThrownBy(() -> configuration.localUser(
                jdbc, passwords, "memo@example.com", " ".repeat(12)));
        assertThatIllegalArgumentException().isThrownBy(() -> configuration.localUser(
                jdbc, passwords, "memo@example.com", "é".repeat(40)));
    }
}
