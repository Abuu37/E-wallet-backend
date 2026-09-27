package com.application.e_wallet.common.validation;

import com.application.e_wallet.auth.dto.CustomerRegistrationRequest;
import com.application.e_wallet.common.annotion.PasswordMatches;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class PasswordMatchesValidator implements ConstraintValidator<PasswordMatches, CustomerRegistrationRequest> {

    @Override
    public boolean isValid(CustomerRegistrationRequest request, ConstraintValidatorContext context) {

        if (request.getPassword() == null || request.getConfirmPassword() == null) {
            return true;
        }

        return request.getPassword().equals(request.getConfirmPassword());
    }
}
