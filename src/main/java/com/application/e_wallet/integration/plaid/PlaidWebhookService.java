package com.application.e_wallet.integration.plaid;

import com.application.e_wallet.kyc.service.KycService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;

/**
 * Stores a verified webhook once and dispatches it. If handling fails, the transaction rolls back (the event is
 * not kept) and the caller answers with an error, so Plaid retries and the event is handled on the next try.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlaidWebhookService {

    private final PlaidWebhookEventRepository events;
    private final KycService kycService;
    private final ObjectMapper objectMapper;

    /** @return false when this exact event was already handled (a replay) */
    @Transactional
    public boolean process(String body) {
        String hash = sha256(body);
        if (events.existsByBodySha256(hash)) {
            return false;
        }
        JsonNode json = objectMapper.readTree(body);
        String type = text(json, "webhook_type");
        String code = text(json, "webhook_code");
        String providerRef = text(json, "identity_verification_id");

        PlaidWebhookEventEntity event = events.save(PlaidWebhookEventEntity.builder()
                .webhookType(type == null ? "UNKNOWN" : type)
                .webhookCode(code == null ? "UNKNOWN" : code)
                .providerRef(providerRef)
                .bodySha256(hash)
                .payload(body)
                .build());

        if ("IDENTITY_VERIFICATION".equals(type) && providerRef != null) {
            kycService.handleProviderUpdate(providerRef);
        } else {
            log.info("Plaid webhook {} {} stored, nothing to do", type, code);
        }
        event.setProcessedAt(OffsetDateTime.now());
        return true;
    }

    private static String sha256(String body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asString() : null;
    }
}
