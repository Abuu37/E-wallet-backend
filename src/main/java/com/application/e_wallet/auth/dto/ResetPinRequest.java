package com.application.e_wallet.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ResetPinRequest(

        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email format")
        String email,

        @NotBlank(message = "OTP is required")
        @Size(min = 6, max = 6, message = "OTP must be 6 digits")
        @Pattern(
                regexp = "\\d{6}",
                message = "OTP must contain only digits"
        )
        String otp,

        @NotBlank(message = "New PIN is required")
        @Pattern(
                regexp = "^\\d{4}$",
                message = "PIN must be exactly 4 digits"
        )
        String newPin

) {
}
