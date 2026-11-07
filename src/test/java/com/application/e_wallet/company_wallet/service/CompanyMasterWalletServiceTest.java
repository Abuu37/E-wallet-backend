package com.application.e_wallet.company_wallet.service;

import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.company_wallet.dto.AgentWithdrawRequest;
import com.application.e_wallet.company_wallet.dto.AllocateFloatRequest;
import com.application.e_wallet.company_wallet.dto.MintFloatRequest;
import com.application.e_wallet.company_wallet.entity.CompanyMasterWalletEntity;
import com.application.e_wallet.company_wallet.repository.CompanyMasterWalletRepository;
import com.application.e_wallet.financial_account.entity.FinancialAccountEntity;
import com.application.e_wallet.financial_account.entity.FinancialAccountStatus;
import com.application.e_wallet.financial_account.repository.FinancialAccountRepository;
import com.application.e_wallet.ledger.entity.EntryType;
import com.application.e_wallet.ledger.entity.LedgerAccountType;
import com.application.e_wallet.ledger.entity.TransactionType;
import com.application.e_wallet.ledger.repository.TransactionJournalRepository;
import com.application.e_wallet.ledger.service.LedgerService;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompanyMasterWalletServiceTest {

    @Mock private CompanyMasterWalletRepository companyMasterWalletRepository;
    @Mock private FinancialAccountRepository financialAccountRepository;
    @Mock private UserRepository userRepository;
    @Mock private TransactionJournalRepository transactionJournalRepository;
    @Mock private LedgerService ledgerService;
    @InjectMocks private CompanyMasterWalletService companyMasterWalletService;

    private UUID adminUserId;
    private UserEntity admin;
    private CompanyMasterWalletEntity wallet;

    @BeforeEach
    void setUp() {
        adminUserId = UUID.randomUUID();
        admin = UserEntity.builder().id(adminUserId).build();
        wallet = CompanyMasterWalletEntity.builder()
                .id(UUID.randomUUID())
                .singletonKey((short) 1)
                .unallocatedFloat(new BigDecimal("1000.00"))
                .totalDistributedFloat(new BigDecimal("500.00"))
                .currency("TZS")
                .build();
        lenient().when(userRepository.findById(adminUserId)).thenReturn(Optional.of(admin));
        lenient().when(companyMasterWalletRepository.findSingletonForUpdate()).thenReturn(Optional.of(wallet));
        lenient().when(companyMasterWalletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void mintIncreasesUnallocatedFloatAndPostsBalancedJournal() {
        companyMasterWalletService.mint(new MintFloatRequest(new BigDecimal("200.00"), "bank deposit"), adminUserId);

        assertThat(wallet.getUnallocatedFloat()).isEqualTo(new BigDecimal("1200.00"));
        verify(companyMasterWalletRepository).findSingletonForUpdate();
        verify(ledgerService).postJournal(eq(TransactionType.MINT_FLOAT), any(), eq(admin), argThat(lines ->
                lines.size() == 2
                && lines.stream().anyMatch(l -> l.ledgerAccountType() == LedgerAccountType.ESCROW_BANK && l.entryType() == EntryType.DEBIT)
                && lines.stream().anyMatch(l -> l.ledgerAccountType() == LedgerAccountType.COMPANY_UNALLOCATED && l.entryType() == EntryType.CREDIT)
        ));
    }

    @Test
    void mintRejectsNonPositiveAmount() {
        assertThatThrownBy(() -> companyMasterWalletService.mint(new MintFloatRequest(BigDecimal.ZERO, "x"), adminUserId))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(ledgerService);
    }

    @Test
    void mintRejectsAmountWithMoreThanTwoDecimalPlaces() {
        assertThatThrownBy(() -> companyMasterWalletService.mint(
                new MintFloatRequest(new BigDecimal("10.005"), "x"), adminUserId))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(ledgerService);
    }

    @Test
    void allocateMovesFloatFromUnallocatedToAgent() {
        FinancialAccountEntity agentAccount = FinancialAccountEntity.builder()
                .id(UUID.randomUUID())
                .currentBalance(new BigDecimal("50.00"))
                .status(FinancialAccountStatus.ACTIVE)
                .build();
        UUID agentId = UUID.randomUUID();
        when(financialAccountRepository.findByWallet_Agent_Id(agentId)).thenReturn(Optional.of(agentAccount));

        companyMasterWalletService.allocate(new AllocateFloatRequest(agentId, new BigDecimal("300.00"), "topup"), adminUserId);

        assertThat(wallet.getUnallocatedFloat()).isEqualTo(new BigDecimal("700.00"));
        assertThat(wallet.getTotalDistributedFloat()).isEqualTo(new BigDecimal("800.00"));
        assertThat(agentAccount.getCurrentBalance()).isEqualTo(new BigDecimal("350.00"));
        verify(companyMasterWalletRepository).findSingletonForUpdate();
    }

    @Test
    void allocateSucceedsWhenAmountEqualsAvailableUnallocatedFloat() {
        FinancialAccountEntity agentAccount = FinancialAccountEntity.builder()
                .id(UUID.randomUUID()).currentBalance(BigDecimal.ZERO).status(FinancialAccountStatus.ACTIVE).build();
        UUID agentId = UUID.randomUUID();
        when(financialAccountRepository.findByWallet_Agent_Id(agentId)).thenReturn(Optional.of(agentAccount));

        companyMasterWalletService.allocate(new AllocateFloatRequest(agentId, new BigDecimal("1000.00"), "all of it"), adminUserId);

        assertThat(wallet.getUnallocatedFloat()).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    void allocateRejectsWhenUnallocatedFloatInsufficient() {
        FinancialAccountEntity agentAccount = FinancialAccountEntity.builder()
                .id(UUID.randomUUID()).currentBalance(BigDecimal.ZERO).status(FinancialAccountStatus.ACTIVE).build();
        UUID agentId = UUID.randomUUID();
        when(financialAccountRepository.findByWallet_Agent_Id(agentId)).thenReturn(Optional.of(agentAccount));

        assertThatThrownBy(() -> companyMasterWalletService.allocate(
                new AllocateFloatRequest(agentId, new BigDecimal("1000.01"), "too much"), adminUserId))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(ledgerService);
    }

    @Test
    void allocateRejectsInactiveAgentAccount() {
        FinancialAccountEntity agentAccount = FinancialAccountEntity.builder()
                .id(UUID.randomUUID()).currentBalance(BigDecimal.ZERO).status(FinancialAccountStatus.SUSPENDED).build();
        UUID agentId = UUID.randomUUID();
        when(financialAccountRepository.findByWallet_Agent_Id(agentId)).thenReturn(Optional.of(agentAccount));

        assertThatThrownBy(() -> companyMasterWalletService.allocate(
                new AllocateFloatRequest(agentId, new BigDecimal("10.00"), "x"), adminUserId))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(ledgerService);
    }

    @Test
    void withdrawMovesFloatFromAgentToUnallocated() {
        FinancialAccountEntity agentAccount = FinancialAccountEntity.builder()
                .id(UUID.randomUUID()).currentBalance(new BigDecimal("100.00")).status(FinancialAccountStatus.ACTIVE).build();
        UUID agentId = UUID.randomUUID();
        when(financialAccountRepository.findByWallet_Agent_Id(agentId)).thenReturn(Optional.of(agentAccount));

        companyMasterWalletService.withdraw(new AgentWithdrawRequest(agentId, new BigDecimal("40.00"), "return"), adminUserId);

        assertThat(wallet.getUnallocatedFloat()).isEqualTo(new BigDecimal("1040.00"));
        assertThat(wallet.getTotalDistributedFloat()).isEqualTo(new BigDecimal("460.00"));
        assertThat(agentAccount.getCurrentBalance()).isEqualTo(new BigDecimal("60.00"));
        verify(companyMasterWalletRepository).findSingletonForUpdate();
    }

    @Test
    void withdrawSucceedsWhenAmountEqualsAgentBalance() {
        FinancialAccountEntity agentAccount = FinancialAccountEntity.builder()
                .id(UUID.randomUUID()).currentBalance(new BigDecimal("25.00")).status(FinancialAccountStatus.ACTIVE).build();
        UUID agentId = UUID.randomUUID();
        when(financialAccountRepository.findByWallet_Agent_Id(agentId)).thenReturn(Optional.of(agentAccount));

        companyMasterWalletService.withdraw(new AgentWithdrawRequest(agentId, new BigDecimal("25.00"), "all"), adminUserId);

        assertThat(agentAccount.getCurrentBalance()).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    void withdrawRejectsWhenAgentBalanceInsufficient() {
        FinancialAccountEntity agentAccount = FinancialAccountEntity.builder()
                .id(UUID.randomUUID()).currentBalance(new BigDecimal("10.00")).status(FinancialAccountStatus.ACTIVE).build();
        UUID agentId = UUID.randomUUID();
        when(financialAccountRepository.findByWallet_Agent_Id(agentId)).thenReturn(Optional.of(agentAccount));

        assertThatThrownBy(() -> companyMasterWalletService.withdraw(
                new AgentWithdrawRequest(agentId, new BigDecimal("10.01"), "x"), adminUserId))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(ledgerService);
    }

    @Test
    void withdrawRejectsInactiveAgentAccount() {
        FinancialAccountEntity agentAccount = FinancialAccountEntity.builder()
                .id(UUID.randomUUID()).currentBalance(new BigDecimal("10.00")).status(FinancialAccountStatus.CLOSED).build();
        UUID agentId = UUID.randomUUID();
        when(financialAccountRepository.findByWallet_Agent_Id(agentId)).thenReturn(Optional.of(agentAccount));

        assertThatThrownBy(() -> companyMasterWalletService.withdraw(
                new AgentWithdrawRequest(agentId, new BigDecimal("5.00"), "x"), adminUserId))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(ledgerService);
    }
}
