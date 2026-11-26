package com.application.e_wallet.treasury.operations;

import com.application.e_wallet.audit.service.AuditService;
import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.ledger.dto.JournalLineRequest;
import com.application.e_wallet.ledger.entity.EntryType;
import com.application.e_wallet.ledger.entity.LedgerTransactionType;
import com.application.e_wallet.ledger.service.LedgerService;
import com.application.e_wallet.staff.repository.StaffRepository;
import com.application.e_wallet.transaction.entity.TransactionEntity;
import com.application.e_wallet.transaction.repository.TransactionRepository;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.user.repository.UserRepository;
import com.application.e_wallet.wallet.entity.WalletEntity;
import com.application.e_wallet.wallet.entity.WalletStatus;
import com.application.e_wallet.wallet.entity.WalletType;
import com.application.e_wallet.wallet.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Treasury moves that need two different staff: nothing moves until a second person approves. */
@ExtendWith(MockitoExtension.class)
class TreasuryOperationServiceTest {

    @Mock private TreasuryOperationRepository operations;
    @Mock private WalletRepository walletRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private LedgerService ledgerService;
    @Mock private UserRepository userRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private AuditService auditService;
    @InjectMocks private TreasuryOperationService service;

    private UserEntity asker, approver;
    private WalletEntity revenue, escrow, sender, recipient;
    private TransactionEntity transfer;

    private static WalletEntity wallet(WalletType type, String balance) {
        return WalletEntity.builder().id(UUID.randomUUID()).user(UserEntity.builder().id(UUID.randomUUID()).build()).walletType(type)
                .balance(new BigDecimal(balance)).currency("TZS").status(WalletStatus.ACTIVE).build();
    }

    @BeforeEach
    void setUp() {
        asker = UserEntity.builder().id(UUID.randomUUID()).build();
        approver = UserEntity.builder().id(UUID.randomUUID()).build();
        revenue = wallet(WalletType.REVENUE, "40000.00");
        escrow = wallet(WalletType.ESCROW_BANK, "900000.00");
        sender = wallet(WalletType.CUSTOMER_MAIN, "10000.00");
        recipient = wallet(WalletType.CUSTOMER_MAIN, "70000.00");
        transfer = TransactionEntity.builder().id(UUID.randomUUID()).referenceNo("TRF-1").senderWallet(sender).receiverWallet(recipient)
                .amount(new BigDecimal("50000")).fee(new BigDecimal("600")).transactionType("TRANSFER").status("COMPLETED").build();

        lenient().when(userRepository.findById(approver.getId())).thenReturn(Optional.of(approver));
        lenient().when(walletRepository.findByWalletType(WalletType.REVENUE)).thenReturn(Optional.of(revenue));
        lenient().when(walletRepository.findByWalletType(WalletType.ESCROW_BANK)).thenReturn(Optional.of(escrow));
        lenient().when(walletRepository.lockAllOrdered(anyCollection())).thenReturn(List.of(revenue, escrow, sender, recipient));
        lenient().when(transactionRepository.findByReferenceNo("TRF-1")).thenReturn(Optional.of(transfer));
        lenient().when(staffRepository.findByUserId(any())).thenReturn(Optional.empty());
        lenient().when(operations.save(any(TreasuryOperationEntity.class))).thenAnswer(inv -> {
            TreasuryOperationEntity o = inv.getArgument(0);
            if (o.getId() == null) {
                o.setId(UUID.randomUUID());
            }
            if (o.getOpRef() == null) {
                o.setOpRef("OP-TEST0001");
            }
            return o;
        });
    }

    private TreasuryOperationEntity pending(OperationType type, String amount, String target) {
        TreasuryOperationEntity op = TreasuryOperationEntity.builder().id(UUID.randomUUID()).opRef("OP-1").opType(type).amount(new BigDecimal(amount))
                .reason("because").targetReference(target).requestedBy(asker.getId()).build();
        lenient().when(operations.lockById(op.getId())).thenReturn(Optional.of(op));
        return op;
    }

