package com.application.e_wallet.common.validation;

import com.application.e_wallet.auth.dto.SetPinRequest;
import com.application.e_wallet.common.annotion.PinMatches;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class PinMatchesValidator implements ConstraintValidator<PinMatches, SetPinRequest> {

    @Override
    public void initialize(PinMatches constraintAnnotation) {
        //no initialization required
    }

    @Override
    public boolean isValid(SetPinRequest request, ConstraintValidatorContext context) {

        String pin = request.getPin();
        String confirmPin = request.getConfirmPin();

        if (pin == null || confirmPin == null) {
            return true;
        }

        boolean isValid = pin.equals(confirmPin);

        if (!isValid) {
            context.disableDefaultConstraintViolation();

            context.buildConstraintViolationWithTemplate("Pins do not match").addConstraintViolation();
        }

        return isValid;

    }
}
