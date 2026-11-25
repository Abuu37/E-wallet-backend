package com.application.e_wallet.common.idempotency;

import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.common.exception.DuplicationResourceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Makes a money POST safe to retry. The key row and the money movement share one database transaction:
 * either both are saved or neither is, so a retry never double-posts and never sees half a result.
 *
 * Same key + same body: the stored answer comes back and the action does not run again.
 * Same key + different body: 409. An action that fails (wrong PIN, not enough balance) rolls the key back
 * with it, so the person can fix the problem and try again with the same key.
 */
@Slf4j
@Service
public class IdempotencyService {

    static final int MAX_KEY_LENGTH = 80;

    private final IdempotencyKeyRepository repository;
    private final TransactionTemplate transaction;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyKeyRepository repository, PlatformTransactionManager transactionManager, ObjectMapper objectMapper) {
        this.repository = repository;
        this.transaction = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    public <T> T execute(UUID userId, String key, String endpoint, Object requestBody, Class<T> responseType, Supplier<T> action) {
        String cleanKey = requireKey(key);
        String hash = hash(requestBody);

        // Two requests with a brand-new key can race on the unique index. The loser rolls back, tries again
        // and then finds the winner's stored answer.
        for (int attempt = 0; ; attempt++) {
            try {
                return transaction.execute(status -> run(userId, cleanKey, endpoint, hash, responseType, action));
            } catch (DataIntegrityViolationException raced) {
                if (attempt >= 1) {
                    throw raced;
                }
            }
        }
    }

    private <T> T run(UUID userId, String key, String endpoint, String hash, Class<T> responseType, Supplier<T> action) {
        var existing = repository.findByUserIdAndIdemKey(userId, key);
        if (existing.isPresent()) {
            return replay(existing.get(), endpoint, hash, responseType);
        }

        IdempotencyKeyEntity row = repository.saveAndFlush(IdempotencyKeyEntity.builder()
                .userId(userId).idemKey(key).endpoint(endpoint).requestHash(hash)
                .status(IdempotencyKeyEntity.IN_PROGRESS).build());

        T result = action.get(); // an exception here rolls the whole transaction back, key row included

        row.setStatus(IdempotencyKeyEntity.COMPLETED);
        row.setHttpStatus(200);
        row.setResponseJson(write(result));
        repository.save(row);
        return result;
    }

    private <T> T replay(IdempotencyKeyEntity stored, String endpoint, String hash, Class<T> responseType) {
        if (!stored.getEndpoint().equals(endpoint) || !stored.getRequestHash().equals(hash)) {
            throw new DuplicationResourceException("This Idempotency-Key was already used for a different request.");
        }
        if (!IdempotencyKeyEntity.COMPLETED.equals(stored.getStatus()) || stored.getResponseJson() == null) {
            throw new DuplicationResourceException("This request is still being processed. Try again in a moment.");
        }
        log.info("Idempotent replay of {} (key {})", endpoint, stored.getIdemKey());
        return objectMapper.readValue(stored.getResponseJson(), responseType);
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new BadRequestException("The Idempotency-Key header is required for this request.");
        }
        String clean = key.trim();
        if (clean.length() > MAX_KEY_LENGTH) {
            throw new BadRequestException("The Idempotency-Key header must be at most " + MAX_KEY_LENGTH + " characters.");
        }
        return clean;
    }

    private String write(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    private String hash(Object requestBody) {
        String canonical = requestBody == null ? "" : write(requestBody);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
