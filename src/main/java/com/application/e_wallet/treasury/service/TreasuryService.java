package com.application.e_wallet.treasury.service;

import com.application.e_wallet.agent.entity.AgentEntity;
import com.application.e_wallet.agent.repository.AgentRepository;
import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.ledger.dto.JournalLineRequest;
import com.application.e_wallet.ledger.entity.EntryType;
import com.application.e_wallet.ledger.entity.TransactionJournalEntity;
import com.application.e_wallet.ledger.entity.TransactionType;
import com.application.e_wallet.ledger.repository.TransactionJournalRepository;
import com.application.e_wallet.ledger.service.LedgerService;
import com.application.e_wallet.treasury.dto.JournalLineResponse;
import com.application.e_wallet.treasury.dto.MintRequest;
import com.application.e_wallet.treasury.dto.TransactionJournalResponse;
import com.application.e_wallet.treasury.dto.TreasuryMetrics;
import com.application.e_wallet.treasury.dto.TreasuryOverviewResponse;
import com.application.e_wallet.treasury.entity.RequestStatus;
import com.application.e_wallet.treasury.repository.TreasuryRequestRepository;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.user.repository.UserRepository;
import com.application.e_wallet.wallet.entity.WalletEntity;
import com.application.e_wallet.wallet.entity.WalletStatus;
import com.application.e_wallet.wallet.entity.WalletType;
import com.application.e_wallet.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TreasuryService {

    private final WalletRepository walletRepository;
    private final AgentRepository agentRepository;
    private final UserRepository userRepository;
    private final TreasuryRequestRepository treasuryRequestRepository;
    private final TransactionJournalRepository transactionJournalRepository;
    private final LedgerService ledgerService;

    //=================== GET TREASURY OVERVIEW =============
    @Transactional(readOnly = true)
    public TreasuryOverviewResponse getOverview() {
        WalletEntity companyWallet = walletRepository.findByWalletType(WalletType.COMPANY_MASTER_ACCOUNT)
                .orElseThrow(() -> new IllegalStateException("Company master wallet not initialized"));

        BigDecimal totalDistributedFloat = walletRepository.sumBalanceByWalletType(WalletType.AGENT_FLOAT);

        return new TreasuryOverviewResponse(
                companyWallet.getId(),
                companyWallet.getCurrency(),
                companyWallet.getBalance(),
                totalDistributedFloat,
                buildMetrics()
        );
    }

    //============== GET JOURNAL HISTORY =================
    @Transactional(readOnly = true)
    public List<TransactionJournalResponse> getJournalHistory() {
        return transactionJournalRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(this::toJournalResponse)
                .toList();
    }

    //=================== MINT FLOAT FROM THE BANK ==============
    @Transactional
    public void mint(MintRequest request, UUID adminUserId) {
        if (request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException("Mint amount must be positive");
        }
        validateScale(request.amount());

        UserEntity admin = resolveAdmin(adminUserId);

        WalletEntity companyWallet = walletRepository.findByWalletTypeForUpdate(WalletType.COMPANY_MASTER_ACCOUNT)
                .orElseThrow(() -> new IllegalStateException("Company master wallet not initialized"));
        WalletEntity escrowWallet = walletRepository.findByWalletTypeForUpdate(WalletType.ESCROW_BANK)
                .orElseThrow(() -> new IllegalStateException("Escrow bank wallet not initialized"));

        companyWallet.setBalance(normalizeZero(companyWallet.getBalance().add(request.amount())));
        escrowWallet.setBalance(normalizeZero(escrowWallet.getBalance().add(request.amount())));

        ledgerService.postJournal(TransactionType.MINT_FLOAT, request.description(), admin, List.of(
                new JournalLineRequest(escrowWallet.getId(), EntryType.DEBIT, request.amount()),
                new JournalLineRequest(companyWallet.getId(), EntryType.CREDIT, request.amount())
        ));

        walletRepository.save(companyWallet);
        walletRepository.save(escrowWallet);
    }

    //=================== TOPUP AN AGENT'S FLOAT WALLET ==============
    @Transactional
    public void topup(UUID agentId, BigDecimal amount, String description, UUID adminUserId) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException("Topup amount must be positive");
        }
        validateScale(amount);

        UserEntity admin = resolveAdmin(adminUserId);
        AgentEntity agent = resolveAgent(agentId);

        WalletEntity companyWallet = walletRepository.findByWalletTypeForUpdate(WalletType.COMPANY_MASTER_ACCOUNT)
                .orElseThrow(() -> new IllegalStateException("Company master wallet not initialized"));
        WalletEntity agentWallet = walletRepository.findByUserIdAndWalletTypeForUpdate(agent.getUser().getId(), WalletType.AGENT_FLOAT)
                .orElseThrow(() -> new BadRequestException("Agent float wallet not found"));

        if (agentWallet.getStatus() != WalletStatus.ACTIVE) {
            throw new BadRequestException("Agent float wallet is not active");
        }

        if (companyWallet.getBalance().compareTo(amount) < 0) {
            throw new BadRequestException("Insufficient unallocated float");
        }

        companyWallet.setBalance(normalizeZero(companyWallet.getBalance().subtract(amount)));
        agentWallet.setBalance(normalizeZero(agentWallet.getBalance().add(amount)));

        ledgerService.postJournal(TransactionType.TOPUP_FLOAT, description, admin, List.of(
                new JournalLineRequest(companyWallet.getId(), EntryType.DEBIT, amount),
                new JournalLineRequest(agentWallet.getId(), EntryType.CREDIT, amount)
        ));

        walletRepository.save(companyWallet);
        walletRepository.save(agentWallet);
    }

    //=================== WITHDRAW FROM AN AGENT'S FLOAT WALLET ==============
    @Transactional
    public void withdraw(UUID agentId, BigDecimal amount, String description, UUID adminUserId) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException("Withdraw amount must be positive");
        }
        validateScale(amount);

        UserEntity admin = resolveAdmin(adminUserId);
        AgentEntity agent = resolveAgent(agentId);

        WalletEntity companyWallet = walletRepository.findByWalletTypeForUpdate(WalletType.COMPANY_MASTER_ACCOUNT)
                .orElseThrow(() -> new IllegalStateException("Company master wallet not initialized"));
        WalletEntity agentWallet = walletRepository.findByUserIdAndWalletTypeForUpdate(agent.getUser().getId(), WalletType.AGENT_FLOAT)
                .orElseThrow(() -> new BadRequestException("Agent float wallet not found"));

        if (agentWallet.getStatus() != WalletStatus.ACTIVE) {
            throw new BadRequestException("Agent float wallet is not active");
        }

        if (agentWallet.getBalance().compareTo(amount) < 0) {
            throw new BadRequestException("Insufficient agent float balance");
        }

        agentWallet.setBalance(normalizeZero(agentWallet.getBalance().subtract(amount)));
        companyWallet.setBalance(normalizeZero(companyWallet.getBalance().add(amount)));

        ledgerService.postJournal(TransactionType.AGENT_WITHDRAW, description, admin, List.of(
                new JournalLineRequest(agentWallet.getId(), EntryType.DEBIT, amount),
                new JournalLineRequest(companyWallet.getId(), EntryType.CREDIT, amount)
        ));

        walletRepository.save(agentWallet);
        walletRepository.save(companyWallet);
    }

    /**
     * BigDecimal's equals() treats scale as significant (e.g. 0.00 != 0), which would make a
     * fully-depleted balance fail an isEqualTo(BigDecimal.ZERO) check even though it is
     * numerically zero. Canonicalize exact-zero results to BigDecimal.ZERO; any non-zero result
     * is returned unchanged, preserving its arithmetic scale.
     */
    private static BigDecimal normalizeZero(BigDecimal amount) {
        return amount.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO : amount;
    }

    /**
     * Every money column is NUMERIC(19,2). An amount with more than 2 decimal places would be
     * rounded independently by each column it is persisted into, silently breaking balance
     * invariants this feature exists to maintain. Reject it before any repository interaction.
     * This is defense-in-depth alongside the @Digits constraint on the request DTOs, for any
     * caller that bypasses @Valid.
     */
    private void validateScale(BigDecimal amount) {
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new BadRequestException("Amount must have at most 2 decimal places");
        }
    }

    private UserEntity resolveAdmin(UUID adminUserId) {
        return userRepository.findById(adminUserId)
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found"));
    }

    private AgentEntity resolveAgent(UUID agentId) {
        return agentRepository.findById(agentId)
                .orElseThrow(() -> new BadRequestException("Agent not found"));
    }

    private TreasuryMetrics buildMetrics() {
        return new TreasuryMetrics(
                normalizeScale(treasuryRequestRepository.sumAmountByStatus(RequestStatus.PENDING)),
                treasuryRequestRepository.countByStatus(RequestStatus.PENDING),
                normalizeScale(treasuryRequestRepository.sumAmountByStatus(RequestStatus.APPROVED)),
                treasuryRequestRepository.countByStatus(RequestStatus.APPROVED)
        );
    }

    // SUM(...) over no matching rows comes back via the COALESCE fallback, whose literal
    // scale isn't guaranteed to match the NUMERIC(19,2) column scale across JPA providers.
    private static BigDecimal normalizeScale(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private TransactionJournalResponse toJournalResponse(TransactionJournalEntity journal) {
        List<JournalLineResponse> lines = journal.getLines().stream()
                .map(line -> new JournalLineResponse(
                        line.getWalletId(),
                        line.getEntryType().name(),
                        line.getAmount()
                ))
                .toList();

        return new TransactionJournalResponse(
                journal.getId(),
                journal.getReferenceNumber(),
                journal.getTransactionType().name(),
                journal.getDescription(),
                journal.getCreatedBy().getId(),
                journal.getCreatedAt(),
                lines
        );
    }
}
