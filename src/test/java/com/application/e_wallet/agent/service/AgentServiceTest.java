package com.application.e_wallet.agent.service;

import com.application.e_wallet.agent.dto.AgentCreationRequest;
import com.application.e_wallet.agent.entity.AgentEntity;
import com.application.e_wallet.agent.entity.Gender;
import com.application.e_wallet.agent.repository.AgentRepository;
import com.application.e_wallet.auth.repository.UserSessionRepository;
import com.application.e_wallet.common.validation.PhoneNumberNormalizer;
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
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentServiceTest {

    @Mock private AgentRepository agentRepository;
    @Mock private UserRepository userRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private WalletRepository walletRepository;
    @Mock private RegionRepository regionRepository;
    @Mock private DistrictRepository districtRepository;
    @Mock private UserSessionRepository userSessionRepository;
    @Mock private PhoneNumberNormalizer phoneNumberNormalizer;
    @Mock private OtpGenerator otpGenerator;
    @Mock private PasswordEncoder pinEncoder;
    @Mock private EmailService emailService;

    @InjectMocks
    private AgentService agentService;

    @Test
    void createAgentCreatesBothFloatAndCommissionWallets() {
        AgentCreationRequest request = new AgentCreationRequest(
                "Jane", "M", "Doe", "jane.doe@example.com", "0712345678",
                Gender.FEMALE, LocalDate.of(1995, 1, 1), "TZ01", "TZ0101"
        );

        RoleEntity agentRole = RoleEntity.builder().id(UUID.randomUUID()).name("AGENT").build();
        RegionEntity region = RegionEntity.builder().id(UUID.randomUUID()).externalId("TZ01").name("Dar").build();
        DistrictEntity district = DistrictEntity.builder().id(UUID.randomUUID()).externalId("TZ0101").region(region).name("Ilala").build();

        lenient().when(userRepository.existsByEmail(anyString())).thenReturn(false);
        lenient().when(userRepository.existsByPhone(anyString())).thenReturn(false);
        when(phoneNumberNormalizer.normalize("0712345678")).thenReturn("+255712345678");
        when(roleRepository.findByName("AGENT")).thenReturn(Optional.of(agentRole));
        when(regionRepository.findByExternalId("TZ01")).thenReturn(Optional.of(region));
        when(districtRepository.findByExternalId("TZ0101")).thenReturn(Optional.of(district));
        when(otpGenerator.generate(4)).thenReturn("1234");
        when(pinEncoder.encode(anyString())).thenReturn("hashed-pin");

        UUID userId = UUID.randomUUID();
        when(userRepository.save(any())).thenAnswer(inv -> {
            UserEntity user = inv.getArgument(0);
            user.setId(userId);
            return user;
        });

        UUID agentId = UUID.randomUUID();
        when(agentRepository.existsByAgentNumber(anyString())).thenReturn(false);
        when(agentRepository.save(any())).thenAnswer(inv -> {
            AgentEntity agent = inv.getArgument(0);
            agent.setId(agentId);
            agent.setAgentNumber("100001");
            return agent;
        });

        when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        agentService.createAgent(request);

        ArgumentCaptor<WalletEntity> captor = ArgumentCaptor.forClass(WalletEntity.class);
        verify(walletRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        List<WalletType> savedTypes = captor.getAllValues().stream().map(WalletEntity::getWalletType).toList();
        assertThat(savedTypes).containsExactlyInAnyOrder(WalletType.AGENT_FLOAT, WalletType.AGENT_COMMISSION);
        assertThat(captor.getAllValues()).allMatch(w -> w.getBalance().compareTo(BigDecimal.ZERO) == 0);
    }

    @Test
    void deactivateAgentClosesBothWallets() {
        UUID agentId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        UserEntity user = UserEntity.builder().id(userId).status(UserStatus.ACTIVE).build();
        AgentEntity agent = AgentEntity.builder().id(agentId).user(user).build();

        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

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

        verify(walletRepository, org.mockito.Mockito.times(2))
                .save(org.mockito.ArgumentMatchers.argThat(w -> w.getStatus() == WalletStatus.CLOSED));
    }
}
