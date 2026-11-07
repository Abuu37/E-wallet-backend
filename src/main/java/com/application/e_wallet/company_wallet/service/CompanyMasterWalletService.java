package com.application.e_wallet.company_wallet.service;

import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.company_wallet.dto.AgentWithdrawRequest;
import com.application.e_wallet.company_wallet.dto.AllocateFloatRequest;
import com.application.e_wallet.company_wallet.dto.CompanyMasterWalletResponse;
import com.application.e_wallet.company_wallet.dto.JournalLineResponse;
import com.application.e_wallet.company_wallet.dto.MintFloatRequest;
import com.application.e_wallet.company_wallet.dto.TransactionJournalResponse;
import com.application.e_wallet.company_wallet.entity.CompanyMasterWalletEntity;
import com.application.e_wallet.company_wallet.repository.CompanyMasterWalletRepository;
import com.application.e_wallet.financial_account.entity.FinancialAccountEntity;
import com.application.e_wallet.financial_account.entity.FinancialAccountStatus;
import com.application.e_wallet.financial_account.repository.FinancialAccountRepository;
import com.application.e_wallet.ledger.entity.EntryType;
import com.application.e_wallet.ledger.entity.LedgerAccountType;
import com.application.e_wallet.ledger.entity.TransactionJournalEntity;
import com.application.e_wallet.ledger.entity.TransactionType;
import com.application.e_wallet.ledger.repository.TransactionJournalRepository;
import com.application.e_wallet.ledger.service.JournalLineRequest;
import com.application.e_wallet.ledger.service.LedgerService;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CompanyMasterWalletService {

    private final CompanyMasterWalletRepository companyMasterWalletRepository;
    private final FinancialAccountRepository financialAccountRepository;
    private final UserRepository userRepository;
    private final TransactionJournalRepository transactionJournalRepository;
    private final LedgerService ledgerService;

    public CompanyMasterWalletResponse getWallet() {
        CompanyMasterWalletEntity wallet = companyMasterWalletRepository.findSingleton()
                .orElseThrow(() -> new IllegalStateException("Company master wallet not initialized"));
        return toResponse(wallet);
    }

    public List<TransactionJournalResponse> getJournalHistory() {
        return transactionJournalRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(this::toJournalResponse)
                .toList();
    }

    @Transactional
    public CompanyMasterWalletResponse mint(MintFloatRequest request, UUID adminUserId) {
        if (request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException("Mint amount must be positive");
        }

        UserEntity admin = resolveAdmin(adminUserId);
        CompanyMasterWalletEntity wallet = lockWallet();

        wallet.setUnallocatedFloat(normalizeZero(wallet.getUnallocatedFloat().add(request.amount())));

        ledgerService.postJournal(TransactionType.MINT_FLOAT, request.description(), admin, List.of(
                new JournalLineRequest(LedgerAccountType.ESCROW_BANK, wallet.getId(), EntryType.DEBIT, request.amount()),
                new JournalLineRequest(LedgerAccountType.COMPANY_UNALLOCATED, wallet.getId(), EntryType.CREDIT, request.amount())
        ));

        companyMasterWalletRepository.save(wallet);

        return toResponse(wallet);
    }

    @Transactional
    public CompanyMasterWalletResponse allocate(AllocateFloatRequest request, UUID adminUserId) {
        if (request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException("Allocate amount must be positive");
        }

        UserEntity admin = resolveAdmin(adminUserId);
        CompanyMasterWalletEntity wallet = lockWallet();
        FinancialAccountEntity agentAccount = resolveActiveAgentAccount(request.agentId());

        if (wallet.getUnallocatedFloat().compareTo(request.amount()) < 0) {
            throw new BadRequestException("Insufficient unallocated float");
        }

        wallet.setUnallocatedFloat(normalizeZero(wallet.getUnallocatedFloat().subtract(request.amount())));
        wallet.setTotalDistributedFloat(normalizeZero(wallet.getTotalDistributedFloat().add(request.amount())));
        agentAccount.setCurrentBalance(normalizeZero(agentAccount.getCurrentBalance().add(request.amount())));

        ledgerService.postJournal(TransactionType.ALLOCATE_FLOAT, request.description(), admin, List.of(
                new JournalLineRequest(LedgerAccountType.COMPANY_UNALLOCATED, wallet.getId(), EntryType.DEBIT, request.amount()),
                new JournalLineRequest(LedgerAccountType.AGENT_FLOAT, agentAccount.getId(), EntryType.CREDIT, request.amount())
        ));

        companyMasterWalletRepository.save(wallet);
        financialAccountRepository.save(agentAccount);

        return toResponse(wallet);
    }

    @Transactional
    public CompanyMasterWalletResponse withdraw(AgentWithdrawRequest request, UUID adminUserId) {
        if (request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException("Withdraw amount must be positive");
        }

        UserEntity admin = resolveAdmin(adminUserId);
        CompanyMasterWalletEntity wallet = lockWallet();
        FinancialAccountEntity agentAccount = resolveActiveAgentAccount(request.agentId());

        if (agentAccount.getCurrentBalance().compareTo(request.amount()) < 0) {
            throw new BadRequestException("Insufficient agent float balance");
        }

        agentAccount.setCurrentBalance(normalizeZero(agentAccount.getCurrentBalance().subtract(request.amount())));
        wallet.setUnallocatedFloat(normalizeZero(wallet.getUnallocatedFloat().add(request.amount())));
        wallet.setTotalDistributedFloat(normalizeZero(wallet.getTotalDistributedFloat().subtract(request.amount())));

        ledgerService.postJournal(TransactionType.AGENT_WITHDRAW, request.description(), admin, List.of(
                new JournalLineRequest(LedgerAccountType.AGENT_FLOAT, agentAccount.getId(), EntryType.DEBIT, request.amount()),
                new JournalLineRequest(LedgerAccountType.COMPANY_UNALLOCATED, wallet.getId(), EntryType.CREDIT, request.amount())
        ));

        companyMasterWalletRepository.save(wallet);
        financialAccountRepository.save(agentAccount);

        return toResponse(wallet);
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

    private UserEntity resolveAdmin(UUID adminUserId) {
        return userRepository.findById(adminUserId)
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found"));
    }

    private CompanyMasterWalletEntity lockWallet() {
        return companyMasterWalletRepository.findSingletonForUpdate()
                .orElseThrow(() -> new IllegalStateException("Company master wallet not initialized"));
    }

    private FinancialAccountEntity resolveActiveAgentAccount(UUID agentId) {
        FinancialAccountEntity agentAccount = financialAccountRepository.findByWallet_Agent_Id(agentId)
                .orElseThrow(() -> new BadRequestException("Agent float account not found"));

        if (agentAccount.getStatus() != FinancialAccountStatus.ACTIVE) {
            throw new BadRequestException("Agent float account is not active");
        }

        return agentAccount;
    }

    private CompanyMasterWalletResponse toResponse(CompanyMasterWalletEntity wallet) {
        return new CompanyMasterWalletResponse(
                wallet.getId(),
                wallet.getWalletName(),
                wallet.getCurrency(),
                wallet.getUnallocatedFloat(),
                wallet.getTotalDistributedFloat(),
                wallet.getEscrowBankAccountNo()
        );
    }

    private TransactionJournalResponse toJournalResponse(TransactionJournalEntity journal) {
        List<JournalLineResponse> lines = journal.getLines().stream()
                .map(line -> new JournalLineResponse(
                        line.getLedgerAccountType().name(),
                        line.getAccountRefId(),
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
