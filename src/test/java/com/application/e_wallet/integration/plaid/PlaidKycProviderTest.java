package com.application.e_wallet.integration.plaid;

import com.application.e_wallet.kyc.provider.KycResult;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PlaidKycProviderTest {

    @Test
    void mapsPlaidIdentityVerificationStatusesToOurOutcomes() {
        assertThat(PlaidKycProvider.outcomeOf("success")).isEqualTo(KycResult.Outcome.PASSED);
        assertThat(PlaidKycProvider.outcomeOf("failed")).isEqualTo(KycResult.Outcome.FAILED);
        assertThat(PlaidKycProvider.outcomeOf("expired")).isEqualTo(KycResult.Outcome.FAILED);
        assertThat(PlaidKycProvider.outcomeOf("canceled")).isEqualTo(KycResult.Outcome.FAILED);
        assertThat(PlaidKycProvider.outcomeOf("active")).isEqualTo(KycResult.Outcome.PENDING);
        assertThat(PlaidKycProvider.outcomeOf("pending_review")).isEqualTo(KycResult.Outcome.PENDING);
    }

    @Test
    void anUnknownOrMissingStatusIsNeverTreatedAsAPass() {
        assertThat(PlaidKycProvider.outcomeOf("something_new")).isEqualTo(KycResult.Outcome.PENDING);
        assertThat(PlaidKycProvider.outcomeOf(null)).isEqualTo(KycResult.Outcome.PENDING);
        assertThat(PlaidKycProvider.outcomeOf("SUCCESS ")).isEqualTo(KycResult.Outcome.PASSED);
    }

    @Test
    void theClientUserIdIsOurCustomerIdAndNothingPersonal() {
        UUID id = UUID.randomUUID();
        assertThat(PlaidKycProvider.clientUserId(id)).isEqualTo(id.toString());
    }
}
