package com.application.e_wallet.otp.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
public class OtpGenerator {

    private final SecureRandom secureRandom = new SecureRandom();

    public String generate(int length) {
        int minimum= (int) Math.pow(10, length - 1);
        int maximum= (int) Math.pow(10, length);

        int otp = minimum + secureRandom.nextInt(maximum - minimum);

        return String.valueOf(otp);
    }
}

