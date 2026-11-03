package com.application.e_wallet.auth.dto;

import com.application.e_wallet.common.annotion.ValidPhoneNumber;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CustomerRegistrationRequest {

    @NotBlank(message = "First name is required")
    @Size(min = 1, max = 100, message = "First name must be between 1 and 100 characters")
    @Pattern(
            regexp = "^[\\p{L}\\s'-]+$",
            message = "First name can only contain letters, spaces, hyphens, and apostrophes"
    )
    private String firstName;

    @Size(min = 1, max = 100, message = "Middle name must be between 1 and 100 characters")
    @Pattern(
            regexp = "^[\\p{L}\\s'-]+$",
            message = "Middle name can only contain letters, spaces, hyphens, and apostrophes"
    )
    private String middleName;

    @NotBlank(message = "Last name is required")
    @Pattern(
            regexp = "^[\\p{L}\\s'-]+$",
            message = "Last name can only contain letters, spaces, hyphens, and apostrophes"
    )
    @Size(min = 1, max = 100, message = "Last name must be between 1 and 100 characters")
    private String lastName;

    @NotBlank(message = "Email is required")
    @Size(max = 255, message = "Email cannot exceed 255 characters")
    @Email(message = "Please provide a valid email address")
    private String email;

    @NotBlank(message = "Phone number is required")
    @ValidPhoneNumber
    private String phone;
}
