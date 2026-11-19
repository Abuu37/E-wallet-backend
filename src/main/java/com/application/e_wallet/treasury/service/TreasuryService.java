package com.application.e_wallet.treasury.service;

import com.application.e_wallet.agent.entity.AgentEntity;
import com.application.e_wallet.agent.repository.AgentRepository;
import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.ledger.dto.JournalLineRequest;
import com.application.e_wallet.ledger.entity.EntryType;
import com.application.e_wallet.ledger.entity.TransactionJournalEntity;
import com.application.e_wallet.ledger.entity.TransactionJournalLineEntity;
import com.application.e_wallet.ledger.entity.LedgerTransactionType;
import com.application.e_wallet.ledger.repository.TransactionJournalRepository;
import com.application.e_wallet.ledger.service.LedgerService;
import com.application.e_wallet.treasury.dto.CommissionTopupResponse;
import com.application.e_wallet.treasury.dto.JournalLineResponse;
import com.application.e_wallet.treasury.dto.MintRequest;
import com.application.e_wallet.treasury.dto.TransactionJournalResponse;
import com.application.e_wallet.treasury.dto.TreasuryMetrics;
import com.application.e_wallet.treasury.dto.TreasuryOverviewResponse;
import com.application.e_wallet.treasury.dto.WalletMovement;
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
import java.time.OffsetDateTime;
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
    public TreasuryOverviewResponse getOverview(String createdBy) {
        WalletEntity companyWallet = walletRepository.findByWalletType(WalletType.COMPANY_MASTER_ACCOUNT)
                .orElseThrow(() -> new IllegalStateException(
                        "Company master wallet not initialized"
                ));

        BigDecimal totalDistributedFloat = walletRepository.sumBalanceByWalletType(WalletType.AGENT_FLOAT);

        return new TreasuryOverviewResponse(
                companyWallet.getId(),
                companyWallet.getCurrency(),
                companyWallet.getBalance(),
                totalDistributedFloat,
                buildMetrics(),
                createdBy,
                OffsetDateTime.now()
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
            throw new BadRequestException(
                    "Mint amount must be positive"
            );
        }
        validateScale(request.amount());

        UserEntity admin = resolveAdmin(adminUserId);

        WalletEntity companyWallet = walletRepository.findByWalletTypeForUpdate(WalletType.COMPANY_MASTER_ACCOUNT)
                .orElseThrow(() -> new IllegalStateException(
                        "Company master wallet not initialized"
                ));
        WalletEntity escrowWallet = walletRepository.findByWalletTypeForUpdate(WalletType.ESCROW_BANK)
                .orElseThrow(() -> new IllegalStateException(
                        "Escrow bank wallet not initialized"
                ));

        companyWallet.setBalance(normalizeZero(companyWallet.getBalance().add(request.amount())));
        escrowWallet.setBalance(normalizeZero(escrowWallet.getBalance().add(request.amount())));

        ledgerService.postJournal(LedgerTransactionType.MINT_FLOAT, request.description(), admin, List.of(
                new JournalLineRequest(escrowWallet.getId(), EntryType.DEBIT, request.amount()),
                new JournalLineRequest(companyWallet.getId(), EntryType.CREDIT, request.amount())
        ));

        walletRepository.save(companyWallet);
        walletRepository.save(escrowWallet);
    }

    //=================== TOPUP FLOAT TO AN AGENT'S FLOAT WALLET ==============
    @Transactional
    public void topup(UUID agentId, BigDecimal amount, String description, UUID adminUserId) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException(
                    "Topup amount must be positive"
            );
        }
        validateScale(amount);

        UserEntity admin = resolveAdmin(adminUserId);
        AgentEntity agent = resolveAgent(agentId);

        WalletEntity companyWallet = walletRepository.findByWalletTypeForUpdate(WalletType.COMPANY_MASTER_ACCOUNT)
                .orElseThrow(() -> new IllegalStateException(
                        "Company master wallet not initialized"
                ));
        WalletEntity agentWallet = walletRepository.findByUserIdAndWalletTypeForUpdate(agent.getUser().getId(), WalletType.AGENT_FLOAT)
                .orElseThrow(() -> new BadRequestException(
                        "Agent float wallet not found"
                ));

        if (agentWallet.getStatus() != WalletStatus.ACTIVE) {
            throw new BadRequestException(
                    "Agent float wallet is not active"
            );
        }
        if (companyWallet.getBalance().compareTo(amount) < 0) {
            throw new BadRequestException(
                    "Insufficient unallocated float"
            );
        }

        companyWallet.setBalance(normalizeZero(companyWallet.getBalance().subtract(amount)));
        agentWallet.setBalance(normalizeZero(agentWallet.getBalance().add(amount)));

        ledgerService.postJournal(LedgerTransactionType.TOPUP_FLOAT, description, admin, List.of(
                new JournalLineRequest(companyWallet.getId(), EntryType.DEBIT, amount),
                new JournalLineRequest(agentWallet.getId(), EntryType.CREDIT, amount)
        ));

        walletRepository.save(companyWallet);
        walletRepository.save(agentWallet);
    }

    //=================== TOPUP COMMISSION ACCOUNT FORM COMPANY MASTER WALLET ==============
    @Transactional
    public CommissionTopupResponse fundCommission(BigDecimal amount, String description, UUID adminUserId) {

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException(
                    "Topup amount must be positive"
            );
        }
        validateScale(amount);

        UserEntity admin = resolveAdmin(adminUserId);

        WalletEntity companyWallet = walletRepository.findByWalletTypeForUpdate(WalletType.COMPANY_MASTER_ACCOUNT)
                .orElseThrow(() -> new IllegalStateException(
                        "Company master wallet not initialized"
                ));
        WalletEntity commissionWallet = walletRepository.findByWalletTypeForUpdate(WalletType.COMMISSION)
                .orElseThrow(() -> new IllegalStateException(
                        "Commission wallet not initialized"
                ));

        if (companyWallet.getBalance().compareTo(amount) < 0) {
            throw new BadRequestException(
                    "Insufficient balance in Company Master Wallet"
            );
        }

        BigDecimal companyPreviousBalance = companyWallet.getBalance();
        BigDecimal commissionPreviousBalance = commissionWallet.getBalance();

        companyWallet.setBalance(
                normalizeZero(companyPreviousBalance.subtract(amount))
        );

        commissionWallet.setBalance(
                normalizeZero(commissionPreviousBalance.add(amount))
        );

        TransactionJournalEntity journal = ledgerService.postJournal(LedgerTransactionType.TOPUP_COMMISSION, description, admin, List.of(
                new JournalLineRequest(companyWallet.getId(), EntryType.DEBIT, amount),
                new JournalLineRequest(commissionWallet.getId(), EntryType.CREDIT, amount)
        ));

        walletRepository.save(companyWallet);
        walletRepository.save(commissionWallet);

        return new CommissionTopupResponse(
                journal.getReferenceNumber(),
                journal.getTransactionType().name(),
                "COMPLETED",
                companyWallet.getCurrency(),
                amount,
                new WalletMovement(companyWallet.getAccountName(), "DEBIT", companyPreviousBalance, companyWallet.getBalance()),
                new WalletMovement(commissionWallet.getAccountName(), "CREDIT", commissionPreviousBalance, commissionWallet.getBalance()),
                admin.getEmail()
        );
    }

    //=================== WITHDRAW FROM AN AGENT'S FLOAT WALLET ==============
    @Transactional
    public void withdraw(UUID agentId, BigDecimal amount, String description, UUID adminUserId) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException(
                    "Withdraw amount must be positive"
            );
        }
        validateScale(amount);

        UserEntity admin = resolveAdmin(adminUserId);
        AgentEntity agent = resolveAgent(agentId);

        WalletEntity companyWallet = walletRepository.findByWalletTypeForUpdate(WalletType.COMPANY_MASTER_ACCOUNT)
                .orElseThrow(() -> new IllegalStateException(
                        "Company master wallet not initialized"
                ));

        WalletEntity agentWallet = walletRepository.findByUserIdAndWalletTypeForUpdate(agent.getUser().getId(), WalletType.AGENT_FLOAT)
                .orElseThrow(() -> new BadRequestException(
                        "Agent float wallet not found"
                ));

        if (agentWallet.getStatus() != WalletStatus.ACTIVE) {
            throw new BadRequestException("Agent float wallet is not active");
        }

        if (agentWallet.getBalance().compareTo(amount) < 0) {
            throw new BadRequestException("Insufficient agent float balance");
        }

        agentWallet.setBalance(normalizeZero(agentWallet.getBalance().subtract(amount)));
        companyWallet.setBalance(normalizeZero(companyWallet.getBalance().add(amount)));

        ledgerService.postJournal(LedgerTransactionType.AGENT_WITHDRAW, description, admin, List.of(
                new JournalLineRequest(agentWallet.getId(), EntryType.DEBIT, amount),
                new JournalLineRequest(companyWallet.getId(), EntryType.CREDIT, amount)
        ));

        walletRepository.save(agentWallet);
        walletRepository.save(companyWallet);
    }

    private static BigDecimal normalizeZero(BigDecimal amount) {
        return amount.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO : amount;
    }

    /**
      validate amount to have at least 2 decimal places only .
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

    private static BigDecimal normalizeScale(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private TransactionJournalResponse toJournalResponse(TransactionJournalEntity journal) {
        List<JournalLineResponse> lines = journal.getLines().stream()
                .map(this::toJournalLineResponse)
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

    private JournalLineResponse toJournalLineResponse(TransactionJournalLineEntity line) {
        WalletEntity wallet = walletRepository.findById(line.getWalletId())
                .orElseThrow(() -> new IllegalStateException("Wallet not found for journal line: " + line.getWalletId()));

        return new JournalLineResponse(
                wallet.getId(),
                wallet.getAccountNumber(),
                ownerName(wallet),
                wallet.getWalletType().name(),
                line.getEntryType().name(),
                line.getAmount()
        );
    }

    private static String ownerName(WalletEntity wallet) {
        String accountName = wallet.getAccountName();
        int suffixIndex = accountName.indexOf(" — ");
        return suffixIndex == -1 ? accountName : accountName.substring(0, suffixIndex);
    }
}
