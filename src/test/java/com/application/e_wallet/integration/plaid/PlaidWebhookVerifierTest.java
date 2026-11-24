package com.application.e_wallet.integration.plaid;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Webhooks are public, so the Plaid-Verification JWT is the only thing that proves they are real. */
class PlaidWebhookVerifierTest {

    private static final String BODY = "{\"webhook_type\":\"IDENTITY_VERIFICATION\",\"webhook_code\":\"STATUS_UPDATED\",\"identity_verification_id\":\"idv_1\"}";
    private static final Instant NOW = Instant.parse("2026-11-24T12:00:00Z");

    private KeyPair plaidKey;
    private PlaidWebhookVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        plaidKey = newKeyPair();
        PlaidKeySource keys = keyId -> "kid-1".equals(keyId) ? Optional.of((ECPublicKey) plaidKey.getPublic()) : Optional.empty();
        verifier = new PlaidWebhookVerifier(keys, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static KeyPair newKeyPair() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private String jwt(KeyPair signer, String kid, Instant issuedAt, String bodyHash) {
        return Jwts.builder()
                .header().keyId(kid).and()
                .claims(Map.of("request_body_sha256", bodyHash))
                .issuedAt(Date.from(issuedAt))
                .signWith(signer.getPrivate(), Jwts.SIG.ES256)
                .compact();
    }

    @Test
    void acceptsAFreshCorrectlySignedWebhookWhoseBodyMatches() throws Exception {
        String token = jwt(plaidKey, "kid-1", NOW.minusSeconds(30), sha256(BODY));
        assertThat(verifier.verify(token, BODY)).isTrue();
    }

    @Test
    void rejectsABodyThatWasChangedAfterSigning() throws Exception {
        String token = jwt(plaidKey, "kid-1", NOW.minusSeconds(30), sha256(BODY));
        assertThat(verifier.verify(token, BODY.replace("idv_1", "idv_2"))).isFalse();
    }

    @Test
    void rejectsAWebhookOlderThanFiveMinutes() throws Exception {
        String token = jwt(plaidKey, "kid-1", NOW.minus(Duration.ofMinutes(6)), sha256(BODY));
        assertThat(verifier.verify(token, BODY)).isFalse();
    }

    @Test
    void rejectsATokenSignedByAnyoneButPlaid() throws Exception {
        String token = jwt(newKeyPair(), "kid-1", NOW.minusSeconds(30), sha256(BODY));
        assertThat(verifier.verify(token, BODY)).isFalse();
    }

    @Test
    void rejectsAnUnknownKeyId() throws Exception {
        String token = jwt(plaidKey, "kid-unknown", NOW.minusSeconds(30), sha256(BODY));
        assertThat(verifier.verify(token, BODY)).isFalse();
    }

    @Test
    void rejectsMissingOrMalformedTokens() {
        assertThat(verifier.verify(null, BODY)).isFalse();
        assertThat(verifier.verify("", BODY)).isFalse();
        assertThat(verifier.verify("not.a.jwt", BODY)).isFalse();
        // an unsigned token (alg none) must never pass
        String unsigned = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\",\"kid\":\"kid-1\"}".getBytes(StandardCharsets.UTF_8))
                + "." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("{}".getBytes(StandardCharsets.UTF_8)) + ".";
        assertThat(verifier.verify(unsigned, BODY)).isFalse();
    }
}
