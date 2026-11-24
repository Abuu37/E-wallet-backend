package com.application.e_wallet.auth.service;

import com.application.e_wallet.common.exception.TooManyRequestsException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Service
public class RateLimitingService {

    private final Cache<String, Bucket> ipCache = Caffeine.newBuilder()
            .expireAfterAccess(1, TimeUnit.HOURS)
            .build();

    private final Cache<String, Bucket> accountCache = Caffeine.newBuilder()
            .expireAfterAccess(1, TimeUnit.HOURS)
            .build();

    // IP rule: max 20 requests per minute
    private Bucket createNewIpBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.classic(20, Refill.intervally(20, Duration.ofMinutes(1))))
                .build();
    }

    // Account rule: max 5 attempts, refills 1 token every 3 minutes
    private Bucket createNewAccountBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.classic(5, Refill.intervally(1, Duration.ofMinutes(3))))
                .build();
    }

    public boolean tryConsumeIp(String ip) {
        return ipCache.get(ip, key -> createNewIpBucket()).tryConsume(1);
    }

    public boolean tryConsumeAccount(String accountKey) {
        return accountCache.get(accountKey, key -> createNewAccountBucket()).tryConsume(1);
    }

    /**
     * Consumes one token from the per-IP bucket and, when accountKey is given, from the
     * per-account bucket. Throws TooManyRequestsException (HTTP 429 + Retry-After) when either is empty.
     */
    public void enforce(String ip, String accountKey) {
        ConsumptionProbe ipProbe = ipCache.get(ip, key -> createNewIpBucket()).tryConsumeAndReturnRemaining(1);
        if (!ipProbe.isConsumed()) {
            throw tooMany(ipProbe);
        }
        if (accountKey != null) {
            ConsumptionProbe accountProbe = accountCache
                    .get(accountKey, key -> createNewAccountBucket()).tryConsumeAndReturnRemaining(1);
            if (!accountProbe.isConsumed()) {
                throw tooMany(accountProbe);
            }
        }
    }

    private TooManyRequestsException tooMany(ConsumptionProbe probe) {
        long seconds = (long) Math.ceil(probe.getNanosToWaitForRefill() / 1_000_000_000.0);
        return new TooManyRequestsException("Too many attempts. Please try again later.", seconds);
    }
}
