package com.application.e_wallet.agent.activity;

import com.application.e_wallet.common.dto.ApiResponse;
import com.application.e_wallet.security.jwt.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The signed-in agent's own till history. There is never an agent id in the URL. */
@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
public class AgentActivityController {

    private final AgentActivityService activity;

    @GetMapping("/transactions")
    @PreAuthorize("hasAuthority('AGENT_VIEW')")
    public ResponseEntity<ApiResponse<AgentTransactionPage>> list(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        return ok("Transactions retrieved.", activity.list(userId(authentication), type, from, to, q, page, size));
    }

    @GetMapping("/transactions/{reference}")
    @PreAuthorize("hasAuthority('AGENT_VIEW')")
    public ResponseEntity<ApiResponse<AgentTransactionRow>> detail(@PathVariable String reference, Authentication authentication) {
        return ok("Transaction retrieved.", activity.detail(userId(authentication), reference));
    }

    @GetMapping("/withdrawals/live")
    @PreAuthorize("hasAuthority('AGENT_VIEW')")
    public ResponseEntity<ApiResponse<List<LiveWithdrawal>>> live(@RequestParam(defaultValue = "15") int minutes, Authentication authentication) {
        return ok("Live withdrawals retrieved.", activity.live(userId(authentication), minutes));
    }

    @GetMapping("/dashboard/weekly")
    @PreAuthorize("hasAuthority('AGENT_VIEW')")
    public ResponseEntity<ApiResponse<List<WeeklyDay>>> weekly(Authentication authentication) {
        return ok("Last 7 days retrieved.", activity.weekly(userId(authentication)));
    }

    private static UUID userId(Authentication authentication) {
        return ((AuthenticatedUser) authentication.getPrincipal()).userId();
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(String message, T data) {
        return ResponseEntity.ok(ApiResponse.<T>builder().timestamp(Instant.now()).status(HttpStatus.OK.value()).message(message).data(data).build());
    }
}
