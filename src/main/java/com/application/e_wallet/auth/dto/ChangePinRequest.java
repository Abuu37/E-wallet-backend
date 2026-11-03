package com.application.e_wallet.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChangePinRequest {
    @NotBlank( message = "current PIN required")
    private String currentPin;

    @NotBlank( message = "new PIN is required")
    @Pattern(
            regexp = "^\\d{4}$",
            message = "PIN must be exactly 4 digits"
    )
    private String newPin;
}
