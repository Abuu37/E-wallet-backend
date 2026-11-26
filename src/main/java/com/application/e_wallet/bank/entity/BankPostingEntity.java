package com.application.e_wallet.bank.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** The matching line to put on the Sandbox bank for a wallet movement. A failed call is retried, never dropped. */
@Entity
@Table(name = "bank_postings")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BankPostingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "linked_bank_id", nullable = false)
    private UUID linkedBankId;

    /** DEBIT: money left the bank (a top-up). CREDIT: money arrived (a withdrawal). */
    @Column(nullable = false, length = 10)
    private String direction;

    @Column(name = "usd_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal usdAmount;

    @Column(nullable = false, length = 10)
    @Builder.Default
    private String status = "PENDING";

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 200)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "done_at")
    private OffsetDateTime doneAt;

    @PrePersist
    void onCreate() {
        createdAt = OffsetDateTime.now();
    }
}
