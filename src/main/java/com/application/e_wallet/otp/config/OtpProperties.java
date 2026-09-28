package com.application.e_wallet.otp.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@Setter
@Getter
@ConfigurationProperties(prefix = "otp")
public class OtpProperties {

    private int length;
    private int expirationMinutes;
    private int maxAttempts;
}
