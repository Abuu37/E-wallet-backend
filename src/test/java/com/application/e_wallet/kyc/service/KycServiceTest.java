package com.application.e_wallet.kyc.service;

import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.customer.entity.CustomerEntity;
import com.application.e_wallet.customer.repository.CustomerRepository;
import com.application.e_wallet.kyc.dto.KycStatusResponse;
import com.application.e_wallet.kyc.entity.KycStatus;
import com.application.e_wallet.kyc.provider.KycProvider;
import com.application.e_wallet.kyc.provider.KycResult;
import com.application.e_wallet.kyc.provider.KycSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KycServiceTest {

    @Mock private CustomerRepository customerRepository;
    @Mock private KycProvider kycProvider;

    private KycService service;
    private UUID userId;
    private CustomerEntity customer;

    @BeforeEach
    void setUp() {
        KycLimitsProperties limits = new KycLimitsProperties();
        service = new KycService(customerRepository, kycProvider, limits, org.mockito.Mockito.mock(com.application.e_wallet.limits.LimitService.class), org.mockito.Mockito.mock(com.application.e_wallet.wallet.repository.WalletRepository.class));
        userId = UUID.randomUUID();
        customer = CustomerEntity.builder().id(UUID.randomUUID()).kycStatus(KycStatus.NOT_STARTED).build();
        when(customerRepository.findByUserId(userId)).thenReturn(Optional.of(customer));
        when(customerRepository.findById(customer.getId())).thenReturn(Optional.of(customer));
        when(customerRepository.save(any(CustomerEntity.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void startingAVerificationReturnsALinkTokenAndMarksTheCustomerPending() {
        when(kycProvider.createSession(customer.getId())).thenReturn(new KycSession("link-sandbox-123"));

        String token = service.startSession(userId);

        assertThat(token).isEqualTo("link-sandbox-123");
        assertThat(customer.getKycStatus()).isEqualTo(KycStatus.PENDING);
    }

    @Test
    void anAlreadyVerifiedCustomerCannotStartAgain() {
        customer.setKycStatus(KycStatus.VERIFIED);

        assertThatThrownBy(() -> service.startSession(userId)).isInstanceOf(BadRequestException.class);
        verify(kycProvider, never()).createSession(any());
    }

    @Test
    void aPassedCheckVerifiesTheCustomerAndRecordsTheProviderReference() {
        when(kycProvider.fetchLatestResult(customer.getId()))
                .thenReturn(new KycResult(KycResult.Outcome.PASSED, "idv_123", customer.getId()));

        KycStatusResponse response = service.complete(userId);

        assertThat(customer.getKycStatus()).isEqualTo(KycStatus.VERIFIED);
        assertThat(customer.getKycProviderRef()).isEqualTo("idv_123");
        assertThat(customer.getKycVerifiedAt()).isNotNull();
        assertThat(response.status()).isEqualTo("VERIFIED");
        assertThat(response.tier()).isEqualTo("VERIFIED");
        assertThat(response.perPaymentLimit()).isEqualByComparingTo(new BigDecimal("3000000"));
    }

    @Test
    void aFailedCheckLeavesTheBasicTierAndAllowsATryAgain() {
        when(kycProvider.fetchLatestResult(customer.getId()))
                .thenReturn(new KycResult(KycResult.Outcome.FAILED, "idv_456", customer.getId()));

        KycStatusResponse response = service.complete(userId);

        assertThat(customer.getKycStatus()).isEqualTo(KycStatus.FAILED);
        assertThat(customer.getKycVerifiedAt()).isNull();
        assertThat(response.tier()).isEqualTo("BASIC");
        assertThat(response.perPaymentLimit()).isEqualByComparingTo(new BigDecimal("200000"));

        when(kycProvider.createSession(customer.getId())).thenReturn(new KycSession("link-retry"));
        assertThat(service.startSession(userId)).isEqualTo("link-retry");
    }

    @Test
    void aStillPendingCheckStaysPending() {
        customer.setKycStatus(KycStatus.PENDING);
        when(kycProvider.fetchLatestResult(customer.getId()))
                .thenReturn(new KycResult(KycResult.Outcome.PENDING, "idv_789", customer.getId()));

        service.complete(userId);

        assertThat(customer.getKycStatus()).isEqualTo(KycStatus.PENDING);
    }

    @Test
    void aVerifiedCustomerIsNeverDowngradedByALaterResult() {
        customer.setKycStatus(KycStatus.VERIFIED);
        when(kycProvider.fetchLatestResult(customer.getId()))
                .thenReturn(new KycResult(KycResult.Outcome.FAILED, "idv_late", customer.getId()));

        service.complete(userId);

        assertThat(customer.getKycStatus()).isEqualTo(KycStatus.VERIFIED);
    }

    @Test
    void aWebhookUpdateAppliesTheResultToTheRightCustomer() {
        when(kycProvider.fetchResult("idv_hook"))
                .thenReturn(new KycResult(KycResult.Outcome.PASSED, "idv_hook", customer.getId()));

        service.handleProviderUpdate("idv_hook");

        assertThat(customer.getKycStatus()).isEqualTo(KycStatus.VERIFIED);
    }

    @Test
    void aWebhookForAnUnknownCustomerIsIgnored() {
        UUID stranger = UUID.randomUUID();
        when(customerRepository.findById(stranger)).thenReturn(Optional.empty());
        when(kycProvider.fetchResult("idv_x")).thenReturn(new KycResult(KycResult.Outcome.PASSED, "idv_x", stranger));

        service.handleProviderUpdate("idv_x");

        verify(customerRepository, never()).save(any());
    }

    @Test
    void statusReportsTheBasicTierBeforeAnyCheck() {
        KycStatusResponse response = service.status(userId);

        assertThat(response.basic().perPayment()).isEqualByComparingTo(new BigDecimal("200000"));
        assertThat(response.verified().perPayment()).isEqualByComparingTo(new BigDecimal("3000000"));
        assertThat(response.verified().maxBalance()).isEqualByComparingTo(new BigDecimal("10000000"));

        assertThat(response.status()).isEqualTo("NOT_STARTED");
        assertThat(response.tier()).isEqualTo("BASIC");
        assertThat(response.dailyLimit()).isEqualByComparingTo(new BigDecimal("500000"));
        assertThat(response.maxBalance()).isEqualByComparingTo(new BigDecimal("1000000"));
    }
}
