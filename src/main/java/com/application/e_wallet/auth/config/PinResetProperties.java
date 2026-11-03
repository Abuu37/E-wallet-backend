package com.application.e_wallet.auth.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "pin-reset")
public class PinResetProperties {

    private int length;
    private int expirationMinutes;
    private int maxAttempts;
    private int resendCooldownSeconds;
}
