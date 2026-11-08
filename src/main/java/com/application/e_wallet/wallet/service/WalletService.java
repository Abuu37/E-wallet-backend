package com.application.e_wallet.wallet.service;

import com.application.e_wallet.agent.entity.AgentEntity;
import com.application.e_wallet.agent.repository.AgentRepository;
import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.customer.entity.CustomerEntity;
import com.application.e_wallet.customer.repository.CustomerRepository;
import com.application.e_wallet.financial_account.entity.FinancialAccountEntity;
import com.application.e_wallet.financial_account.entity.FinancialAccountStatus;
import com.application.e_wallet.financial_account.entity.FinancialAccountType;
import com.application.e_wallet.financial_account.repository.FinancialAccountRepository;
import com.application.e_wallet.wallet.dto.WalletResponse;
import com.application.e_wallet.wallet.entity.WalletEntity;
import com.application.e_wallet.wallet.entity.WalletStatus;
import com.application.e_wallet.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WalletService {

    private final WalletRepository walletRepository;
    private final FinancialAccountRepository financialAccountRepository;
    private final CustomerRepository customerRepository;
    private final AgentRepository agentRepository;

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

    @Transactional
    public WalletResponse getMyWallet(UUID userId) {

        return customerRepository.findByUserId(userId)
                .map(this::walletForCustomer)
                .or(() -> agentRepository.findByUserId(userId).map(this::walletForAgent))
                .orElseThrow(() ->
                        new BadRequestException(
                                "No wallet is associated with this account."
                        ));
    }

    private WalletResponse walletForCustomer(CustomerEntity customer) {

        WalletEntity wallet = walletRepository.findByCustomerId(customer.getId())
                .orElseThrow(() ->
                        new BadRequestException(
                                "No wallet is associated with this account."
                        ));

        return toResponse(wallet, "CUSTOMER");
    }

    @Transactional
    public WalletResponse getWalletByNumber(String walletNumber) {

        WalletEntity wallet = walletRepository.findByWalletNumber(walletNumber)
                .orElseThrow(() ->
                        new BadRequestException(
                                "No wallet found for wallet number: " + walletNumber
                        ));

        String ownerType = wallet.getCustomer() != null ? "CUSTOMER" : "AGENT";

        return toResponse(wallet, ownerType);
    }

    private WalletResponse walletForAgent(AgentEntity agent) {

        WalletEntity wallet = walletRepository.findByAgentId(agent.getId())
                .orElseThrow(() ->
                        new BadRequestException(
                                "No wallet is associated with this account."
                        ));

        return toResponse(wallet, "AGENT");
    }

    private WalletResponse toResponse(WalletEntity wallet, String ownerType) {

        FinancialAccountEntity account = financialAccountRepository.findByWalletId(wallet.getId())
                .orElseThrow(() ->
                        new BadRequestException(
                                "No financial account is associated with this wallet."
                        ));

        return new WalletResponse(
                wallet.getId(),
                ownerType,
                wallet.getWalletNumber(),
                wallet.getCurrency(),
                wallet.getStatus(),
                account.getAccountNumber(),
                account.getCurrentBalance()
        );
    }
}