package com.application.e_wallet.integration.plaid;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PlaidWebhookEventRepository extends JpaRepository<PlaidWebhookEventEntity, UUID> {

    boolean existsByBodySha256(String bodySha256);
}
