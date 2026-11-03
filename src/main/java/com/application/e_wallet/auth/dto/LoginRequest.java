package com.application.e_wallet.auth.dto;

import com.application.e_wallet.common.annotion.ValidPhoneNumber;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoginRequest {

    @NotBlank(message = "Phone number is required")
    @ValidPhoneNumber
    private String phone;

    @NotBlank(message = "PIN is required")
    @Pattern(
            regexp = "^\\d{4}$",
            message = "PIN must be exactly 4 digits"
    )
    private String pin;
}
