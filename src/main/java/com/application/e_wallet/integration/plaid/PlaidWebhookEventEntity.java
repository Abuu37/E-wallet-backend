package com.application.e_wallet.integration.plaid;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Every verified Plaid webhook, stored once (the body hash is unique) so a replay is recognised and ignored. */
@Entity
@Table(name = "plaid_webhook_events")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlaidWebhookEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "webhook_type", nullable = false, length = 60)
    private String webhookType;

    @Column(name = "webhook_code", nullable = false, length = 60)
    private String webhookCode;

    @Column(name = "provider_ref", length = 100)
    private String providerRef;

    @Column(name = "body_sha256", nullable = false, length = 64, unique = true, updatable = false)
    private String bodySha256;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "received_at", nullable = false, updatable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "processed_at")
    private OffsetDateTime processedAt;

    @PrePersist
    protected void onCreate() {
        if (receivedAt == null) {
            receivedAt = OffsetDateTime.now();
        }
    }
}
