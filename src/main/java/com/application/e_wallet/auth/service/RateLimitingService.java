package com.application.e_wallet.auth.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Service
public class RateLimitingService {

    // Cache to hold token buckets for IPs
    private final Cache<String, Bucket> ipCache = Caffeine.newBuilder()
            .expireAfterAccess(1, TimeUnit.HOURS)
            .build();

    // Cache to hold token buckets for accounts (keyed by email)
    private final Cache<String, Bucket> accountCache = Caffeine.newBuilder()
            .expireAfterAccess(1, TimeUnit.HOURS)
            .build();

    // IP Rule: Max 20 requests per minute
    private Bucket createNewIpBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.classic(20, Refill.intervally(20, Duration.ofMinutes(1))))
                .build();
    }

    // Account Rule: Max 5 attempts, refills 1 token every 3 minutes
    private Bucket createNewAccountBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.classic(5, Refill.intervally(1, Duration.ofMinutes(3))))
                .build();
    }

    public boolean tryConsumeIp(String ip) {
        Bucket bucket = ipCache.get(ip, key -> createNewIpBucket());
        return bucket.tryConsume(1);
    }

    public boolean tryConsumeAccount(String email) {
        Bucket bucket = accountCache.get(email, key -> createNewAccountBucket());
        return bucket.tryConsume(1);
    }

}
