package com.application.e_wallet.integration.plaid;

import com.application.e_wallet.kyc.provider.KycResult;
import com.application.e_wallet.kyc.provider.KycSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MANUAL smoke test against Plaid's real Sandbox. It never runs in CI: set PLAID_SMOKE=true together with
 * PLAID_CLIENT_ID, PLAID_SECRET and PLAID_IDV_TEMPLATE_ID in the environment to run it.
 */
@EnabledIfEnvironmentVariable(named = "PLAID_SMOKE", matches = "true")
class PlaidSandboxSmokeTest {

    @Test
    void sandboxCredentialsAndTemplateWork() {
        PlaidProperties properties = new PlaidProperties();
        properties.setClientId(System.getenv("PLAID_CLIENT_ID"));
        properties.setSecret(System.getenv("PLAID_SECRET"));
        properties.setIdvTemplateId(System.getenv("PLAID_IDV_TEMPLATE_ID"));
        PlaidKycProvider provider = new PlaidKycProvider(new PlaidClient(properties, JsonMapper.builder().build()), properties);

        UUID customerId = UUID.randomUUID();

        KycSession session = provider.createSession(customerId);
        assertThat(session.linkToken()).startsWith("link-sandbox-");

        KycResult result = provider.fetchLatestResult(customerId);
        assertThat(result.outcome()).isEqualTo(KycResult.Outcome.PENDING);
        assertThat(result.providerRef()).isNull();
    }
}
