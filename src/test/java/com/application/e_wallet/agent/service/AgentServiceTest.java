package com.application.e_wallet.agent.service;

import com.application.e_wallet.agent.dto.AgentCreationRequest;
import com.application.e_wallet.agent.dto.AgentStatusUpdateRequest;
import com.application.e_wallet.agent.entity.AgentEntity;
import com.application.e_wallet.agent.entity.AgentStatus;
import com.application.e_wallet.agent.entity.Gender;
import com.application.e_wallet.agent.repository.AgentRepository;
import com.application.e_wallet.auth.repository.UserSessionRepository;
import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.common.exception.DuplicationResourceException;
import com.application.e_wallet.common.validation.PhoneNumberNormalizer;
import com.application.e_wallet.ledger.repository.TransactionJournalLineRepository;
import com.application.e_wallet.location.entity.DistrictEntity;
import com.application.e_wallet.location.entity.RegionEntity;
import com.application.e_wallet.location.repository.DistrictRepository;
import com.application.e_wallet.location.repository.RegionRepository;
import com.application.e_wallet.otp.service.EmailService;
import com.application.e_wallet.otp.service.OtpGenerator;
import com.application.e_wallet.role.entity.RoleEntity;
import com.application.e_wallet.role.repository.RoleRepository;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.user.entity.UserStatus;
import com.application.e_wallet.user.repository.UserRepository;
import com.application.e_wallet.wallet.entity.WalletEntity;
import com.application.e_wallet.wallet.entity.WalletStatus;
import com.application.e_wallet.wallet.entity.WalletType;
import com.application.e_wallet.wallet.repository.WalletRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentServiceTest {

    @Mock private com.application.e_wallet.audit.service.AuditService auditService;
    @Mock private AgentRepository agentRepository;
    @Mock private UserRepository userRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private WalletRepository walletRepository;
    @Mock private TransactionJournalLineRepository transactionJournalLineRepository;
    @Mock private RegionRepository regionRepository;
    @Mock private DistrictRepository districtRepository;
    @Mock private UserSessionRepository userSessionRepository;
    @Mock private PhoneNumberNormalizer phoneNumberNormalizer;
    @Mock private OtpGenerator otpGenerator;
    @Mock private PasswordEncoder pinEncoder;
    @Mock private EmailService emailService;

        @Mock private com.application.e_wallet.common.identifier.IdentifierService identifierService;

@InjectMocks
    private AgentService agentService;

    @org.junit.jupiter.api.BeforeEach
    void stubIdentifiers() {
        org.mockito.Mockito.lenient().when(identifierService.nextAgentNumber()).thenReturn("AG-10234");
    }

    @Test
    void createAgentCreatesBothFloatAndCommissionWallets() {
        AgentCreationRequest request = new AgentCreationRequest(
                "Jane", "M", "Doe", "jane.doe@example.com", "0712345678",
                Gender.FEMALE, LocalDate.of(1995, 1, 1), "TZ01", "TZ0101",
                "Jane's Shop", "TIN-12345"
        );

        RoleEntity agentRole = RoleEntity.builder().id(UUID.randomUUID()).name("AGENT").build();
        RegionEntity region = RegionEntity.builder().id(UUID.randomUUID()).externalId("TZ01").name("Dar").build();
        DistrictEntity district = DistrictEntity.builder().id(UUID.randomUUID()).externalId("TZ0101").region(region).name("Ilala").build();

        UUID adminUserId = UUID.randomUUID();
        UserEntity admin = UserEntity.builder().id(adminUserId).build();

        lenient().when(userRepository.existsByEmail(anyString())).thenReturn(false);
        lenient().when(userRepository.existsByPhone(anyString())).thenReturn(false);
        when(phoneNumberNormalizer.normalize("0712345678")).thenReturn("+255712345678");
        when(roleRepository.findByName("AGENT")).thenReturn(Optional.of(agentRole));
        when(regionRepository.findByExternalId("TZ01")).thenReturn(Optional.of(region));
        when(districtRepository.findByExternalId("TZ0101")).thenReturn(Optional.of(district));
        when(otpGenerator.generate(4)).thenReturn("1234");
        when(pinEncoder.encode(anyString())).thenReturn("hashed-pin");
        when(userRepository.findById(adminUserId)).thenReturn(Optional.of(admin));

        UUID userId = UUID.randomUUID();
        when(userRepository.save(any())).thenAnswer(inv -> {
            UserEntity user = inv.getArgument(0);
            user.setId(userId);
            return user;
        });

        UUID agentId = UUID.randomUUID();
        when(agentRepository.save(any())).thenAnswer(inv -> {
            AgentEntity agent = inv.getArgument(0);
            agent.setId(agentId);
            agent.setAgentNumber("100001");
            return agent;
        });

        when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = agentService.createAgent(adminUserId, request);

        assertThat(response.status()).isEqualTo(AgentStatus.ACTIVE.name());

        ArgumentCaptor<WalletEntity> captor = ArgumentCaptor.forClass(WalletEntity.class);
        verify(walletRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        List<WalletType> savedTypes = captor.getAllValues().stream().map(WalletEntity::getWalletType).toList();
        assertThat(savedTypes).containsExactlyInAnyOrder(WalletType.AGENT_FLOAT, WalletType.AGENT_COMMISSION);
        assertThat(captor.getAllValues()).allMatch(w -> w.getBalance().compareTo(BigDecimal.ZERO) == 0);
    }

    @Test
    void deactivateAgentClosesBothWalletsAndSetsBothStatuses() {
        UUID agentId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        UserEntity user = UserEntity.builder().id(userId).status(UserStatus.ACTIVE).build();
        AgentEntity agent = AgentEntity.builder().id(agentId).user(user).agentStatus(AgentStatus.ACTIVE).build();

        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(agentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WalletEntity floatWallet = WalletEntity.builder()
                .id(UUID.randomUUID()).user(user).walletType(WalletType.AGENT_FLOAT)
                .balance(BigDecimal.ZERO).status(WalletStatus.ACTIVE).build();
        WalletEntity commissionWallet = WalletEntity.builder()
                .id(UUID.randomUUID()).user(user).walletType(WalletType.AGENT_COMMISSION)
                .balance(BigDecimal.ZERO).status(WalletStatus.ACTIVE).build();

        when(walletRepository.findByUserIdAndWalletType(userId, WalletType.AGENT_FLOAT))
                .thenReturn(Optional.of(floatWallet));
        when(walletRepository.findByUserIdAndWalletType(userId, WalletType.AGENT_COMMISSION))
                .thenReturn(Optional.of(commissionWallet));
        when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        agentService.deactivateAgent(agentId);

        assertThat(user.getStatus()).isEqualTo(UserStatus.DISABLED);
        assertThat(agent.getAgentStatus()).isEqualTo(AgentStatus.DEACTIVATED);

        verify(walletRepository, org.mockito.Mockito.times(2))
                .save(org.mockito.ArgumentMatchers.argThat(w -> w.getStatus() == WalletStatus.CLOSED));
    }

    @Test
    void listAgentsWithNoStatusFilterUsesFindAll() {
        Pageable pageable = PageRequest.of(0, 20);
        when(agentRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of()));

        agentService.listAgents(pageable, null);

        verify(agentRepository).findAll(pageable);
        verify(agentRepository, never()).findByAgentStatus(any(), any());
    }

    @Test
    void listAgentsWithStatusFilterUsesTheStatusScopedQueryNotFindAll() {
        Pageable pageable = PageRequest.of(0, 20);
        when(agentRepository.findByAgentStatus(AgentStatus.SUSPENDED, pageable)).thenReturn(new PageImpl<>(List.of()));

        agentService.listAgents(pageable, AgentStatus.SUSPENDED);

        verify(agentRepository).findByAgentStatus(AgentStatus.SUSPENDED, pageable);
        verify(agentRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    void updateAgentStatusToSuspendedSetsWalletsAndUserSuspendedAndKillsSessions() {
        UUID agentId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        UserEntity user = UserEntity.builder().id(userId).status(UserStatus.ACTIVE).build();
        AgentEntity agent = AgentEntity.builder().id(agentId).user(user).agentStatus(AgentStatus.ACTIVE)
                .region(RegionEntity.builder().id(UUID.randomUUID()).externalId("TZ01").name("Dar").build())
                .district(DistrictEntity.builder().id(UUID.randomUUID()).externalId("TZ0101").name("Ilala").build())
                .build();

        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(agentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WalletEntity floatWallet = WalletEntity.builder()
                .id(UUID.randomUUID()).user(user).walletType(WalletType.AGENT_FLOAT)
                .balance(BigDecimal.ZERO).status(WalletStatus.ACTIVE).build();
        WalletEntity commissionWallet = WalletEntity.builder()
                .id(UUID.randomUUID()).user(user).walletType(WalletType.AGENT_COMMISSION)
                .balance(BigDecimal.ZERO).status(WalletStatus.ACTIVE).build();

        when(walletRepository.findByUserIdAndWalletType(userId, WalletType.AGENT_FLOAT))
                .thenReturn(Optional.of(floatWallet));
        when(walletRepository.findByUserIdAndWalletType(userId, WalletType.AGENT_COMMISSION))
                .thenReturn(Optional.of(commissionWallet));

        var response = agentService.updateAgentStatus(agentId, new AgentStatusUpdateRequest(AgentStatus.SUSPENDED, "Suspended after a failed audit"));
        org.mockito.Mockito.verify(auditService).recordAsCurrentUser(org.mockito.ArgumentMatchers.eq("AGENT_STATUS_CHANGE"), org.mockito.ArgumentMatchers.eq("AGENT"), org.mockito.ArgumentMatchers.eq(agentId), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("Suspended after a failed audit"));

        assertThat(response.status()).isEqualTo(AgentStatus.SUSPENDED.name());
        assertThat(user.getStatus()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(floatWallet.getStatus()).isEqualTo(WalletStatus.SUSPENDED);
        assertThat(commissionWallet.getStatus()).isEqualTo(WalletStatus.SUSPENDED);
        verify(userSessionRepository).deleteAllByUserId(userId);
    }

    @Test
    void updateAgentStatusBackToActiveDoesNotKillSessions() {
        UUID agentId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        UserEntity user = UserEntity.builder().id(userId).status(UserStatus.SUSPENDED).build();
        AgentEntity agent = AgentEntity.builder().id(agentId).user(user).agentStatus(AgentStatus.SUSPENDED)
                .region(RegionEntity.builder().id(UUID.randomUUID()).externalId("TZ01").name("Dar").build())
                .district(DistrictEntity.builder().id(UUID.randomUUID()).externalId("TZ0101").name("Ilala").build())
                .build();

        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(agentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WalletEntity floatWallet = WalletEntity.builder()
                .id(UUID.randomUUID()).user(user).walletType(WalletType.AGENT_FLOAT)
                .balance(BigDecimal.ZERO).status(WalletStatus.SUSPENDED).build();
        WalletEntity commissionWallet = WalletEntity.builder()
                .id(UUID.randomUUID()).user(user).walletType(WalletType.AGENT_COMMISSION)
                .balance(BigDecimal.ZERO).status(WalletStatus.SUSPENDED).build();

        when(walletRepository.findByUserIdAndWalletType(userId, WalletType.AGENT_FLOAT))
                .thenReturn(Optional.of(floatWallet));
        when(walletRepository.findByUserIdAndWalletType(userId, WalletType.AGENT_COMMISSION))
                .thenReturn(Optional.of(commissionWallet));

        var response = agentService.updateAgentStatus(agentId, new AgentStatusUpdateRequest(AgentStatus.ACTIVE, "Reinstated after review"));

        assertThat(response.status()).isEqualTo(AgentStatus.ACTIVE.name());
        assertThat(floatWallet.getStatus()).isEqualTo(WalletStatus.ACTIVE);
        verify(userSessionRepository, never()).deleteAllByUserId(any());
    }

    @Test
    void updateAgentStatusIsANoOpWhenAlreadyInTheRequestedStatus() {
        UUID agentId = UUID.randomUUID();
        UserEntity user = UserEntity.builder().id(UUID.randomUUID()).status(UserStatus.ACTIVE).build();
        AgentEntity agent = AgentEntity.builder().id(agentId).user(user).agentStatus(AgentStatus.ACTIVE)
                .region(RegionEntity.builder().id(UUID.randomUUID()).externalId("TZ01").name("Dar").build())
                .district(DistrictEntity.builder().id(UUID.randomUUID()).externalId("TZ0101").name("Ilala").build())
                .build();

        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
        when(agentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        agentService.updateAgentStatus(agentId, new AgentStatusUpdateRequest(AgentStatus.ACTIVE, "Reinstated after review"));
        org.mockito.Mockito.verify(auditService, org.mockito.Mockito.never()).recordAsCurrentUser(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        verify(userRepository, never()).save(any());
        verify(walletRepository, never()).findByUserIdAndWalletType(any(), any());
        verify(userSessionRepository, never()).deleteAllByUserId(any());
    }

    @Test
    void getOwnProfileResolvesTheAgentByCallerUserIdNotByAgentId() {
        UUID callerUserId = UUID.randomUUID();
        UserEntity user = UserEntity.builder().id(callerUserId).build();
        AgentEntity agent = AgentEntity.builder().id(UUID.randomUUID()).user(user)
                .agentNumber("40625").businessName("Ilala Mchikichini Wakala").agentStatus(AgentStatus.ACTIVE)
                .region(RegionEntity.builder().id(UUID.randomUUID()).externalId("TZ01").name("Dar").build())
                .district(DistrictEntity.builder().id(UUID.randomUUID()).externalId("TZ0101").name("Ilala").build())
                .build();

        WalletEntity floatWallet = WalletEntity.builder()
                .id(UUID.randomUUID()).accountNumber("019482716350")
                .balance(new BigDecimal("750000.00")).status(WalletStatus.ACTIVE).build();
        WalletEntity commissionWallet = WalletEntity.builder()
                .id(UUID.randomUUID()).accountNumber("025819304721")
                .balance(new BigDecimal("18500.00")).status(WalletStatus.ACTIVE).build();

        when(agentRepository.findByUserId(callerUserId)).thenReturn(Optional.of(agent));
        when(walletRepository.findByUserIdAndWalletType(callerUserId, WalletType.AGENT_FLOAT))
                .thenReturn(Optional.of(floatWallet));
        when(walletRepository.findByUserIdAndWalletType(callerUserId, WalletType.AGENT_COMMISSION))
                .thenReturn(Optional.of(commissionWallet));

        var response = agentService.getOwnProfile(callerUserId);

        assertThat(response.agentId()).isEqualTo(agent.getId());
        assertThat(response.agentNumber()).isEqualTo("40625");
        assertThat(response.floatAccount().accountNumber()).isEqualTo("019482716350");
        assertThat(response.commissionAccount().accountNumber()).isEqualTo("025819304721");
        verify(agentRepository, never()).findById(any());
    }

    @Test
    void getOwnProfileThrowsWhenCallerHasNoAgentProfile() {
        UUID callerUserId = UUID.randomUUID();
        when(agentRepository.findByUserId(callerUserId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> agentService.getOwnProfile(callerUserId))
                .isInstanceOf(BadRequestException.class);
    }
}
