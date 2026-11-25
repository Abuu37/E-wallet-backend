package com.application.e_wallet.agent.dto;

import com.application.e_wallet.agent.entity.Gender;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class AgentCreationRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private AgentCreationRequest request(String middleName) {
        return new AgentCreationRequest("Rachel", middleName, "Saho", "rachel@example.com", "+255712616240",
                Gender.FEMALE, LocalDate.of(1990, 1, 1), "TZ07", "TZ07-01", "Saho Shop", "123456789");
    }

    @Test
    void aMiddleNameIsOptional() {
        assertThat(validator.validate(request(null))).extracting(v -> v.getPropertyPath().toString()).doesNotContain("middleName");
    }

    @Test
    void anOverlongMiddleNameIsStillRejected() {
        assertThat(validator.validate(request("x".repeat(101)))).extracting(v -> v.getPropertyPath().toString()).contains("middleName");
    }
}
