package com.application.e_wallet.audit.service;

import com.application.e_wallet.audit.entity.AuditLogEntity;
import com.application.e_wallet.audit.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    /**
     * Records who did what, with the state before and after. Joins the caller's transaction, so an
     * action and its audit entry commit or roll back together. Never pass PINs, OTPs or tokens in
     * before/after.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void record(UUID actorId, String action, String entityType, UUID entityId,
                       Object before, Object after, String reason, String ipAddress) {
        auditLogRepository.save(AuditLogEntity.builder()
                .actorId(actorId)
                .action(action)
                .entityType(entityType)
                .entityId(entityId)
                .beforeJson(before == null ? null : objectMapper.writeValueAsString(before))
                .afterJson(after == null ? null : objectMapper.writeValueAsString(after))
                .reason(reason == null || reason.isBlank() ? null : reason.trim())
                .ipAddress(ipAddress)
                .build());
    }
}
