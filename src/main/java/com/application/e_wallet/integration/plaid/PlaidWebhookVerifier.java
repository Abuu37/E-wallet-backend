package com.application.e_wallet.integration.plaid;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Webhooks are public, so this is the only proof one came from Plaid: an ES256 JWT in the Plaid-Verification
 * header, signed with a key Plaid publishes, that must be fresh (5 minutes) and carry the SHA-256 of the exact body.
 */
@Slf4j
@Component
public class PlaidWebhookVerifier {

    private static final Duration MAX_AGE = Duration.ofMinutes(5);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final PlaidKeySource keys;
    private final Clock clock;

    @Autowired
    public PlaidWebhookVerifier(PlaidKeySource keys) {
        this(keys, Clock.systemUTC());
    }

    PlaidWebhookVerifier(PlaidKeySource keys, Clock clock) {
        this.keys = keys;
        this.clock = clock;
    }

    public boolean verify(String jwt, String body) {
        try {
            if (jwt == null || jwt.isBlank() || body == null) {
                return false;
            }
            String[] parts = jwt.split("\\.", -1);
            if (parts.length != 3 || parts[2].isEmpty()) {
                return false; // an unsigned token (alg none) has an empty signature
            }
            JsonNode header = JSON.readTree(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8));
            if (!"ES256".equals(textOrNull(header, "alg"))) {
                return false;
            }
            Optional<ECPublicKey> key = keys.publicKey(textOrNull(header, "kid"));
            if (key.isEmpty()) {
                return false;
            }

            Claims claims = Jwts.parser().verifyWith(key.get()).build().parseSignedClaims(jwt).getPayload();

            Date issuedAt = claims.getIssuedAt();
            Instant now = clock.instant();
            if (issuedAt == null || issuedAt.toInstant().isBefore(now.minus(MAX_AGE)) || issuedAt.toInstant().isAfter(now.plusSeconds(60))) {
                return false;
            }

            String expected = claims.get("request_body_sha256", String.class);
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
            return expected != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("Rejected a Plaid webhook: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asString() : null;
    }
}
