package com.application.e_wallet.treasury.service;

import com.application.e_wallet.agent.entity.AgentEntity;
import com.application.e_wallet.agent.repository.AgentRepository;
import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.ledger.dto.JournalLineRequest;
import com.application.e_wallet.ledger.entity.EntryType;
import com.application.e_wallet.ledger.entity.LedgerTransactionType;
import com.application.e_wallet.ledger.entity.TransactionJournalEntity;
import com.application.e_wallet.ledger.repository.TransactionJournalRepository;
import com.application.e_wallet.ledger.service.LedgerService;
import com.application.e_wallet.treasury.dto.CommissionTopupResponse;
import com.application.e_wallet.treasury.dto.TreasuryOverviewResponse;
import com.application.e_wallet.treasury.repository.TreasuryRequestRepository;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.user.repository.UserRepository;
import com.application.e_wallet.wallet.entity.WalletEntity;
import com.application.e_wallet.wallet.entity.WalletStatus;
import com.application.e_wallet.wallet.entity.WalletType;
import com.application.e_wallet.wallet.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Float is issued straight from escrow (real money came in), the commission pool is refilled from revenue,
 * and float goes back to the bank through escrow. There is no company master wallet and no minting.
 */
@ExtendWith(MockitoExtension.class)
class TreasuryServiceTest {

    @Mock private WalletRepository walletRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private UserRepository userRepository;
    @Mock private TreasuryRequestRepository treasuryRequestRepository;
    @Mock private TransactionJournalRepository transactionJournalRepository;
    @Mock private LedgerService ledgerService;
    @InjectMocks private TreasuryService treasuryService;

    private UUID adminUserId;
    private UserEntity admin;

    private WalletEntity escrowWallet;
    private WalletEntity revenueWallet;
    private WalletEntity commissionWallet;

    private UUID agentId;
    private UUID agentUserId;
    private AgentEntity agent;
    private WalletEntity agentFloatWallet;

    private static WalletEntity wallet(WalletType type, String balance) {
        return WalletEntity.builder()
                .id(UUID.randomUUID())
                .walletType(type)
                .accountName(type.name())
                .balance(new BigDecimal(balance))
                .status(WalletStatus.ACTIVE)
                .currency("TZS")
                .build();
    }

    @BeforeEach
    void setUp() {
        adminUserId = UUID.randomUUID();
        admin = UserEntity.builder().id(adminUserId).build();
        lenient().when(userRepository.findById(adminUserId)).thenReturn(Optional.of(admin));

        escrowWallet = wallet(WalletType.ESCROW_BANK, "1000.00");
        revenueWallet = wallet(WalletType.REVENUE, "400.00");
        commissionWallet = wallet(WalletType.COMMISSION, "100.00");

        lenient().when(walletRepository.findByWalletTypeForUpdate(WalletType.ESCROW_BANK)).thenReturn(Optional.of(escrowWallet));
        lenient().when(walletRepository.findByWalletTypeForUpdate(WalletType.REVENUE)).thenReturn(Optional.of(revenueWallet));
        lenient().when(walletRepository.findByWalletTypeForUpdate(WalletType.COMMISSION)).thenReturn(Optional.of(commissionWallet));

        agentUserId = UUID.randomUUID();
        UserEntity agentUser = UserEntity.builder().id(agentUserId).build();
        agentId = UUID.randomUUID();
        agent = AgentEntity.builder().id(agentId).user(agentUser).build();

        agentFloatWallet = wallet(WalletType.AGENT_FLOAT, "50.00");

        lenient().when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
        lenient().when(walletRepository.findByUserIdAndWalletTypeForUpdate(agentUserId, WalletType.AGENT_FLOAT))
                .thenReturn(Optional.of(agentFloatWallet));

        lenient().when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TransactionJournalEntity journal = mock(TransactionJournalEntity.class);
        lenient().when(journal.getReferenceNumber()).thenReturn("JRN-TEST");
        lenient().when(journal.getTransactionType()).thenReturn(LedgerTransactionType.TOPUP_COMMISSION);
        lenient().when(ledgerService.postJournal(any(), any(), any(), any())).thenReturn(journal);
    }

    @Test
    void overviewShowsEscrowCommissionPoolAndRevenue() {
        lenient().when(walletRepository.findByWalletType(WalletType.ESCROW_BANK)).thenReturn(Optional.of(escrowWallet));
        lenient().when(walletRepository.sumBalanceByWalletType(WalletType.COMMISSION)).thenReturn(new BigDecimal("100.00"));
        lenient().when(walletRepository.sumBalanceByWalletType(WalletType.REVENUE)).thenReturn(new BigDecimal("400.00"));
        lenient().when(walletRepository.sumBalanceByWalletType(WalletType.AGENT_FLOAT)).thenReturn(new BigDecimal("500.00"));
        lenient().when(treasuryRequestRepository.sumAmountByStatus(any())).thenReturn(BigDecimal.ZERO);
        lenient().when(treasuryRequestRepository.countByStatus(any())).thenReturn(0L);

        TreasuryOverviewResponse overview = treasuryService.getOverview("admin@e-wallet.com");

        assertThat(overview.walletId()).isEqualTo(escrowWallet.getId());
        assertThat(overview.currency()).isEqualTo("TZS");
        assertThat(overview.escrowBalance()).isEqualByComparingTo("1000.00");
        assertThat(overview.commissionPoolBalance()).isEqualByComparingTo("100.00");
        assertThat(overview.revenueBalance()).isEqualByComparingTo("400.00");
        assertThat(overview.totalDistributedFloat()).isEqualByComparingTo("500.00");
        assertThat(overview.createdBy()).isEqualTo("admin@e-wallet.com");
    }

