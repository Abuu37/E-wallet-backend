package com.application.e_wallet.audit.service;

import com.application.e_wallet.audit.dto.AuditLogItem;
import com.application.e_wallet.audit.dto.AuditLogListResponse;
import com.application.e_wallet.audit.entity.AuditLogEntity;
import com.application.e_wallet.audit.repository.AuditLogRepository;
import com.application.e_wallet.staff.repository.StaffRepository;
import com.application.e_wallet.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AuditLogService {

    static final int MAX_PAGE_SIZE = 200;

    private final AuditLogRepository auditLogRepository;
    private final StaffRepository staffRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public AuditLogListResponse list(int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Page<AuditLogEntity> result = auditLogRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(Math.max(page, 0), safeSize));

        Map<UUID, String> names = new HashMap<>();
        for (AuditLogEntity e : result.getContent()) {
            if (e.getActorId() != null) {
                names.computeIfAbsent(e.getActorId(), this::nameOf);
            }
        }

        return new AuditLogListResponse(
                result.getContent().stream().map(e -> new AuditLogItem(
                        e.getId(), e.getCreatedAt(), e.getActorId(),
                        e.getActorId() == null ? "System" : names.get(e.getActorId()),
                        e.getEntityType(), e.getAction(), e.getEntityId(),
                        e.getBeforeJson(), e.getAfterJson(), e.getReason(), e.getIpAddress())).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements());
    }

    private String nameOf(UUID userId) {
        return staffRepository.findByUserId(userId)
                .map(s -> Stream.of(s.getFirstName(), s.getMiddleName(), s.getLastName())
                        .filter(p -> p != null && !p.isBlank()).collect(Collectors.joining(" ")))
                .filter(n -> !n.isBlank())
                .orElseGet(() -> userRepository.findById(userId).map(u -> u.getEmail()).orElse("Unknown user"));
    }
}
