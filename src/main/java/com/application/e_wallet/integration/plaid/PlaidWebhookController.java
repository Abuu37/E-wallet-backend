package com.application.e_wallet.integration.plaid;

import com.application.e_wallet.auth.service.RateLimitingService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public endpoint (no login), so every request must carry a valid Plaid-Verification JWT. A request that fails the
 * check gets 401 and nothing is stored. The raw body is read as text because the signature covers its exact bytes.
 */
@RestController
@RequestMapping("/api/v1/webhooks/plaid")
@RequiredArgsConstructor
public class PlaidWebhookController {

    private final PlaidWebhookVerifier verifier;
    private final PlaidWebhookService service;
    private final RateLimitingService rateLimitingService;

    @PostMapping
    public ResponseEntity<Void> receive(
            @RequestBody String body,
            @RequestHeader(value = "Plaid-Verification", required = false) String verification,
            HttpServletRequest http) {
        rateLimitingService.enforceRefresh(http.getRemoteAddr());
        if (!verifier.verify(verification, body)) {
            return ResponseEntity.status(401).build();
        }
        service.process(body);
        return ResponseEntity.ok().build();
    }
}
