package com.application.e_wallet.integration.plaid;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** Plaid settings. Keys come from environment variables only and are never logged or sent to the browser. */
@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "plaid")
public class PlaidProperties {

    private String baseUrl = "https://sandbox.plaid.com";
    private String clientId;
    private String secret;
    /** Identity Verification template id (idvtmp_...) created in the Plaid Dashboard. */
    private String idvTemplateId;
    private String clientName = "WalletPesa";
    /** Optional. IDV webhooks are registered in the Dashboard; this is only used for link tokens that support it. */
    private String webhookUrl;
    /** sandbox for now. The Sandbox-only switches below do nothing in any other environment. */
    private String env = "sandbox";
    private final Sandbox sandbox = new Sandbox();

    @Getter
    @Setter
    public static class Sandbox {
        /** Skips the own-bank name check. Sandbox banks carry Plaid's test names, never a customer's. */
        private boolean skipNameMatch;
        /** Sandbox balances are small USD amounts; the demo shows them in TZS at this fixed rate. */
        private BigDecimal usdToTzs = new BigDecimal("2600");
    }

    public boolean isSandbox() {
        return "sandbox".equalsIgnoreCase(env);
    }

    /** The own-bank name check is skipped only when this is Sandbox and the flag is on. */
    public boolean skipNameMatchActive() {
        return isSandbox() && sandbox.isSkipNameMatch();
    }

    /** Fail at startup rather than run a name-check bypass anywhere but Sandbox. */
    @PostConstruct
    void refuseUnsafeFlag() {
        if (sandbox.isSkipNameMatch() && !isSandbox()) {
            throw new IllegalStateException("plaid.sandbox.skip-name-match can only be on when PLAID_ENV is sandbox");
        }
    }
}