    // ------------------------------------------------------------------ asking
    @Test
    void askingMovesNothing() {
        OperationResponse response = service.request(asker.getId(), new OperationRequest(OperationType.SWEEP, new BigDecimal("30000"), "monthly sweep", null));
        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.askedByYou()).isTrue();
        assertThat(revenue.getBalance()).isEqualByComparingTo("40000.00");
        verify(ledgerService, never()).postJournal(any(), any(), any(), any());
    }

    @Test
    void aReversalTakesItsAmountFromTheTransfer() {
        OperationResponse response = service.request(asker.getId(), new OperationRequest(OperationType.REVERSAL, null, "wrong number", "TRF-1"));
        assertThat(response.amount()).isEqualByComparingTo("50000");
        assertThat(response.targetReference()).isEqualTo("TRF-1");
    }

    @Test
    void onlyACompletedTransferCanBeReversedAndOnlyOnce() {
        transfer.setTransactionType("CASH_OUT");
        assertThatThrownBy(() -> service.request(asker.getId(), new OperationRequest(OperationType.REVERSAL, null, "x", "TRF-1")))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("wallet-to-wallet");
        transfer.setTransactionType("TRANSFER");
        lenient().when(operations.existsByTargetReferenceAndOpTypeAndStatusIn(eq("TRF-1"), eq(OperationType.REVERSAL), anyCollection())).thenReturn(true);
        assertThatThrownBy(() -> service.request(asker.getId(), new OperationRequest(OperationType.REVERSAL, null, "x", "TRF-1")))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("already has a reversal");
    }

    // ------------------------------------------------------------------ the two-person rule
    @Test
    void theAskerCannotApproveTheirOwnRequest() {
        TreasuryOperationEntity op = pending(OperationType.SWEEP, "30000", null);
        assertThatThrownBy(() -> service.approve(op.getId(), asker.getId())).isInstanceOf(BadRequestException.class).hasMessageContaining("second staff member");
        assertThat(op.getStatus()).isEqualTo(OperationStatus.PENDING);
        verify(ledgerService, never()).postJournal(any(), any(), any(), any());
    }

    @Test
    void aDecidedOperationCannotBeDecidedAgain() {
        TreasuryOperationEntity op = pending(OperationType.SWEEP, "30000", null);
        op.setStatus(OperationStatus.REJECTED);
        assertThatThrownBy(() -> service.approve(op.getId(), approver.getId())).isInstanceOf(BadRequestException.class).hasMessageContaining("pending");
    }

    // ------------------------------------------------------------------ the moves
    @Test
    void aSweepTakesRevenueAndEscrowDownTogetherWithABalancedJournal() {
        TreasuryOperationEntity op = pending(OperationType.SWEEP, "30000", null);

        OperationResponse response = service.approve(op.getId(), approver.getId());

        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(revenue.getBalance()).isEqualByComparingTo("10000.00");
        assertThat(escrow.getBalance()).isEqualByComparingTo("870000.00");
        @SuppressWarnings("unchecked") ArgumentCaptor<List<JournalLineRequest>> lines = ArgumentCaptor.forClass(List.class);
        verify(ledgerService).postJournal(eq(LedgerTransactionType.TREASURY_SWEEP), any(), eq(approver), lines.capture());
        assertThat(lines.getValue()).containsExactlyInAnyOrder(
                new JournalLineRequest(revenue.getId(), EntryType.DEBIT, new BigDecimal("30000")),
                new JournalLineRequest(escrow.getId(), EntryType.CREDIT, new BigDecimal("30000")));
    }

    @Test
    void aSweepCannotTakeMoreThanRevenueHolds() {
        TreasuryOperationEntity op = pending(OperationType.SWEEP, "50000", null);
        assertThatThrownBy(() -> service.approve(op.getId(), approver.getId())).isInstanceOf(BadRequestException.class).hasMessageContaining("Revenue only holds");
        assertThat(escrow.getBalance()).isEqualByComparingTo("900000.00");
    }

    @Test
    void aCompanyPayInRaisesEscrowAndRevenueTogether() {
        TreasuryOperationEntity op = pending(OperationType.ADJUSTMENT_PAY_IN, "100000", null);
        service.approve(op.getId(), approver.getId());
        assertThat(escrow.getBalance()).isEqualByComparingTo("1000000.00");
        assertThat(revenue.getBalance()).isEqualByComparingTo("140000.00");
        verify(ledgerService).postJournal(eq(LedgerTransactionType.TREASURY_ADJUSTMENT), any(), eq(approver), any());
    }

    @Test
    void aBankChargeLowersEscrowAndRevenueTogether() {
        TreasuryOperationEntity op = pending(OperationType.ADJUSTMENT_CHARGE, "5000", null);
        service.approve(op.getId(), approver.getId());
        assertThat(escrow.getBalance()).isEqualByComparingTo("895000.00");
        assertThat(revenue.getBalance()).isEqualByComparingTo("35000.00");
    }

    @Test
    void aReversalGivesTheAmountAndTheFeeBackToTheSender() {
        TreasuryOperationEntity op = pending(OperationType.REVERSAL, "50000", "TRF-1");

        OperationResponse response = service.approve(op.getId(), approver.getId());

        assertThat(recipient.getBalance()).isEqualByComparingTo("20000.00");
        assertThat(sender.getBalance()).isEqualByComparingTo("60600.00");
        assertThat(revenue.getBalance()).isEqualByComparingTo("39400.00");
        assertThat(response.resultReference()).startsWith("REV-");
        @SuppressWarnings("unchecked") ArgumentCaptor<List<JournalLineRequest>> lines = ArgumentCaptor.forClass(List.class);
        verify(ledgerService).postJournal(eq(LedgerTransactionType.TRANSFER_REVERSAL), any(), eq(approver), lines.capture());
        assertThat(lines.getValue()).containsExactlyInAnyOrder(
                new JournalLineRequest(recipient.getId(), EntryType.DEBIT, new BigDecimal("50000")),
                new JournalLineRequest(sender.getId(), EntryType.CREDIT, new BigDecimal("50600")),
                new JournalLineRequest(revenue.getId(), EntryType.DEBIT, new BigDecimal("600")));
        ArgumentCaptor<TransactionEntity> saved = ArgumentCaptor.forClass(TransactionEntity.class);
        verify(transactionRepository).save(saved.capture());
        assertThat(saved.getValue().getTransactionType()).isEqualTo("REVERSAL");
        assertThat(saved.getValue().getExternalReference()).isEqualTo("TRF-1");
    }

    @Test
    void aReversalIsRefusedWhenTheRecipientAlreadySpentTheMoney() {
        recipient.setBalance(new BigDecimal("1000.00"));
        TreasuryOperationEntity op = pending(OperationType.REVERSAL, "50000", "TRF-1");
        assertThatThrownBy(() -> service.approve(op.getId(), approver.getId())).isInstanceOf(BadRequestException.class).hasMessageContaining("already spent");
        assertThat(sender.getBalance()).isEqualByComparingTo("10000.00");
        verify(ledgerService, never()).postJournal(any(), any(), any(), any());
    }

    // ------------------------------------------------------------------ rejecting
    @Test
    void rejectingNeedsAReasonAndMovesNothing() {
        TreasuryOperationEntity op = pending(OperationType.SWEEP, "30000", null);
        assertThatThrownBy(() -> service.reject(op.getId(), approver.getId(), " ")).isInstanceOf(BadRequestException.class);
        service.reject(op.getId(), approver.getId(), "not this month");
        assertThat(op.getStatus()).isEqualTo(OperationStatus.REJECTED);
        assertThat(op.getDecisionNote()).isEqualTo("not this month");
        assertThat(revenue.getBalance()).isEqualByComparingTo("40000.00");
    }

    @Test
    void theAskerCanWithdrawTheirOwnRequest() {
        TreasuryOperationEntity op = pending(OperationType.SWEEP, "30000", null);
        service.reject(op.getId(), asker.getId(), "changed my mind");
        assertThat(op.getStatus()).isEqualTo(OperationStatus.REJECTED);
        assertThat(op.getDecidedBy()).isNull();
    }
}
