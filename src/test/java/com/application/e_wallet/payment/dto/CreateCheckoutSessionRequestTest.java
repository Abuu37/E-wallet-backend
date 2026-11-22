package com.application.e_wallet.payment.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CreateCheckoutSessionRequestTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void acceptsAmountAtTheUpperBoundary() {
        Set<ConstraintViolation<CreateCheckoutSessionRequest>> violations =
                validator.validate(new CreateCheckoutSessionRequest(new BigDecimal("999999.99")));

        assertThat(violations).isEmpty();
    }

    @Test
    void rejectsAmountAboveStripesEightDigitMinorUnitLimit() {
        Set<ConstraintViolation<CreateCheckoutSessionRequest>> violations =
                validator.validate(new CreateCheckoutSessionRequest(new BigDecimal("1000000.00")));

        // 1,000,000.00 has 7 integer digits, so it also trips @Digits(integer = 6) in addition
        // to @DecimalMax - both constraints are expected to fire together at this boundary.
        assertThat(violations)
                .extracting(ConstraintViolation::getMessage)
                .contains("Maximum top-up amount is 999,999.99");
    }

    @Test
    void rejectsAmountWithMoreThanTwoDecimalPlaces() {
        Set<ConstraintViolation<CreateCheckoutSessionRequest>> violations =
                validator.validate(new CreateCheckoutSessionRequest(new BigDecimal("1000.005")));

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage())
                .isEqualTo("Amount must have at most 2 decimal places");
    }

    @Test
    void acceptsAmountWithExactlyTwoDecimalPlaces() {
        Set<ConstraintViolation<CreateCheckoutSessionRequest>> violations =
                validator.validate(new CreateCheckoutSessionRequest(new BigDecimal("1000.00")));

        assertThat(violations).isEmpty();
    }

    @Test
    void stillRejectsAmountBelowMinimum() {
        Set<ConstraintViolation<CreateCheckoutSessionRequest>> violations =
                validator.validate(new CreateCheckoutSessionRequest(new BigDecimal("500.00")));

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage())
                .isEqualTo("Minimum top-up amount is 1,000 TZS");
    }

    @Test
    void rejectsNullAmount() {
        Set<ConstraintViolation<CreateCheckoutSessionRequest>> violations =
                validator.validate(new CreateCheckoutSessionRequest(null));

        assertThat(violations).hasSize(1);
    }
}
