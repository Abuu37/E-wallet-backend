package com.application.e_wallet.integration.plaid;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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
}
