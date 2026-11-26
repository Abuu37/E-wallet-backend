package com.application.e_wallet.treasury.operations;

import com.application.e_wallet.common.dto.ApiResponse;
import com.application.e_wallet.common.idempotency.IdempotencyService;
import com.application.e_wallet.security.jwt.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Two-person treasury moves. The approve call carries an Idempotency-Key, so a double click moves money once. */
@RestController
@RequestMapping("/api/v1/treasury/operations")
@RequiredArgsConstructor
public class TreasuryOperationController {

    private final TreasuryOperationService service;
    private final IdempotencyService idempotencyService;

    @PostMapping
    @PreAuthorize("hasAuthority('TREASURY_OPERATE')")
    public ResponseEntity<ApiResponse<OperationResponse>> request(@Valid @RequestBody OperationRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication authentication) {
        UUID userId = userId(authentication);
        return ok("Request sent. A second staff member must approve it.",
                idempotencyService.execute(userId, key, "POST /treasury/operations", request, OperationResponse.class, () -> service.request(userId, request)));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('TREASURY_OPERATE')")
    public ResponseEntity<ApiResponse<List<OperationResponse>>> list(@RequestParam(required = false) OperationStatus status,
            @RequestParam(defaultValue = "50") int size, Authentication authentication) {
        return ok("Operations retrieved.", service.list(userId(authentication), status, size));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('TREASURY_OPERATE')")
    public ResponseEntity<ApiResponse<OperationResponse>> approve(@PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication authentication) {
        UUID userId = userId(authentication);
        return ok("Approved.", idempotencyService.execute(userId, key, "POST /treasury/operations/" + id + "/approve", id, OperationResponse.class,
                () -> service.approve(id, userId)));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('TREASURY_OPERATE')")
    public ResponseEntity<ApiResponse<OperationResponse>> reject(@PathVariable UUID id, @Valid @RequestBody OperationDecisionRequest request, Authentication authentication) {
        return ok("Rejected.", service.reject(id, userId(authentication), request.reason()));
    }

    private static UUID userId(Authentication authentication) {
        return ((AuthenticatedUser) authentication.getPrincipal()).userId();
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(String message, T data) {
        return ResponseEntity.ok(ApiResponse.<T>builder().timestamp(Instant.now()).status(HttpStatus.OK.value()).message(message).data(data).build());
    }
}
