package com.application.e_wallet.integration.plaid;

import com.application.e_wallet.common.exception.ProviderUnavailableException;
import com.application.e_wallet.kyc.provider.KycProvider;
import com.application.e_wallet.kyc.provider.KycResult;
import com.application.e_wallet.kyc.provider.KycSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Plaid Identity Verification (document and selfie) behind {@link KycProvider}. Sandbox only for now. */
@Component
@RequiredArgsConstructor
public class PlaidKycProvider implements KycProvider {

    private final PlaidClient plaid;
    private final PlaidProperties properties;

    @Override
    public KycSession createSession(UUID customerId) {
        requireTemplate();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client_name", properties.getClientName());
        body.put("language", "en");
        body.put("country_codes", List.of("US"));
        // Only our own customer id is ever sent to Plaid: never a phone number or a NIDA number.
        body.put("user", Map.of("client_user_id", clientUserId(customerId)));
        body.put("products", List.of("identity_verification"));
        body.put("identity_verification", Map.of("template_id", properties.getIdvTemplateId()));
        if (properties.getWebhookUrl() != null && !properties.getWebhookUrl().isBlank()) {
            body.put("webhook", properties.getWebhookUrl());
        }
        JsonNode response = plaid.post("/link/token/create", body);
        if (!response.hasNonNull("link_token")) {
            throw new ProviderUnavailableException("Identity verification is not available right now. Please try again.");
        }
        return new KycSession(response.get("link_token").asString());
    }

    @Override
    public KycResult fetchLatestResult(UUID customerId) {
        requireTemplate();
        JsonNode response = plaid.post("/identity_verification/list", Map.of(
                "template_id", properties.getIdvTemplateId(),
                "client_user_id", clientUserId(customerId)));
        JsonNode list = response.get("identity_verifications");
        if (list == null || !list.isArray() || list.isEmpty()) {
            return new KycResult(KycResult.Outcome.PENDING, null, customerId);
        }
        JsonNode latest = list.get(0); // Plaid returns the most recent first
        return new KycResult(outcomeOf(textOrNull(latest, "status")), textOrNull(latest, "id"), customerId);
    }

    @Override
    public KycResult fetchResult(String providerRef) {
        JsonNode response = plaid.post("/identity_verification/get", Map.of("identity_verification_id", providerRef));
        String clientUserId = textOrNull(response, "client_user_id");
        UUID customerId = null;
        try {
            customerId = clientUserId == null ? null : UUID.fromString(clientUserId);
        } catch (IllegalArgumentException ignored) {
            // not one of ours: handled as "no customer" by the caller
        }
        return new KycResult(outcomeOf(textOrNull(response, "status")), textOrNull(response, "id"), customerId);
    }

    /** Anything we do not recognise is PENDING, never a pass. */
    static KycResult.Outcome outcomeOf(String plaidStatus) {
        if (plaidStatus == null) {
            return KycResult.Outcome.PENDING;
        }
        return switch (plaidStatus.trim().toLowerCase(Locale.ROOT)) {
            case "success" -> KycResult.Outcome.PASSED;
            case "failed", "expired", "canceled" -> KycResult.Outcome.FAILED;
            default -> KycResult.Outcome.PENDING; // active, pending_review, and anything new
        };
    }

    static String clientUserId(UUID customerId) {
        return customerId.toString();
    }

    private void requireTemplate() {
        if (properties.getIdvTemplateId() == null || properties.getIdvTemplateId().isBlank()) {
            throw new ProviderUnavailableException("Identity verification is not available right now.");
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asString() : null;
    }
}
