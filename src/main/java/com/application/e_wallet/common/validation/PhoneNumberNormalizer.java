package com.application.e_wallet.common.validation;

import com.application.e_wallet.common.exception.BadRequestException;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;
import org.springframework.stereotype.Component;

@Component
public class PhoneNumberNormalizer {

    private static final String DEFAULT_REGION = "TZ";

    private final PhoneNumberUtil phoneNumberUtil = PhoneNumberUtil.getInstance();

    // Converts an already-validated phone number to a canonical E.164 string
    // (e.g. "+255712345678") so the same real number always produces the same
    // stored value, regardless of how the customer typed it.
    public String normalize(String rawPhone) {
        try {
            PhoneNumber phoneNumber = phoneNumberUtil.parse(rawPhone, DEFAULT_REGION);
            return phoneNumberUtil.format(phoneNumber, PhoneNumberUtil.PhoneNumberFormat.E164);
        } catch (NumberParseException exception) {
            throw new BadRequestException("Invalid phone number");
        }
    }
}
