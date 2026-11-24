package com.application.e_wallet.kyc.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Demo limits per KYC tier, read from configuration so they change without code. Before real money these
 * must be replaced with the regulator's tiers.
 */
@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "limits")
public class KycLimitsProperties {

    private Tier basic = new Tier("200000", "500000", "1000000");
    private Tier verified = new Tier("3000000", "5000000", "10000000");

    @Getter
    @Setter
    public static class Tier {
        private BigDecimal perPayment;
        private BigDecimal daily;
        private BigDecimal maxBalance;

        public Tier() {
        }

        public Tier(String perPayment, String daily, String maxBalance) {
            this.perPayment = new BigDecimal(perPayment);
            this.daily = new BigDecimal(daily);
            this.maxBalance = new BigDecimal(maxBalance);
        }
    }
}
