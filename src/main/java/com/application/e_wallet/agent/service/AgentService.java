package com.application.e_wallet.agent.service;

import com.application.e_wallet.agent.dto.AgentCreationRequest;
import com.application.e_wallet.agent.dto.AgentDetailResponse;
import com.application.e_wallet.agent.dto.AgentOnboardResponse;
import com.application.e_wallet.agent.dto.AgentSummaryResponse;
import com.application.e_wallet.agent.dto.AgentUpdateRequest;
import com.application.e_wallet.agent.dto.AgentUpdateResponse;
import com.application.e_wallet.agent.dto.FloatHealth;
import com.application.e_wallet.agent.entity.AgentEntity;
import com.application.e_wallet.agent.repository.AgentRepository;
import com.application.e_wallet.auth.repository.UserSessionRepository;
import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.common.exception.DuplicationResourceException;
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
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class AgentService {

    private static final int ONE_TIME_PIN_LENGTH = 4;

    private final AgentRepository agentRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final WalletRepository walletRepository;
    private final RegionRepository regionRepository;
    private final DistrictRepository districtRepository;
    private final UserSessionRepository userSessionRepository;
    private final PhoneNumberNormalizer phoneNumberNormalizer;
    private final OtpGenerator otpGenerator;
    private final PasswordEncoder pinEncoder;
    private final EmailService emailService;

    //======================== CREATE AN AGENT ==================== //
    @Transactional
    public AgentOnboardResponse createAgent(AgentCreationRequest request) {

        String email = request.email().trim().toLowerCase();
        String normalizedPhone = phoneNumberNormalizer.normalize(request.phone());

        if (userRepository.existsByEmail(email)) {
            throw new DuplicationResourceException("Email already exists");
        }

        if (userRepository.existsByPhone(normalizedPhone)) {
            throw new DuplicationResourceException("Phone already exists");
        }

        RoleEntity agentRole = roleRepository.findByName("AGENT")
                .orElseThrow(() -> new IllegalStateException("AGENT role not found"));

        RegionEntity region = regionRepository.findByExternalId(request.regionId())
                .orElseThrow(() -> new BadRequestException("Unknown regionId"));

        DistrictEntity district = districtRepository.findByExternalId(request.districtId())
                .orElseThrow(() -> new BadRequestException("Unknown districtId"));

        if (!district.getRegion().getId().equals(region.getId())) {
            throw new BadRequestException("districtId does not belong to regionId");
        }

        String middleName = request.middleName() != null ? request.middleName().trim() : "";
        String oneTimePin = otpGenerator.generate(ONE_TIME_PIN_LENGTH);

        UserEntity user = UserEntity.builder()
                .firstName(request.firstName().trim())
                .middleName(middleName)
                .lastName(request.lastName().trim())
                .email(email)
                .phone(normalizedPhone)
                .pinHash(pinEncoder.encode(oneTimePin))
                .status(UserStatus.PIN_CHANGE_REQUIRED)
                .build();

        user.getRoles().add(agentRole);

        UserEntity savedUser = userRepository.save(user); //======== user saved in database ========

        AgentEntity agent = AgentEntity.builder()
                .user(savedUser)
                .agentNumber(generateAgentCode())
                .gender(request.gender())
                .dateOfBirth(request.dateOfBirth())
                .region(region)
                .district(district)
                .build();

        AgentEntity savedAgent = agentRepository.save(agent); //=== Agent account created ====

        String floatNumber = generateFloatNumber();//todo: generate valid GL Accounts based on the BOT requirements
        String commissionNumber = "COM-" + savedAgent.getAgentNumber();

        WalletEntity floatWallet = WalletEntity.builder()
                .user(savedUser)
                .walletType(WalletType.AGENT_FLOAT)
                .walletNumber(floatNumber)
                .accountNumber(floatNumber)
                .balance(BigDecimal.ZERO)
                .currency("TZS")
                .status(WalletStatus.ACTIVE)
                .build();

        WalletEntity commissionWallet = WalletEntity.builder()
                .user(savedUser)
                .walletType(WalletType.AGENT_COMMISSION)
                .walletNumber(commissionNumber)
                .accountNumber(commissionNumber)
                .balance(BigDecimal.ZERO)
                .currency("TZS")
                .status(WalletStatus.ACTIVE)
                .build();

        WalletEntity savedFloatWallet = walletRepository.save(floatWallet);
        WalletEntity savedCommissionWallet = walletRepository.save(commissionWallet);

        // Sends the one-time PIN the agent must use to log in and then change.
        emailService.sendOneTimePin(savedUser.getEmail(), oneTimePin);

        return toOnboardResponse(savedAgent, savedUser, savedFloatWallet, savedCommissionWallet);
    }

    //======================= List AN AGENT ====================//
    @Transactional(readOnly = true)
    public List<AgentSummaryResponse> listAgents() {
        return agentRepository.findAll().stream()
                .map(this::toSummaryResponse)
                .toList();
    }

    // =================== List specific AGENT ================== //
    @Transactional(readOnly = true)
    public AgentDetailResponse listAgent(UUID agentId) {
        AgentEntity agent = findAgentOrThrow(agentId);
        return toDetailResponse(agent, agent.getUser());
    }

    //====================== Update AGENT ======================== //
    @Transactional
    public AgentUpdateResponse updateAgent(UUID agentId, AgentUpdateRequest request) {
        AgentEntity agent = findAgentOrThrow(agentId);
        UserEntity user = agent.getUser();

        RegionEntity region = regionRepository.findByExternalId(request.regionId())
                .orElseThrow(() -> new BadRequestException("Unknown regionId"));

        DistrictEntity district = districtRepository.findByExternalId(request.districtId())
                .orElseThrow(() -> new BadRequestException("Unknown districtId"));

        if (!district.getRegion().getId().equals(region.getId())) {
            throw new BadRequestException("districtId does not belong to regionId");
        }

        user.setFirstName(request.firstName().trim());
        user.setMiddleName(request.middleName() != null ? request.middleName().trim() : "");
        user.setLastName(request.lastName().trim());
        userRepository.save(user);

        agent.setGender(request.gender());
        agent.setDateOfBirth(request.dateOfBirth());
        agent.setRegion(region);
        agent.setDistrict(district);
        AgentEntity savedAgent = agentRepository.save(agent);

        return toUpdateResponse(savedAgent, user);
    }

    // ==================== Deactivate AGENT ===================== //
    @Transactional
    public void deactivateAgent(UUID agentId) {

        AgentEntity agent = findAgentOrThrow(agentId);
        UserEntity user = agent.getUser();

        if (user.getStatus() == UserStatus.DISABLED) {
            return;
        }

        user.setStatus(UserStatus.DISABLED);
        userRepository.save(user);

        WalletEntity floatWallet = walletRepository.findByUserIdAndWalletType(user.getId(), WalletType.AGENT_FLOAT)
                .orElseThrow(() -> new IllegalStateException("Agent float wallet not found"));
        floatWallet.setStatus(WalletStatus.CLOSED);
        walletRepository.save(floatWallet);

        WalletEntity commissionWallet = walletRepository.findByUserIdAndWalletType(user.getId(), WalletType.AGENT_COMMISSION)
                .orElseThrow(() -> new IllegalStateException("Agent commission wallet not found"));
        commissionWallet.setStatus(WalletStatus.CLOSED);
        walletRepository.save(commissionWallet);

        userSessionRepository.deleteAllByUserId(user.getId());
    }

    private AgentEntity findAgentOrThrow(UUID agentId) {
        return agentRepository.findById(agentId)
                .orElseThrow(() -> new BadRequestException("Agent not found"));
    }

    private AgentOnboardResponse toOnboardResponse(
            AgentEntity agent, UserEntity user, WalletEntity floatWallet, WalletEntity commissionWallet) {
        return new AgentOnboardResponse(
                agent.getId(),
                agent.getAgentNumber(),
                user.getId(),
                user.getFirstName(),
                user.getMiddleName(),
                user.getLastName(),
                user.getEmail(),
                user.getPhone(),
                agent.getGender(),
                agent.getDateOfBirth(),
                agent.getRegion().getExternalId(),
                agent.getRegion().getName(),
                agent.getDistrict().getExternalId(),
                agent.getDistrict().getName(),
                floatWallet.getWalletNumber(),
                floatWallet.getAccountNumber(),
                commissionWallet.getWalletNumber(),
                commissionWallet.getAccountNumber(),
                user.getStatus().name()
        );
    }

    private AgentDetailResponse toDetailResponse(AgentEntity agent, UserEntity user) {
        WalletEntity floatWallet = walletRepository.findByUserIdAndWalletType(user.getId(), WalletType.AGENT_FLOAT)
                .orElseThrow(() -> new IllegalStateException("Agent float wallet not found"));
        WalletEntity commissionWallet = walletRepository.findByUserIdAndWalletType(user.getId(), WalletType.AGENT_COMMISSION)
                .orElseThrow(() -> new IllegalStateException("Agent commission wallet not found"));

        return new AgentDetailResponse(
                agent.getId(),
                agent.getAgentNumber(),
                user.getId(),
                user.getFirstName(),
                user.getMiddleName(),
                user.getLastName(),
                user.getEmail(),
                user.getPhone(),
                agent.getGender(),
                agent.getDateOfBirth(),
                agent.getRegion().getExternalId(),
                agent.getRegion().getName(),
                agent.getDistrict().getExternalId(),
                agent.getDistrict().getName(),
                floatWallet.getBalance(),
                FloatHealth.fromBalance(floatWallet.getBalance()),
                commissionWallet.getBalance(),
                floatWallet.getAccountNumber(),
                floatWallet.getWalletNumber(),
                commissionWallet.getAccountNumber(),
                commissionWallet.getWalletNumber(),
                user.getStatus().name()
        );
    }

    private AgentSummaryResponse toSummaryResponse(AgentEntity agent) {
        UserEntity user = agent.getUser();
        WalletEntity floatWallet = walletRepository.findByUserIdAndWalletType(user.getId(), WalletType.AGENT_FLOAT)
                .orElseThrow(() -> new IllegalStateException("Agent float wallet not found"));

        return new AgentSummaryResponse(
                agent.getId(),
                agent.getAgentNumber(),
                user.getFirstName(),
                user.getMiddleName(),
                user.getLastName(),
                user.getPhone(),
                agent.getRegion().getName(),
                agent.getDistrict().getName(),
                user.getStatus().name(),
                floatWallet.getBalance()
        );
    }

    private AgentUpdateResponse toUpdateResponse(AgentEntity agent, UserEntity user) {
        return new AgentUpdateResponse(
                agent.getId(),
                user.getFirstName(),
                user.getMiddleName(),
                user.getLastName(),
                agent.getGender(),
                agent.getDateOfBirth(),
                agent.getRegion().getExternalId(),
                agent.getRegion().getName(),
                agent.getDistrict().getExternalId(),
                agent.getDistrict().getName(),
                user.getStatus().name()
        );
    }

    private String generateAgentCode() {
        String code;
        do {
            code = String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 1_000_000));
        } while (agentRepository.existsByAgentNumber(code));

        return code;
    }

    // Opaque visual number for the agent's float wallet/account.
    private String generateFloatNumber() {
        return "FLT-" +
                UUID.randomUUID()
                        .toString()
                        .replace("-", "")
                        .substring(0, 12)
                        .toUpperCase();
    }
}
