package com.application.e_wallet.audit.service;

import com.application.e_wallet.audit.entity.AuditLogEntity;
import com.application.e_wallet.audit.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import com.application.e_wallet.security.jwt.AuthenticatedUser;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
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
    /** Records an action by whoever is signed in on this request (their user id comes from the security context). */
    @Transactional(propagation = Propagation.REQUIRED)
    public void recordAsCurrentUser(String action, String entityType, UUID entityId, Object before, Object after, String reason) {
        UUID actor = null;
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            actor = user.userId();
        }
        record(actor, action, entityType, entityId, before, after, reason, null);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void record(UUID actorId, String action, String entityType, UUID entityId,
                       Object before, Object after, String reason, String ipAddress) {
        String address = ipAddress != null ? ipAddress : currentRemoteAddress();
        auditLogRepository.save(AuditLogEntity.builder()
                .actorId(actorId)
                .action(action)
                .entityType(entityType)
                .entityId(entityId)
                .beforeJson(before == null ? null : objectMapper.writeValueAsString(before))
                .afterJson(after == null ? null : objectMapper.writeValueAsString(after))
                .reason(reason == null || reason.isBlank() ? null : reason.trim())
                .ipAddress(address)
                .build());
    }

    /** The caller's address when running inside a web request (the socket address, never a spoofable header). */
    private static String currentRemoteAddress() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest().getRemoteAddr();
        }
        return null;
    }
}
