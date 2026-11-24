package com.application.e_wallet.auth.service;

import com.application.e_wallet.common.exception.TooManyRequestsException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitingServiceTest {

    private final RateLimitingService service = new RateLimitingService();

    @Test
    void ipBucketAllowsTwentyAuthCallsPerMinuteThenReturnsRetryAfter() {
        for (int i = 0; i < 20; i++) {
            service.enforce("10.0.0.1", null);
        }
        assertThatThrownBy(() -> service.enforce("10.0.0.1", null))
                .isInstanceOfSatisfying(TooManyRequestsException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isBetween(1L, 60L));
    }

    @Test
    void accountBucketAllowsFiveAttempts() {
        for (int i = 0; i < 5; i++) {
            service.enforce("10.0.0.2", "login:+255712345678");
            // each call also consumes the IP bucket; 5 is well under its limit
        }
        assertThatThrownBy(() -> service.enforce("10.0.0.2", "login:+255712345678"))
                .isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void refreshHasItsOwnLargerBucketSoPageReloadsAndLoginsDoNotStarveEachOther() {
        for (int i = 0; i < 20; i++) {
            service.enforce("10.0.0.3", null); // login-style traffic exhausts the auth bucket
        }
        // a signed-in user behind the same NAT address can still refresh their session
        for (int i = 0; i < 100; i++) {
            service.enforceRefresh("10.0.0.3");
        }
        assertThatThrownBy(() -> service.enforceRefresh("10.0.0.3"))
                .isInstanceOf(TooManyRequestsException.class);
    }
}
