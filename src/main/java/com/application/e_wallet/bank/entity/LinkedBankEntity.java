package com.application.e_wallet.bank.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A bank account a person linked through Plaid. The access token is kept encrypted and never leaves the server. */
@Entity
@Table(name = "linked_bank_accounts")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LinkedBankEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String provider = "PLAID";

    @Column(name = "provider_item_id", nullable = false, length = 100)
    private String providerItemId;

    @Column(name = "access_token_encrypted", nullable = false, columnDefinition = "text")
    private String accessTokenEncrypted;

    @Column(name = "provider_account_id", nullable = false, length = 100)
    private String providerAccountId;

    @Column(name = "institution_name", length = 150)
    private String institutionName;

    @Column(name = "account_name", length = 150)
    private String accountName;

    @Column(name = "account_mask", length = 10)
    private String accountMask;

    @Column(name = "holder_name", length = 200)
    private String holderName;

    @Column(name = "name_matched", nullable = false)
    private boolean nameMatched;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "ACTIVE";

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = OffsetDateTime.now();
    }

    /** "First Platypus Bank ••0000", the label used on screens, receipts and messages. */
    public String label() {
        String bank = institutionName == null || institutionName.isBlank() ? "Bank" : institutionName;
        return accountMask == null ? bank : bank + " ••" + accountMask;
    }
}
