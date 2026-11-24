package com.application.e_wallet.audit.controller;

import com.application.e_wallet.audit.dto.AuditLogListResponse;
import com.application.e_wallet.audit.service.AuditLogService;
import com.application.e_wallet.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** Read-only. There is deliberately no endpoint that edits or deletes an entry. */
@RestController
@RequestMapping("/api/v1/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogService auditLogService;

    @GetMapping
    @PreAuthorize("hasAuthority('AUDIT_VIEW')")
    public ResponseEntity<ApiResponse<AuditLogListResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(ApiResponse.<AuditLogListResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Audit log retrieved successfully")
                .data(auditLogService.list(page, size))
                .build());
    }
}
