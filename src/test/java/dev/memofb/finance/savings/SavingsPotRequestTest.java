package dev.memofb.finance.savings;

import java.math.BigDecimal;
import java.util.stream.Stream;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class SavingsPotRequestTest {

    static final ValidatorFactory factory = Validation.buildDefaultValidatorFactory();

    @AfterAll
    static void close() {
        factory.close();
    }

    @Test
    void normalizesTextAndPreservesAccentsAndMoney() {
        var request = new SavingsPotRequest("  Viaje a México  ", new BigDecimal("0.01"), " #aabbcc ");
        assertThat(factory.getValidator().validate(request)).isEmpty();
        assertThat(request.name()).isEqualTo("Viaje a México");
        assertThat(request.color()).isEqualTo("#AABBCC");
        assertThat(request.targetAmount()).isEqualByComparingTo("0.01");
        assertThat(factory.getValidator().validate(new SavingsPotRequest("é".repeat(100),
                new BigDecimal("99999999999999999.99"), "#FFFFFF"))).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void rejectsInvalidFields(String field, SavingsPotRequest request) {
        assertThat(factory.getValidator().validate(request)).anySatisfy(error ->
                assertThat(error.getPropertyPath().toString()).isEqualTo(field));
    }

    static Stream<Arguments> invalidRequests() {
        var names = Stream.of(null, "", "   ", "x".repeat(101), "😀".repeat(51))
                .map(name -> Arguments.of("name", new SavingsPotRequest(name, BigDecimal.ONE, "#FFFFFF")));
        var amounts = Stream.of(null, "0", "-1", "0.001", "100000000000000000.00")
                .map(value -> Arguments.of("targetAmount", new SavingsPotRequest("Valid",
                        value == null ? null : new BigDecimal(value), "#FFFFFF")));
        var colors = Stream.of(null, "", "red", "#FFF", "#GGGGGG", "#FFFFFFFF", "FFFFFF")
                .map(color -> Arguments.of("color", new SavingsPotRequest("Valid", BigDecimal.ONE, color)));
        return Stream.of(names, amounts, colors).flatMap(stream -> stream);
    }
}
