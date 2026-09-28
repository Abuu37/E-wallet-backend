package com.application.e_wallet.otp.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record ResendOtpRequest(

        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email format")
        String email
) {
}