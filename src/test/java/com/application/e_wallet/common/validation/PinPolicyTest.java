package com.application.e_wallet.common.validation;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PinPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"0000", "1111", "9999", "1234", "2345", "4321", "3210", "0123", "7890", "9012", "1212", "2121", "1122"})
    void rejectsRepeatedSequentialAndPatternedPins(String pin) {
        assertThat(PinPolicy.isWeak(pin)).as(pin).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"4829", "1357", "2468", "9031", "7406"})
    void acceptsOrdinaryPins(String pin) {
        assertThat(PinPolicy.isWeak(pin)).as(pin).isFalse();
    }
}
