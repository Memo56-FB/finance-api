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
    void normalizesNameAndEmailButPreservesThePassword() {
        var request = new RegistrationRequest("  María José O'Neill  ", "  MARIA@EXAMPLE.COM  ", "  secret12  ");

        assertThat(validator.validate(request)).isEmpty();
        assertThat(request.name()).isEqualTo("María José O'Neill");
        assertThat(request.email()).isEqualTo("maria@example.com");
        assertThat(request.password()).isEqualTo("  secret12  ");
        assertThat(request.toString()).doesNotContain(request.password(), request.email(), request.name());
    }

    @ParameterizedTest
    @MethodSource("validRequests")
    void acceptsValidBoundaries(RegistrationRequest request) {
        assertThat(validator.validate(request)).isEmpty();
    }

    static Stream<RegistrationRequest> validRequests() {
        return Stream.of(
                new RegistrationRequest("M", "maria@example.com", "abcdefgh"),
                new RegistrationRequest("M".repeat(100), "maria@example.com", "a".repeat(72)),
                new RegistrationRequest("María", emailOfLength(254), "é".repeat(36)));
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void rejectsInvalidFields(String field, RegistrationRequest request) {
        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString()).contains(field);
    }

    static Stream<Arguments> invalidRequests() {
        var blankValues = Stream.<String>of(null, "", " \t ");
        var names = Stream.concat(blankValues, Stream.of("M".repeat(101)))
                .map(name -> Arguments.of("name", new RegistrationRequest(name, "maria@example.com", "abcdefgh")));
        var emails = Stream.of(null, "", " ", "maria", "maria@", "maria example.com", emailOfLength(255))
                .map(email -> Arguments.of("email", new RegistrationRequest("María", email, "abcdefgh")));
        var passwords = Stream.of(null, "", " ".repeat(8), "a".repeat(7), "a".repeat(73), "é".repeat(37))
                .map(password -> Arguments.of(
                        password != null && password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72
                                ? "passwordWithinByteLimit" : "password",
                        new RegistrationRequest("María", "maria@example.com", password)));
        return Stream.concat(Stream.concat(names, emails), passwords);
    }

    private static String emailOfLength(int length) {
        return "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(length - 193);
    }
}
