package dev.memofb.finance.auth;

import java.util.stream.Stream;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class RegistrationRequestTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    void normalizesUsernameButPreservesThePassword() {
        var request = new RegistrationRequest("  MARIA  ", "  secret12  ");

        assertThat(validator.validate(request)).isEmpty();
        assertThat(request.username()).isEqualTo("maria");
        assertThat(request.password()).isEqualTo("  secret12  ");
        assertThat(request.toString()).doesNotContain(request.password(), request.username());
    }

    @ParameterizedTest
    @MethodSource("validRequests")
    void acceptsValidBoundaries(RegistrationRequest request) {
        assertThat(validator.validate(request)).isEmpty();
    }

    static Stream<RegistrationRequest> validRequests() {
        return Stream.of(
                new RegistrationRequest("maria", "abcdefgh"),
                new RegistrationRequest("maria", "a".repeat(72)),
                new RegistrationRequest("a".repeat(254), "é".repeat(36)));
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void rejectsInvalidFields(String field, RegistrationRequest request) {
        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString()).contains(field);
    }

    static Stream<Arguments> invalidRequests() {
        var usernames = Stream.of(null, "", " ", "maria@example.com", "maria@", "maria example.com", "a".repeat(255))
                .map(username -> Arguments.of("username", new RegistrationRequest(username, "abcdefgh")));
        var passwords = Stream.of(null, "", " ".repeat(8), "a".repeat(7), "a".repeat(73), "é".repeat(37))
                .map(password -> Arguments.of(
                        password != null && password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72
                                ? "passwordWithinByteLimit" : "password",
                        new RegistrationRequest("maria", password)));
        return Stream.concat(usernames, passwords);
    }

}
