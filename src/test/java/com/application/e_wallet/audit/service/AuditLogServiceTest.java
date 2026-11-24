package com.application.e_wallet.audit.service;

import com.application.e_wallet.audit.dto.AuditLogItem;
import com.application.e_wallet.audit.dto.AuditLogListResponse;
import com.application.e_wallet.audit.entity.AuditLogEntity;
import com.application.e_wallet.audit.repository.AuditLogRepository;
import com.application.e_wallet.staff.entity.StaffEntity;
import com.application.e_wallet.staff.repository.StaffRepository;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuditLogServiceTest {

    @Mock private AuditLogRepository auditLogRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private UserRepository userRepository;
    @InjectMocks private AuditLogService service;

    private UUID actorId;

    private AuditLogEntity entry(UUID actor, String action) {
        return AuditLogEntity.builder()
                .id(UUID.randomUUID()).actorId(actor).action(action).entityType("ROLE").entityId(UUID.randomUUID())
                .beforeJson("{\"role\":\"AGENT\",\"permissions\":[\"A\"]}").afterJson("{\"role\":\"AGENT\",\"permissions\":[\"A\",\"B\"]}")
                .reason("Agents need B").ipAddress("10.0.0.1").createdAt(OffsetDateTime.parse("2026-11-24T10:00:00+03:00")).build();
    }

    @BeforeEach
    void setUp() {
        actorId = UUID.randomUUID();
    }

    @Test
    void entriesCarryTheStaffMembersNameAndTheBeforeAndAfterState() {
        Page<AuditLogEntity> page = new PageImpl<>(List.of(entry(actorId, "ROLE_PERMISSIONS_UPDATE")));
        when(auditLogRepository.findAllByOrderByCreatedAtDesc(any(Pageable.class))).thenReturn(page);
        StaffEntity staff = StaffEntity.builder().firstName("Abubakar").lastName("Mohammed").build();
        when(staffRepository.findByUserId(actorId)).thenReturn(Optional.of(staff));

        AuditLogListResponse response = service.list(0, 50);

        assertThat(response.entries()).hasSize(1);
        AuditLogItem item = response.entries().get(0);
        assertThat(item.actorName()).isEqualTo("Abubakar Mohammed");
        assertThat(item.module()).isEqualTo("ROLE");
        assertThat(item.action()).isEqualTo("ROLE_PERMISSIONS_UPDATE");
        assertThat(item.beforeJson()).contains("\"A\"");
        assertThat(item.afterJson()).contains("\"B\"");
        assertThat(item.reason()).isEqualTo("Agents need B");
        assertThat(response.totalElements()).isEqualTo(1);
    }

    @Test
    void anActorWithoutAStaffProfileFallsBackToTheirEmail() {
        when(auditLogRepository.findAllByOrderByCreatedAtDesc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(entry(actorId, "ROLE_CREATE"))));
        when(staffRepository.findByUserId(actorId)).thenReturn(Optional.empty());
        when(userRepository.findById(actorId)).thenReturn(Optional.of(UserEntity.builder().id(actorId).email("ops@example.com").build()));

        assertThat(service.list(0, 50).entries().get(0).actorName()).isEqualTo("ops@example.com");
    }

    @Test
    void aSystemEntryWithNoActorIsLabelledSystem() {
        when(auditLogRepository.findAllByOrderByCreatedAtDesc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(entry(null, "SYSTEM_JOB"))));

        assertThat(service.list(0, 50).entries().get(0).actorName()).isEqualTo("System");
    }

    @Test
    void pageSizeIsCappedSoNobodyCanDumpTheWholeTable() {
        when(auditLogRepository.findAllByOrderByCreatedAtDesc(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        service.list(0, 100_000);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(auditLogRepository).findAllByOrderByCreatedAtDesc(captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(200);
    }
}
