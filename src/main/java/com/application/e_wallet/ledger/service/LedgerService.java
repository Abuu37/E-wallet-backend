package com.application.e_wallet.ledger.service;

import com.application.e_wallet.ledger.dto.JournalLineRequest;
import com.application.e_wallet.ledger.entity.EntryType;
import com.application.e_wallet.ledger.entity.TransactionJournalEntity;
import com.application.e_wallet.ledger.entity.TransactionJournalLineEntity;
import com.application.e_wallet.ledger.entity.TransactionType;
import com.application.e_wallet.ledger.repository.TransactionJournalRepository;
import com.application.e_wallet.user.entity.UserEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LedgerService {

    private static final DateTimeFormatter REFERENCE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final TransactionJournalRepository transactionJournalRepository;

    @Transactional
    public TransactionJournalEntity postJournal(
            TransactionType transactionType,
            String description,
            UserEntity createdBy,
            List<JournalLineRequest> lines
    ) {

        BigDecimal totalDebits = BigDecimal.ZERO;
        BigDecimal totalCredits = BigDecimal.ZERO;

        for (JournalLineRequest line : lines) {
            if (line.entryType() == EntryType.DEBIT) {
                totalDebits = totalDebits.add(line.amount());
            } else {
                totalCredits = totalCredits.add(line.amount());
            }
        }

        if (totalDebits.compareTo(totalCredits) != 0) {
            throw new IllegalStateException(
                    "Unbalanced journal: total debits (" + totalDebits + ") must equal total credits (" + totalCredits + ")");
        }

        TransactionJournalEntity journal = TransactionJournalEntity.builder()
                .referenceNumber(generateReferenceNumber(transactionType))
                .transactionType(transactionType)
                .description(description)
                .createdBy(createdBy)
                .build();

        for (JournalLineRequest line : lines) {
            TransactionJournalLineEntity journalLine = TransactionJournalLineEntity.builder()
                    .journal(journal)
                    .ledgerAccountType(line.ledgerAccountType())
                    .accountRefId(line.accountRefId())
                    .entryType(line.entryType())
                    .amount(line.amount())
                    .build();

            journal.getLines().add(journalLine);
        }

        return transactionJournalRepository.save(journal);
    }

    private String generateReferenceNumber(TransactionType transactionType) {

        String referenceNumber;
        do {
            String datePart = OffsetDateTime.now().format(REFERENCE_DATE_FORMAT);
            String randomPart = UUID.randomUUID()
                    .toString()
                    .replace("-", "")
                    .substring(0, 8)
                    .toUpperCase();
            referenceNumber = transactionType + "-" + datePart + "-" + randomPart;
        } while (transactionJournalRepository.existsByReferenceNumber(referenceNumber));

        return referenceNumber;
    }
}
