package com.application.e_wallet.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** A customer proves who they are with the phone number and the last 4 digits of their NIDA number. */
public record ForgotPinRequest(
        @NotBlank(message = "Phone number is required")
        String phone,

        @NotBlank(message = "The last 4 digits of your NIDA number are required")
        @Pattern(regexp = "^\\d{4}$", message = "Enter the last 4 digits of your NIDA number")
        String nidaLast4
) {
}
