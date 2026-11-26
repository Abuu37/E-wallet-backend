package com.application.e_wallet.kyc.service;

import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.customer.entity.CustomerEntity;
import com.application.e_wallet.customer.repository.CustomerRepository;
import com.application.e_wallet.kyc.dto.KycStatusResponse;
import com.application.e_wallet.kyc.entity.KycStatus;
import com.application.e_wallet.kyc.provider.KycProvider;
import com.application.e_wallet.kyc.provider.KycResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * kyc_status changes only after our server has read the result from the provider; the client's word is
 * never trusted. VERIFIED is final: a later result never downgrades it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KycService {

    private final CustomerRepository customerRepository;
    private final KycProvider kycProvider;
    private final KycLimitsProperties limits;
    private final com.application.e_wallet.limits.LimitService limitService;
    private final com.application.e_wallet.wallet.repository.WalletRepository walletRepository;

    /** Returns the short-lived link token the browser opens. */
    @Transactional
    public String startSession(UUID userId) {
        CustomerEntity customer = customerFor(userId);
        if (customer.getKycStatus() == KycStatus.VERIFIED) {
            throw new BadRequestException("Your identity is already verified.");
        }
        String linkToken = kycProvider.createSession(customer.getId()).linkToken();
        customer.setKycStatus(KycStatus.PENDING);
        customerRepository.save(customer);
        return linkToken;
    }

    /** Called when the customer finishes the provider flow: reads the result from the provider. */
    @Transactional
    public KycStatusResponse complete(UUID userId) {
        CustomerEntity customer = customerFor(userId);
        apply(customer, kycProvider.fetchLatestResult(customer.getId()));
        return toResponse(customer);
    }

    @Transactional(readOnly = true)
    public KycStatusResponse status(UUID userId) {
        return toResponse(customerFor(userId));
    }

    /** Called by the verified webhook: whichever of webhook and "complete" arrives first sets the status. */
    @Transactional
    public void handleProviderUpdate(String providerRef) {
        KycResult result = kycProvider.fetchResult(providerRef);
        if (result.customerId() == null) {
            log.warn("KYC webhook for a verification with no customer reference");
            return;
        }
        customerRepository.findById(result.customerId()).ifPresentOrElse(
                customer -> apply(customer, result),
                () -> log.warn("KYC webhook for an unknown customer"));
    }

    private void apply(CustomerEntity customer, KycResult result) {
        if (customer.getKycStatus() == KycStatus.VERIFIED) {
            return;
        }
        switch (result.outcome()) {
            case PASSED -> {
                customer.setKycStatus(KycStatus.VERIFIED);
                customer.setKycProviderRef(result.providerRef());
                customer.setKycVerifiedAt(OffsetDateTime.now());
            }
            case FAILED -> {
                customer.setKycStatus(KycStatus.FAILED);
                customer.setKycProviderRef(result.providerRef());
            }
            case PENDING -> {
                if (customer.getKycStatus() == KycStatus.NOT_STARTED && result.providerRef() != null) {
                    customer.setKycStatus(KycStatus.PENDING);
                }
            }
        }
        customerRepository.save(customer);
    }

    private KycStatusResponse toResponse(CustomerEntity customer) {
        boolean verified = customer.getKycStatus() == KycStatus.VERIFIED;
        KycLimitsProperties.Tier tier = verified ? limits.getVerified() : limits.getBasic();
        return new KycStatusResponse(
                customer.getKycStatus().name(),
                verified ? "VERIFIED" : "BASIC",
                tier.getPerPayment(), tier.getDaily(), tier.getMaxBalance(),
                toLimits(limits.getBasic()), toLimits(limits.getVerified()),
                true,
                walletRepository.findByUserIdAndWalletType(customer.getUser().getId(), com.application.e_wallet.wallet.entity.WalletType.CUSTOMER_MAIN)
                        .map(w -> limitService.usedToday(w.getId())).orElse(java.math.BigDecimal.ZERO));
    }

    private static KycStatusResponse.Limits toLimits(KycLimitsProperties.Tier tier) {
        return new KycStatusResponse.Limits(tier.getPerPayment(), tier.getDaily(), tier.getMaxBalance());
    }

    private CustomerEntity customerFor(UUID userId) {
        return customerRepository.findByUserId(userId)
                .orElseThrow(() -> new BadRequestException("No customer profile for this account"));
    }
}
