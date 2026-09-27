package com.application.e_wallet.common.validation;

import com.application.e_wallet.common.annotion.ValidPhoneNumber;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class PhoneNumberValidator implements ConstraintValidator<ValidPhoneNumber, String> {

    private String defaultRegion;
    private final PhoneNumberUtil phoneNumberUtil = PhoneNumberUtil.getInstance();

    @Override
    public void initialize(ValidPhoneNumber constraintAnnotation) {
        this.defaultRegion = constraintAnnotation.defaultRegion();
    }

    public boolean isValid(String phoneField, ConstraintValidatorContext context) {

        if (phoneField == null || phoneField.isBlank()) {
            return true;
        }

        try{
            PhoneNumber phoneNumber = phoneNumberUtil.parse(phoneField, defaultRegion);
            return phoneNumberUtil.isValidNumber(phoneNumber);
        } catch (NumberParseException e) {
            return false;
        }
    }

}
