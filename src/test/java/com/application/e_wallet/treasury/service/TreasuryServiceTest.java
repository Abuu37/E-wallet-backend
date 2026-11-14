package com.application.e_wallet.treasury.service;

import com.application.e_wallet.agent.entity.AgentEntity;
import com.application.e_wallet.agent.repository.AgentRepository;
import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.ledger.dto.JournalLineRequest;
import com.application.e_wallet.ledger.entity.EntryType;
import com.application.e_wallet.ledger.entity.TransactionType;
import com.application.e_wallet.ledger.repository.TransactionJournalRepository;
import com.application.e_wallet.ledger.service.LedgerService;
import com.application.e_wallet.treasury.dto.MintRequest;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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

    private WalletEntity companyWallet;
    private WalletEntity escrowWallet;

    private UUID agentId;
    private UUID agentUserId;
    private AgentEntity agent;
    private WalletEntity agentFloatWallet;

    @BeforeEach
    void setUp() {
        adminUserId = UUID.randomUUID();
        admin = UserEntity.builder().id(adminUserId).build();
        lenient().when(userRepository.findById(adminUserId)).thenReturn(Optional.of(admin));

        companyWallet = WalletEntity.builder()
                .id(UUID.randomUUID())
                .walletType(WalletType.COMPANY_MASTER_ACCOUNT)
                .balance(new BigDecimal("1000.00"))
                .status(WalletStatus.ACTIVE)
                .currency("TZS")
                .build();

        escrowWallet = WalletEntity.builder()
                .id(UUID.randomUUID())
                .walletType(WalletType.ESCROW_BANK)
                .balance(new BigDecimal("1000.00"))
                .status(WalletStatus.ACTIVE)
                .currency("TZS")
                .build();

        lenient().when(walletRepository.findByWalletTypeForUpdate(WalletType.COMPANY_MASTER_ACCOUNT))
                .thenReturn(Optional.of(companyWallet));
        lenient().when(walletRepository.findByWalletTypeForUpdate(WalletType.ESCROW_BANK))
                .thenReturn(Optional.of(escrowWallet));

        agentUserId = UUID.randomUUID();
        UserEntity agentUser = UserEntity.builder().id(agentUserId).build();
        agentId = UUID.randomUUID();
        agent = AgentEntity.builder().id(agentId).user(agentUser).build();

        agentFloatWallet = WalletEntity.builder()
                .id(UUID.randomUUID())
                .walletType(WalletType.AGENT_FLOAT)
                .balance(new BigDecimal("50.00"))
                .status(WalletStatus.ACTIVE)
                .currency("TZS")
                .build();

        lenient().when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
        lenient().when(walletRepository.findByUserIdAndWalletTypeForUpdate(agentUserId, WalletType.AGENT_FLOAT))
                .thenReturn(Optional.of(agentFloatWallet));

        lenient().when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void mintPostsTwoDistinctWalletLegs() {
        treasuryService.mint(new MintRequest(new BigDecimal("200.00"), "bank deposit"), adminUserId);

        verify(ledgerService).postJournal(eq(TransactionType.MINT_FLOAT), any(), eq(admin), argThat(lines ->
                lines.size() == 2
                && lines.stream().map(JournalLineRequest::walletId).distinct().count() == 2
                && lines.stream().anyMatch(l -> l.walletId().equals(escrowWallet.getId()) && l.entryType() == EntryType.DEBIT)
                && lines.stream().anyMatch(l -> l.walletId().equals(companyWallet.getId()) && l.entryType() == EntryType.CREDIT)
        ));
        assertThat(companyWallet.getBalance()).isEqualTo(new BigDecimal("1200.00"));
        assertThat(escrowWallet.getBalance()).isEqualTo(new BigDecimal("1200.00"));
    }

    @Test
    void mintRejectsNonPositiveAmount() {
        assertThatThrownBy(() -> treasuryService.mint(new MintRequest(BigDecimal.ZERO, "x"), adminUserId))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(ledgerService);
    }

    @Test
    void mintRejectsAmountWithMoreThanTwoDecimalPlaces() {
        assertThatThrownBy(() -> treasuryService.mint(
                new MintRequest(new BigDecimal("10.005"), "x"), adminUserId))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(ledgerService);
    }

    @Test
    void topupRejectsInactiveAgentFloatWallet() {
        agentFloatWallet.setStatus(WalletStatus.SUSPENDED);

        assertThatThrownBy(() -> treasuryService.topup(agentId, new BigDecimal("10.00"), "x", adminUserId))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(ledgerService);
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
    void topupLocksTheSpecificAgentWalletRow() {
        treasuryService.topup(agentId, new BigDecimal("50.00"), "x", adminUserId);
        verify(walletRepository).findByUserIdAndWalletTypeForUpdate(agentUserId, WalletType.AGENT_FLOAT);
    }

    @Test
    void topupMovesFloatFromUnallocatedToAgent() {
        treasuryService.topup(agentId, new BigDecimal("300.00"), "topup", adminUserId);

        assertThat(companyWallet.getBalance()).isEqualTo(new BigDecimal("700.00"));
        assertThat(agentFloatWallet.getBalance()).isEqualTo(new BigDecimal("350.00"));
        verify(walletRepository).save(companyWallet);
        verify(walletRepository).save(agentFloatWallet);
        verify(ledgerService).postJournal(eq(TransactionType.TOPUP_FLOAT), any(), eq(admin), argThat(lines ->
                lines.size() == 2
                && lines.stream().map(JournalLineRequest::walletId).distinct().count() == 2
                && lines.stream().anyMatch(l -> l.walletId().equals(companyWallet.getId()) && l.entryType() == EntryType.DEBIT)
                && lines.stream().anyMatch(l -> l.walletId().equals(agentFloatWallet.getId()) && l.entryType() == EntryType.CREDIT)
        ));
    }

    @Test
    void topupSucceedsWhenAmountEqualsAvailableUnallocatedFloat() {
        treasuryService.topup(agentId, new BigDecimal("1000.00"), "all of it", adminUserId);

        assertThat(companyWallet.getBalance()).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    void topupRejectsWhenUnallocatedFloatInsufficient() {
        assertThatThrownBy(() -> treasuryService.topup(
                agentId, new BigDecimal("1000.01"), "too much", adminUserId))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(ledgerService);
    }

    @Test
    void withdrawMovesFloatFromAgentToUnallocated() {
        agentFloatWallet.setBalance(new BigDecimal("100.00"));

        treasuryService.withdraw(agentId, new BigDecimal("40.00"), "return", adminUserId);

        assertThat(companyWallet.getBalance()).isEqualTo(new BigDecimal("1040.00"));
        assertThat(agentFloatWallet.getBalance()).isEqualTo(new BigDecimal("60.00"));
        verify(walletRepository).save(companyWallet);
        verify(walletRepository).save(agentFloatWallet);
        verify(ledgerService).postJournal(eq(TransactionType.AGENT_WITHDRAW), any(), eq(admin), argThat(lines ->
                lines.size() == 2
                && lines.stream().map(JournalLineRequest::walletId).distinct().count() == 2
                && lines.stream().anyMatch(l -> l.walletId().equals(agentFloatWallet.getId()) && l.entryType() == EntryType.DEBIT)
                && lines.stream().anyMatch(l -> l.walletId().equals(companyWallet.getId()) && l.entryType() == EntryType.CREDIT)
        ));
    }

    @Test
    void withdrawSucceedsWhenAmountEqualsAgentBalance() {
        agentFloatWallet.setBalance(new BigDecimal("25.00"));

        treasuryService.withdraw(agentId, new BigDecimal("25.00"), "all", adminUserId);

        assertThat(agentFloatWallet.getBalance()).isEqualTo(BigDecimal.ZERO);
    }
}
