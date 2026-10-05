package com.application.e_wallet.wallet.service;

import com.application.e_wallet.customer.entity.CustomerEntity;
import com.application.e_wallet.financial_account.entity.FinancialAccountEntity;
import com.application.e_wallet.financial_account.entity.FinancialAccountStatus;
import com.application.e_wallet.financial_account.entity.FinancialAccountType;
import com.application.e_wallet.financial_account.repository.FinancialAccountRepository;
import com.application.e_wallet.wallet.entity.WalletEntity;
import com.application.e_wallet.wallet.entity.WalletStatus;
import com.application.e_wallet.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class WalletService {

    private final WalletRepository walletRepository;
    private final FinancialAccountRepository financialAccountRepository;

    @Transactional
    public WalletEntity createWallet(CustomerEntity customer) {

        if (walletRepository.existsByCustomerId(customer.getId())) {
            throw new IllegalStateException(
                    "Customer already has a wallet"
            );
        }


        WalletEntity wallet = WalletEntity.builder()
                .customer(customer)
                .walletNumber(customer.getUser().getPhone())
                .currency("TZS")
                .status(WalletStatus.ACTIVE)
                .build();

        wallet = walletRepository.save(wallet);

        FinancialAccountEntity account = FinancialAccountEntity.builder()
                .accountNumber(customer.getUser().getPhone())
                .accountType(FinancialAccountType.CUSTOMER_WALLET)
                .currency("TZS")
                .currentBalance(BigDecimal.ZERO)
                .status(FinancialAccountStatus.ACTIVE)
                .wallet(wallet)
                .build();

        financialAccountRepository.save(account);

        wallet.setFinancialAccount(account);

        return wallet;
    }
}