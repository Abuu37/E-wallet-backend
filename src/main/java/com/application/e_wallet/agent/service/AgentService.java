package com.application.e_wallet.agent.service;

import com.application.e_wallet.agent.dto.AgentCreationRequest;
import com.application.e_wallet.agent.dto.AgentResponse;
import com.application.e_wallet.agent.dto.AgentUpdateRequest;
import com.application.e_wallet.agent.dto.FloatHealth;
import com.application.e_wallet.agent.dto.FloatTopupRequest;
import com.application.e_wallet.agent.dto.FloatWithdrawRequest;
import com.application.e_wallet.agent.entity.AgentEntity;
import com.application.e_wallet.agent.repository.AgentRepository;
import com.application.e_wallet.auth.repository.UserSessionRepository;
import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.common.exception.DuplicationResourceException;
import com.application.e_wallet.common.validation.PhoneNumberNormalizer;
import com.application.e_wallet.company_wallet.dto.AgentWithdrawRequest;
import com.application.e_wallet.company_wallet.dto.AllocateFloatRequest;
import com.application.e_wallet.company_wallet.service.CompanyMasterWalletService;
import com.application.e_wallet.financial_account.entity.FinancialAccountEntity;
import com.application.e_wallet.financial_account.entity.FinancialAccountStatus;
import com.application.e_wallet.financial_account.entity.FinancialAccountType;
import com.application.e_wallet.financial_account.repository.FinancialAccountRepository;
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
    private final FinancialAccountRepository financialAccountRepository;
    private final RegionRepository regionRepository;
    private final DistrictRepository districtRepository;
    private final UserSessionRepository userSessionRepository;
    private final CompanyMasterWalletService companyMasterWalletService;
    private final PhoneNumberNormalizer phoneNumberNormalizer;
    private final OtpGenerator otpGenerator;
    private final PasswordEncoder pinEncoder;
    private final EmailService emailService;

    //======================== CREATE AN AGENT ==================== //
    @Transactional
    public AgentResponse createAgent(AgentCreationRequest request) {

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


        WalletEntity wallet = WalletEntity.builder()
                .agent(savedAgent)
                .walletNumber(floatNumber)
                .currency("TZS")
                .status(WalletStatus.ACTIVE)
                .build();

        WalletEntity savedWallet = walletRepository.save(wallet);


        FinancialAccountEntity account = FinancialAccountEntity.builder()
                .accountNumber(floatNumber)
                .accountType(FinancialAccountType.AGENT_FLOAT)
                .currency("TZS")
                .currentBalance(BigDecimal.ZERO)
                .status(FinancialAccountStatus.ACTIVE)
                .wallet(savedWallet)
                .build();

        FinancialAccountEntity savedAccount = financialAccountRepository.save(account);

        savedWallet.setFinancialAccount(savedAccount);

        // Sends the one-time PIN the agent must use to log in and then change.
        emailService.sendOneTimePin(savedUser.getEmail(), oneTimePin);

        return toAgentResponse(
                savedAgent,
                savedUser,
                savedWallet,
                savedAccount
        );
    }

    //======================= List AN AGENT ====================//
    @Transactional(readOnly = true)
    public List<AgentResponse> listAgents() {
        return agentRepository.findAll().stream()
                .map(this::toAgentResponse)
                .toList();
    }

    // =================== List specific AGENT ================== //
    @Transactional(readOnly = true)
    public AgentResponse listAgent(UUID agentId) {
        return toAgentResponse(findAgentOrThrow(agentId));
    }

    //====================== Update AGENT ======================== //
    @Transactional
    public AgentResponse updateAgent(UUID agentId, AgentUpdateRequest request) {
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

        return toAgentResponse(savedAgent, user);
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

        WalletEntity wallet = walletRepository.findByAgentId(agentId)
                .orElseThrow(() -> new IllegalStateException("Agent wallet not found"));
        wallet.setStatus(WalletStatus.CLOSED);
        walletRepository.save(wallet);

        FinancialAccountEntity account = financialAccountRepository.findByWallet_Agent_Id(agentId)
                .orElseThrow(() -> new IllegalStateException("Agent financial account not found"));
        account.setStatus(FinancialAccountStatus.CLOSED);
        financialAccountRepository.save(account);

        userSessionRepository.deleteAllByUserId(user.getId());
    }

    //================= Topup Float to AGENT Account ================ //
    @Transactional
    public AgentResponse topupFloat(UUID agentId, FloatTopupRequest request, UUID adminUserId) {
        companyMasterWalletService.allocate(
                new AllocateFloatRequest(agentId, request.amount(), request.description()),
                adminUserId
        );
        return toAgentResponse(findAgentOrThrow(agentId));
    }

    //================== Withdraw Float from Agent Account ============= //
    @Transactional
    public AgentResponse withdrawFloat(UUID agentId, FloatWithdrawRequest request, UUID adminUserId) {
        companyMasterWalletService.withdraw(
                new AgentWithdrawRequest(agentId, request.amount(), request.description()),
                adminUserId
        );
        return toAgentResponse(findAgentOrThrow(agentId));
    }

    private AgentEntity findAgentOrThrow(UUID agentId) {
        return agentRepository.findById(agentId)
                .orElseThrow(() -> new BadRequestException("Agent not found"));
    }

    private AgentResponse toAgentResponse(AgentEntity agent) {
        return toAgentResponse(agent, agent.getUser());
    }

    private AgentResponse toAgentResponse(AgentEntity agent, UserEntity user) {
        WalletEntity wallet = walletRepository.findByAgentId(agent.getId())
                .orElseThrow(() -> new IllegalStateException("Agent wallet not found"));
        FinancialAccountEntity account = financialAccountRepository.findByWallet_Agent_Id(agent.getId())
                .orElseThrow(() -> new IllegalStateException("Agent financial account not found"));
        return toAgentResponse(agent, user, wallet, account);
    }

    private AgentResponse toAgentResponse(AgentEntity agent, UserEntity user, WalletEntity wallet, FinancialAccountEntity account) {
        return new AgentResponse(
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
                wallet.getWalletNumber(),
                account.getAccountNumber(),
                account.getCurrentBalance(),
                FloatHealth.fromBalance(account.getCurrentBalance()),
                BigDecimal.ZERO.setScale(2),
                0L,
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
