package com.application.e_wallet.otp.dto;

import jakarta.validation.constraints.NotBlank;

public record ResendOtpRequest(
        @NotBlank(message = "Phone number is required")
        String phone
) {
}
