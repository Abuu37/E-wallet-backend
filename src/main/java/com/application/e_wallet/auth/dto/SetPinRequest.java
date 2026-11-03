package com.application.e_wallet.auth.dto;

import com.application.e_wallet.common.annotion.PinMatches;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@PinMatches
public class SetPinRequest {

    @NotBlank(message = "Email is required")
    @Email(message = "Please provide a valid email address")
    private String email;

    @NotBlank(message = "PIN is required")
    @Pattern(
            regexp = "^\\d{4}$",
            message = "PIN must be exactly 4 digits"
    )
    private String pin;

    @NotBlank(message = "Confirm PIN is required")
    private String confirmPin;
}
