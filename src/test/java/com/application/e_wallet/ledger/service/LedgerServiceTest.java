package com.application.e_wallet.ledger.service;

import com.application.e_wallet.ledger.entity.EntryType;
import com.application.e_wallet.ledger.entity.LedgerAccountType;
import com.application.e_wallet.ledger.entity.TransactionJournalEntity;
import com.application.e_wallet.ledger.entity.TransactionType;
import com.application.e_wallet.ledger.repository.TransactionJournalRepository;
import com.application.e_wallet.user.entity.UserEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    @Mock
    private TransactionJournalRepository transactionJournalRepository;

    @InjectMocks
    private LedgerService ledgerService;

    @Test
    void postJournalPersistsBalancedJournalWithLines() {
        UserEntity admin = UserEntity.builder().id(UUID.randomUUID()).build();
        List<JournalLineRequest> lines = List.of(
                new JournalLineRequest(LedgerAccountType.ESCROW_BANK, UUID.randomUUID(), EntryType.DEBIT, new BigDecimal("100.00")),
                new JournalLineRequest(LedgerAccountType.COMPANY_UNALLOCATED, UUID.randomUUID(), EntryType.CREDIT, new BigDecimal("100.00"))
        );
        when(transactionJournalRepository.existsByReferenceNumber(any())).thenReturn(false);
        when(transactionJournalRepository.save(any(TransactionJournalEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TransactionJournalEntity result = ledgerService.postJournal(
                TransactionType.MINT_FLOAT, "test mint", admin, lines);

        assertThat(result.getLines()).hasSize(2);
        assertThat(result.getTransactionType()).isEqualTo(TransactionType.MINT_FLOAT);
        assertThat(result.getCreatedBy()).isEqualTo(admin);
    }

    @Test
    void postJournalRejectsUnbalancedLines() {
        UserEntity admin = UserEntity.builder().id(UUID.randomUUID()).build();
        List<JournalLineRequest> lines = List.of(
                new JournalLineRequest(LedgerAccountType.ESCROW_BANK, UUID.randomUUID(), EntryType.DEBIT, new BigDecimal("100.00")),
                new JournalLineRequest(LedgerAccountType.COMPANY_UNALLOCATED, UUID.randomUUID(), EntryType.CREDIT, new BigDecimal("50.00"))
        );

        assertThatThrownBy(() -> ledgerService.postJournal(TransactionType.MINT_FLOAT, "bad", admin, lines))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(transactionJournalRepository);
    }

    @Test
    void postJournalRetriesReferenceNumberOnCollision() {
        UserEntity admin = UserEntity.builder().id(UUID.randomUUID()).build();
        List<JournalLineRequest> lines = List.of(
                new JournalLineRequest(LedgerAccountType.ESCROW_BANK, UUID.randomUUID(), EntryType.DEBIT, new BigDecimal("10.00")),
                new JournalLineRequest(LedgerAccountType.COMPANY_UNALLOCATED, UUID.randomUUID(), EntryType.CREDIT, new BigDecimal("10.00"))
        );
        when(transactionJournalRepository.existsByReferenceNumber(any())).thenReturn(true, false);
        when(transactionJournalRepository.save(any(TransactionJournalEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ledgerService.postJournal(TransactionType.MINT_FLOAT, "retry", admin, lines);

        verify(transactionJournalRepository, times(2)).existsByReferenceNumber(any());
    }
}
