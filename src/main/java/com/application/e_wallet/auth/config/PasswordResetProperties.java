package com.application.e_wallet.auth.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "password-reset")
public class PasswordResetProperties {

    private int length;
    private int expirationMinutes;
    private int maxAttempts;
    private int resendCooldownSeconds;
}
