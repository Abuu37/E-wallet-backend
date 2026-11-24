package com.application.e_wallet.integration.plaid;

import com.application.e_wallet.common.exception.ProviderUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Fetches Plaid's webhook signing keys (a JWK per key id) and caches them. Expired keys are never returned. */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlaidKeyFetcher implements PlaidKeySource {

    private final PlaidClient plaid;
    private final Map<String, ECPublicKey> cache = new ConcurrentHashMap<>();

    @Override
    public Optional<ECPublicKey> publicKey(String keyId) {
        if (keyId == null || keyId.isBlank() || keyId.length() > 100) {
            return Optional.empty();
        }
        ECPublicKey cached = cache.get(keyId);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            JsonNode key = plaid.post("/webhook_verification_key/get", Map.of("key_id", keyId)).get("key");
            if (key == null || key.hasNonNull("expired_at") || !"EC".equals(text(key, "kty")) || !"P-256".equals(text(key, "crv"))) {
                return Optional.empty();
            }
            ECPublicKey publicKey = toPublicKey(text(key, "x"), text(key, "y"));
            cache.put(keyId, publicKey);
            return Optional.of(publicKey);
        } catch (ProviderUnavailableException | java.security.GeneralSecurityException | IllegalArgumentException e) {
            log.warn("Could not load a Plaid webhook verification key: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static ECPublicKey toPublicKey(String x, String y) throws java.security.GeneralSecurityException {
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec spec = parameters.getParameterSpec(ECParameterSpec.class);
        ECPoint point = new ECPoint(new BigInteger(1, Base64.getUrlDecoder().decode(x)), new BigInteger(1, Base64.getUrlDecoder().decode(y)));
        return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, spec));
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asString() : null;
    }
}
