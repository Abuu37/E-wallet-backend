package com.application.e_wallet.wallet.service;

import com.application.e_wallet.agent.entity.AgentEntity;
import com.application.e_wallet.agent.repository.AgentRepository;
import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.customer.entity.CustomerEntity;
import com.application.e_wallet.customer.repository.CustomerRepository;
import com.application.e_wallet.ledger.repository.TransactionJournalLineRepository;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.wallet.dto.WalletDetailResponse;
import com.application.e_wallet.wallet.dto.WalletLedgerEntry;
import com.application.e_wallet.wallet.dto.WalletListItem;
import com.application.e_wallet.wallet.dto.WalletListResponse;
import com.application.e_wallet.wallet.dto.WalletOwner;
import com.application.e_wallet.wallet.dto.WalletSummaryStats;
import com.application.e_wallet.wallet.entity.WalletEntity;
import com.application.e_wallet.wallet.entity.WalletStatus;
import com.application.e_wallet.wallet.entity.WalletType;
import com.application.e_wallet.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class WalletService {

    // "01" + 8 digits + 2 letters = 12 characters, same lead digits as AGENT_FLOAT by design.
    private static final String CUSTOMER_ACCOUNT_PREFIX = "01";

    private final WalletRepository walletRepository;
    private final CustomerRepository customerRepository;
    private final AgentRepository agentRepository;
    private final TransactionJournalLineRepository transactionJournalLineRepository;

    @Transactional
    public WalletEntity createWallet(CustomerEntity customer) {

        if (walletRepository.existsByUserIdAndWalletType(customer.getUser().getId(), WalletType.CUSTOMER_MAIN)) {
            throw new IllegalStateException(
                    "Customer already has a wallet"
            );
        }

        WalletEntity wallet = WalletEntity.builder()
                .user(customer.getUser())
                .walletType(WalletType.CUSTOMER_MAIN)
                .accountNumber(generateCustomerAccountNumber())
                .accountName(customer.getFirstName() + " " + customer.getLastName())
                .balance(BigDecimal.ZERO)
                .currency("TZS")
                .status(WalletStatus.ACTIVE)
                .build();

        return walletRepository.save(wallet);
    }

    // 12 characters: "01" + 8 random digits + 2 random letters, retried on collision.
    private String generateCustomerAccountNumber() {
        String number;
        do {
            number = CUSTOMER_ACCOUNT_PREFIX + randomDigits(8) + randomLetters(2);
        } while (walletRepository.existsByAccountNumber(number));

        return number;
    }

    private static String randomDigits(int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(ThreadLocalRandom.current().nextInt(10));
        }
        return sb.toString();
    }

    private static String randomLetters(int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append((char) ('A' + ThreadLocalRandom.current().nextInt(26)));
        }
        return sb.toString();
    }

    @Transactional(readOnly = true)
    public WalletDetailResponse getMyWallet(UUID userId) {
        return toDetailResponse(resolveMyWallet(userId));
    }

    // Agents own two wallets; "my wallet" is the float wallet they operate with -
    // AGENT_COMMISSION is not exposed through this endpoint.
    private WalletEntity resolveMyWallet(UUID userId) {
        return customerRepository.findByUserId(userId)
                .flatMap(customer -> walletRepository.findByUserIdAndWalletType(userId, WalletType.CUSTOMER_MAIN))
                .or(() -> agentRepository.findByUserId(userId)
                        .flatMap(agent -> walletRepository.findByUserIdAndWalletType(userId, WalletType.AGENT_FLOAT)))
                .orElseThrow(() ->
                        new BadRequestException(
                                "No wallet is associated with this account."
                        ));
    }

    @Transactional(readOnly = true)
    public WalletDetailResponse getWalletByAccountNumber(String accountNumber) {

        WalletEntity wallet = walletRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() ->
                        new BadRequestException(
                                "No wallet found for account number: " + accountNumber
                        ));

        return toDetailResponse(wallet);
    }

    @Transactional(readOnly = true)
    public WalletListResponse listWallets(Pageable pageable) {
        Page<WalletEntity> page = walletRepository.findAll(pageable);
        List<WalletListItem> wallets = page.getContent().stream()
                .map(this::toListItem)
                .toList();

        WalletSummaryStats summary = new WalletSummaryStats(
                walletRepository.sumBalanceByWalletType(WalletType.COMPANY_MASTER_ACCOUNT),
                walletRepository.sumBalanceByWalletType(WalletType.AGENT_FLOAT),
                walletRepository.sumBalanceByWalletType(WalletType.AGENT_COMMISSION),
                walletRepository.sumBalanceByWalletType(WalletType.CUSTOMER_MAIN)
        );

        return new WalletListResponse(summary, wallets, page.getNumber(), page.getSize(), page.getTotalElements());
    }

    private WalletListItem toListItem(WalletEntity wallet) {
        OwnerInfo owner = ownerInfoFor(wallet);
        return new WalletListItem(
                wallet.getId(),
                wallet.getAccountNumber(),
                wallet.getWalletType(),
                owner.name(),
                owner.code(),
                wallet.getBalance(),
                wallet.getCurrency(),
                wallet.getStatus(),
                wallet.getUpdatedAt()
        );
    }

    private WalletDetailResponse toDetailResponse(WalletEntity wallet) {
        OwnerInfo owner = ownerInfoFor(wallet);
        UserEntity user = wallet.getUser();

        WalletOwner walletOwner = new WalletOwner(
                user.getId(),
                owner.name(),
                user.getPhone(),
                user.getRole().getName()
        );

        List<WalletLedgerEntry> recentLedgerEntries = transactionJournalLineRepository
                .findTop5ByWalletIdInOrderByJournal_CreatedAtDesc(List.of(wallet.getId()))
                .stream()
                .map(line -> new WalletLedgerEntry(
                        line.getId(),
                        line.getJournal().getReferenceNumber(),
                        line.getJournal().getTransactionType().name(),
                        line.getEntryType().name(),
                        line.getAmount(),
                        line.getJournal().getDescription(),
                        line.getJournal().getCreatedAt()
                ))
                .toList();

        return new WalletDetailResponse(
                wallet.getId(),
                wallet.getAccountNumber(),
                wallet.getWalletType(),
                walletOwner,
                wallet.getBalance(),
                wallet.getCurrency(),
                wallet.getStatus(),
                wallet.getCreatedAt(),
                recentLedgerEntries
        );
    }

    // ownerCode is the agent's agentNumber for agent wallets; customers and company-level
    // wallets (master/revenue/escrow) have no such business code, so it's null for them.
    private OwnerInfo ownerInfoFor(WalletEntity wallet) {
        UUID userId = wallet.getUser().getId();
        return switch (wallet.getWalletType()) {
            case AGENT_FLOAT, AGENT_COMMISSION -> {
                AgentEntity agent = agentRepository.findByUserId(userId)
                        .orElseThrow(() -> new IllegalStateException("Agent not found for wallet owner"));
                yield new OwnerInfo(agent.getBusinessName(), agent.getAgentNumber());
            }
            case CUSTOMER_MAIN -> {
                CustomerEntity customer = customerRepository.findByUserId(userId)
                        .orElseThrow(() -> new IllegalStateException("Customer not found for wallet owner"));
                yield new OwnerInfo(customer.getFirstName() + " " + customer.getLastName(), null);
            }
            default -> new OwnerInfo(wallet.getAccountName(), null);
        };
    }

    private record OwnerInfo(String name, String code) {
    }
}
