package com.application.e_wallet.treasury.controller;

import com.application.e_wallet.auth.service.RateLimitingService;
import com.application.e_wallet.common.dto.ApiResponse;
import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.common.idempotency.IdempotencyService;
import com.application.e_wallet.common.storage.SlipStorage;
import com.application.e_wallet.security.jwt.AuthenticatedUser;
import com.application.e_wallet.treasury.dto.EscrowDetailsResponse;
import com.application.e_wallet.treasury.dto.FloatRequestResponse;
import com.application.e_wallet.treasury.service.FloatRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/** Float request for agents who deposited at the bank (Path B): where to pay, send the request with the slip, view the slip. */
@RestController
@RequiredArgsConstructor
public class FloatRequestController {

    private final FloatRequestService floatRequests;
    private final IdempotencyService idempotencyService;
    private final RateLimitingService rateLimitingService;

    @GetMapping("/api/v1/agent/float/escrow-details")
    @PreAuthorize("hasAuthority('REQUEST_FLOAT')")
    public ResponseEntity<ApiResponse<EscrowDetailsResponse>> escrowDetails(Authentication authentication) {
        return ok("Escrow details retrieved.", floatRequests.escrowDetails(userId(authentication)));
    }

    @PostMapping(value = "/api/v1/agent/float/requests", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('REQUEST_FLOAT')")
    public ResponseEntity<ApiResponse<FloatRequestResponse>> create(@RequestParam BigDecimal amount, @RequestParam String depositReference,
            @RequestParam("slip") MultipartFile slip, @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication authentication) throws IOException {
        UUID userId = userId(authentication);
        rateLimitingService.enforceMoney(userId);
        byte[] bytes = slip.getBytes();
        // The body that is compared on a repeat: the amount, the slip number and a fingerprint of the photo.
        Map<String, Object> body = Map.of("amount", amount, "depositReference", depositReference.trim(), "slip", sha256(bytes));
        FloatRequestResponse response = idempotencyService.execute(userId, key, "POST /agent/float/requests", body, FloatRequestResponse.class,
                () -> floatRequests.create(userId, amount, depositReference, bytes));
        return ok("Float request sent. We will email you once it is checked.", response);
    }

    @GetMapping("/api/v1/treasury/requests/{requestId}/slip")
    @PreAuthorize("hasAnyAuthority('FLOAT_VIEW', 'REQUEST_FLOAT')")
    public ResponseEntity<byte[]> slip(@PathVariable UUID requestId, Authentication authentication) {
        boolean canManage = authentication.getAuthorities().stream().anyMatch(a -> "FLOAT_MANAGE".equals(a.getAuthority()));
        SlipStorage.Loaded loaded = floatRequests.slip(requestId, userId(authentication), canManage);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(loaded.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header("X-Content-Type-Options", "nosniff")
                .body(loaded.bytes());
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new BadRequestException("Could not read the slip");
        }
    }

    private static UUID userId(Authentication authentication) {
        return ((AuthenticatedUser) authentication.getPrincipal()).userId();
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(String message, T data) {
        return ResponseEntity.ok(ApiResponse.<T>builder().timestamp(Instant.now()).status(HttpStatus.OK.value()).message(message).data(data).build());
    }
}
