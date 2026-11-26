package com.application.e_wallet.wallet.service;

import com.application.e_wallet.common.validation.PhoneNumberNormalizer;
import com.application.e_wallet.customer.entity.CustomerEntity;
import com.application.e_wallet.customer.entity.CustomerStatus;
import com.application.e_wallet.customer.repository.CustomerRepository;
import com.application.e_wallet.user.repository.UserRepository;
import com.application.e_wallet.wallet.dto.LookupResponse;
import com.application.e_wallet.wallet.entity.WalletStatus;
import com.application.e_wallet.wallet.entity.WalletType;
import com.application.e_wallet.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** "Who is this number?" before a send. Only customers can be found; agents and staff never appear. */
@Service
@RequiredArgsConstructor
public class WalletLookupService {

    private final PhoneNumberNormalizer phoneNumberNormalizer;
    private final UserRepository userRepository;
    private final CustomerRepository customerRepository;
    private final WalletRepository walletRepository;

    @Transactional(readOnly = true)
    public LookupResponse lookup(String phone) {
        String normalized;
        try {
            normalized = phoneNumberNormalizer.normalize(phone);
        } catch (RuntimeException invalid) {
            return LookupResponse.notFound();
        }
        return userRepository.findByPhone(normalized)
                .flatMap(user -> customerRepository.findByUserId(user.getId()))
                .map(customer -> new LookupResponse(true, maskedName(customer), canReceive(customer)))
                .orElseGet(LookupResponse::notFound);
    }

    private boolean canReceive(CustomerEntity customer) {
        return customer.getStatus() == CustomerStatus.ACTIVE
                && walletRepository.findByUserIdAndWalletType(customer.getUser().getId(), WalletType.CUSTOMER_MAIN)
                        .map(w -> w.getStatus() == WalletStatus.ACTIVE).orElse(false);
    }

    /** "Amina Juma" becomes "Amina J." */
    public static String maskedName(CustomerEntity customer) {
        String last = customer.getLastName() == null ? "" : customer.getLastName().trim();
        return customer.getFirstName().trim() + (last.isEmpty() ? "" : " " + last.charAt(0) + ".");
    }
}
