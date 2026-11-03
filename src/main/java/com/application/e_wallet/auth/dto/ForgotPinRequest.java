package com.application.e_wallet.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record ForgotPinRequest(

        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email format")
        String email

) {
}