    // ---- float issued from escrow

    @Test
    void topupIssuesFloatFromEscrowSoBothGoUp() {
        treasuryService.topup(agentId, new BigDecimal("300.00"), "deposit checked", adminUserId);

        assertThat(escrowWallet.getBalance()).isEqualTo(new BigDecimal("1300.00"));
        assertThat(agentFloatWallet.getBalance()).isEqualTo(new BigDecimal("350.00"));
        verify(ledgerService).postJournal(eq(LedgerTransactionType.TOPUP_FLOAT), any(), eq(admin), argThat(lines ->
                lines.size() == 2
                && lines.stream().map(JournalLineRequest::walletId).distinct().count() == 2
                && lines.stream().anyMatch(l -> l.walletId().equals(escrowWallet.getId()) && l.entryType() == EntryType.DEBIT)
                && lines.stream().anyMatch(l -> l.walletId().equals(agentFloatWallet.getId()) && l.entryType() == EntryType.CREDIT)
        ));
    }

    @Test
    void topupLocksTheSpecificAgentWalletRow() {
        treasuryService.topup(agentId, new BigDecimal("50.00"), "x", adminUserId);
        verify(walletRepository).findByUserIdAndWalletTypeForUpdate(agentUserId, WalletType.AGENT_FLOAT);
    }

    @Test
    void topupRejectsInactiveAgentFloatWallet() {
        agentFloatWallet.setStatus(WalletStatus.SUSPENDED);

        assertThatThrownBy(() -> treasuryService.topup(agentId, new BigDecimal("10.00"), "x", adminUserId))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(ledgerService);
    }

    @Test
    void topupRejectsNonPositiveAmountAndExtraDecimals() {
        assertThatThrownBy(() -> treasuryService.topup(agentId, BigDecimal.ZERO, "x", adminUserId)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> treasuryService.topup(agentId, new BigDecimal("10.005"), "x", adminUserId)).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(ledgerService);
    }

    // ---- float paid out to the bank

    @Test
    void withdrawPaysFloatOutThroughEscrowSoBothGoDown() {
        agentFloatWallet.setBalance(new BigDecimal("100.00"));

        treasuryService.withdraw(agentId, new BigDecimal("40.00"), "to bank", adminUserId);

        assertThat(escrowWallet.getBalance()).isEqualTo(new BigDecimal("960.00"));
        assertThat(agentFloatWallet.getBalance()).isEqualTo(new BigDecimal("60.00"));
        verify(ledgerService).postJournal(eq(LedgerTransactionType.AGENT_WITHDRAW), any(), eq(admin), argThat(lines ->
                lines.size() == 2
                && lines.stream().anyMatch(l -> l.walletId().equals(agentFloatWallet.getId()) && l.entryType() == EntryType.DEBIT)
                && lines.stream().anyMatch(l -> l.walletId().equals(escrowWallet.getId()) && l.entryType() == EntryType.CREDIT)
        ));
    }

    @Test
    void withdrawRejectsWhenAgentFloatBalanceInsufficient() {
        agentFloatWallet.setBalance(new BigDecimal("10.00"));

        assertThatThrownBy(() -> treasuryService.withdraw(agentId, new BigDecimal("10.01"), "x", adminUserId))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void withdrawRejectsInactiveAgentFloatWallet() {
        agentFloatWallet.setStatus(WalletStatus.SUSPENDED);

        assertThatThrownBy(() -> treasuryService.withdraw(agentId, new BigDecimal("5.00"), "x", adminUserId))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(ledgerService);
    }

    @Test
    void withdrawSucceedsWhenAmountEqualsAgentBalance() {
        agentFloatWallet.setBalance(new BigDecimal("25.00"));

        treasuryService.withdraw(agentId, new BigDecimal("25.00"), "all", adminUserId);

        assertThat(agentFloatWallet.getBalance()).isEqualTo(BigDecimal.ZERO);
    }

    // ---- commission pool refilled from revenue

    @Test
    void refillMovesRevenueIntoTheCommissionPoolWithoutTouchingEscrow() {
        CommissionTopupResponse response = treasuryService.fundCommission(new BigDecimal("150.00"), "month start", adminUserId);

        assertThat(revenueWallet.getBalance()).isEqualTo(new BigDecimal("250.00"));
        assertThat(commissionWallet.getBalance()).isEqualTo(new BigDecimal("250.00"));
        assertThat(escrowWallet.getBalance()).isEqualTo(new BigDecimal("1000.00"));
        assertThat(response.status()).isEqualTo("COMPLETED");
        verify(ledgerService).postJournal(eq(LedgerTransactionType.TOPUP_COMMISSION), any(), eq(admin), argThat(lines ->
                lines.size() == 2
                && lines.stream().anyMatch(l -> l.walletId().equals(revenueWallet.getId()) && l.entryType() == EntryType.DEBIT)
                && lines.stream().anyMatch(l -> l.walletId().equals(commissionWallet.getId()) && l.entryType() == EntryType.CREDIT)
        ));
    }

    @Test
    void refillRejectsWhenRevenueIsNotEnough() {
        assertThatThrownBy(() -> treasuryService.fundCommission(new BigDecimal("400.01"), "too much", adminUserId))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("revenue");
        verifyNoInteractions(ledgerService);
        assertThat(revenueWallet.getBalance()).isEqualTo(new BigDecimal("400.00"));
    }

    @Test
    void refillSucceedsWhenAmountEqualsRevenue() {
        treasuryService.fundCommission(new BigDecimal("400.00"), "all", adminUserId);

        assertThat(revenueWallet.getBalance()).isEqualTo(BigDecimal.ZERO);
    }
}
