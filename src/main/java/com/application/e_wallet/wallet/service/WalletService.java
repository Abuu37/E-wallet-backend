package com.application.e_wallet.wallet.service;

import com.application.e_wallet.agent.entity.AgentEntity;
import com.application.e_wallet.agent.repository.AgentRepository;
import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.customer.entity.CustomerEntity;
import com.application.e_wallet.customer.repository.CustomerRepository;
import com.application.e_wallet.wallet.dto.WalletResponse;
import com.application.e_wallet.wallet.entity.WalletEntity;
import com.application.e_wallet.wallet.entity.WalletStatus;
import com.application.e_wallet.wallet.entity.WalletType;
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
    private final CustomerRepository customerRepository;
    private final AgentRepository agentRepository;

    @Transactional
    public WalletEntity createWallet(CustomerEntity customer) {

        if (walletRepository.existsByUserIdAndWalletType(customer.getUser().getId(), WalletType.CUSTOMER_MAIN)) {
            throw new IllegalStateException(
                    "Customer already has a wallet"
            );
        }

        String phone = customer.getUser().getPhone();

        WalletEntity wallet = WalletEntity.builder()
                .user(customer.getUser())
                .walletType(WalletType.CUSTOMER_MAIN)
                .walletNumber(phone)
                .accountNumber(phone)
                .balance(BigDecimal.ZERO)
                .currency("TZS")
                .status(WalletStatus.ACTIVE)
                .build();

        return walletRepository.save(wallet);
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

        WalletEntity wallet = walletRepository.findByUserIdAndWalletType(
                        customer.getUser().getId(), WalletType.CUSTOMER_MAIN)
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

        return toResponse(wallet, ownerTypeFor(wallet.getWalletType()));
    }

    private WalletResponse walletForAgent(AgentEntity agent) {

        // An agent now has two wallets. "My wallet" means the float wallet the agent
        // operates with; AGENT_COMMISSION is not exposed through this endpoint.
        WalletEntity wallet = walletRepository.findByUserIdAndWalletType(
                        agent.getUser().getId(), WalletType.AGENT_FLOAT)
                .orElseThrow(() ->
                        new BadRequestException(
                                "No wallet is associated with this account."
                        ));

        return toResponse(wallet, "AGENT");
    }

    private WalletResponse toResponse(WalletEntity wallet, String ownerType) {

        return new WalletResponse(
                wallet.getId(),
                ownerType,
                wallet.getWalletNumber(),
                wallet.getCurrency(),
                wallet.getStatus(),
                wallet.getAccountNumber(),
                wallet.getBalance()
        );
    }

    private String ownerTypeFor(WalletType walletType) {
        return switch (walletType) {
            case CUSTOMER_MAIN -> "CUSTOMER";
            case AGENT_FLOAT, AGENT_COMMISSION -> "AGENT";
            default -> "COMPANY";
        };
    }
}
