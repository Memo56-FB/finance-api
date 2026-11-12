package dev.memofb.finance.auth;

import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalUserConfigurationTest {

    private final LocalUserConfiguration configuration = new LocalUserConfiguration();
    private final JdbcUserDetailsManager users = mock(JdbcUserDetailsManager.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final PasswordEncoder passwords =
            PasswordEncoderFactories.createDelegatingPasswordEncoder();

    @BeforeEach
    void runTransactionCallbacks() {
        doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactions).executeWithoutResult(any());
    }

    @Test
    void createsTheUserWithAnEncodedPasswordAndDoesNotOverwriteItOnRestart() throws Exception {
        when(users.userExists("memo@example.com")).thenReturn(false, true);
        var runner = configuration.localUser(users, passwords, transactions,
                "  Memo@Example.com  ", "local-test-passphrase");

        runner.run(null);
        runner.run(null);

        var captured = ArgumentCaptor.forClass(UserDetails.class);
        verify(users, times(1)).createUser(captured.capture());
        assertThat(captured.getValue().getUsername()).isEqualTo("memo@example.com");
        assertThat(captured.getValue().getPassword()).startsWith("{bcrypt}");
        assertThat(passwords.matches("local-test-passphrase", captured.getValue().getPassword())).isTrue();
    }

    @Test
    void rejectsBlankOrOversizedEmailAndUnsafeBootstrapPasswords() {
        assertThatIllegalArgumentException().isThrownBy(() -> configuration.localUser(
                users, passwords, transactions, " ", "local-test-passphrase"));
        assertThatIllegalArgumentException().isThrownBy(() -> configuration.localUser(
                users, passwords, transactions, "a".repeat(255), "local-test-passphrase"));
        assertThatIllegalArgumentException().isThrownBy(() -> configuration.localUser(
                users, passwords, transactions, "memo@example.com", "short"));
        assertThatIllegalArgumentException().isThrownBy(() -> configuration.localUser(
                users, passwords, transactions, "memo@example.com", " ".repeat(12)));
        assertThatIllegalArgumentException().isThrownBy(() -> configuration.localUser(
                users, passwords, transactions, "memo@example.com", "é".repeat(40)));
    }
}
