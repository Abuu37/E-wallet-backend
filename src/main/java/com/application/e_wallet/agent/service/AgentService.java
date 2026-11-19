package com.application.e_wallet.agent.service;

import com.application.e_wallet.agent.dto.AgentAccountDetail;
import com.application.e_wallet.agent.dto.AgentCreationRequest;
import com.application.e_wallet.agent.dto.AgentDetailResponse;
import com.application.e_wallet.agent.dto.AgentListResponse;
import com.application.e_wallet.agent.dto.AgentOnboardResponse;
import com.application.e_wallet.agent.dto.AgentStatusUpdateRequest;
import com.application.e_wallet.agent.dto.AgentSummaryResponse;
import com.application.e_wallet.agent.dto.AgentTransactionSummary;
import com.application.e_wallet.agent.dto.AgentUpdateRequest;
import com.application.e_wallet.agent.dto.AgentUpdateResponse;
import com.application.e_wallet.agent.dto.AgentWalletSummary;
import com.application.e_wallet.agent.dto.FloatHealth;
import com.application.e_wallet.agent.entity.AgentEntity;
import com.application.e_wallet.agent.entity.AgentStatus;
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
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class    AgentService {

    private static final int ONE_TIME_PIN_LENGTH = 4;
    private static final String FLOAT_ACCOUNT_PREFIX = "01";
    private static final String COMMISSION_ACCOUNT_PREFIX = "02";

    private final AgentRepository agentRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final WalletRepository walletRepository;
    private final TransactionJournalLineRepository transactionJournalLineRepository;
    private final RegionRepository regionRepository;
    private final DistrictRepository districtRepository;
    private final UserSessionRepository userSessionRepository;
    private final PhoneNumberNormalizer phoneNumberNormalizer;
    private final OtpGenerator otpGenerator;
    private final PasswordEncoder pinEncoder;
    private final EmailService emailService;

    //======================== CREATE AN AGENT ==================== //
    @Transactional
    public AgentOnboardResponse createAgent(UUID adminUserId, AgentCreationRequest request) {

        String email = request.email().trim().toLowerCase();
        String normalizedPhone = phoneNumberNormalizer.normalize(request.phone());

        if (userRepository.existsByEmail(email)) {
            throw new DuplicationResourceException("Email already exists");
        }

        if (userRepository.existsByPhone(normalizedPhone)) {
            throw new DuplicationResourceException("Phone already exists");
        }

        if (userRepository.existsByLoginUsername(request.username().trim())) {
            throw new DuplicationResourceException("Username already exists");
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

        UserEntity admin = userRepository.findById(adminUserId)
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found"));

        String middleName = request.middleName() != null ? request.middleName().trim() : "";
        String oneTimePin = otpGenerator.generate(ONE_TIME_PIN_LENGTH);

        UserEntity user = UserEntity.builder()
                .loginUsername(request.username().trim())
                .email(email)
                .phone(normalizedPhone)
                .pinHash(pinEncoder.encode(oneTimePin))
                .status(UserStatus.PIN_CHANGE_REQUIRED)
                .role(agentRole)
                .build();

        UserEntity savedUser = userRepository.save(user); //======== user saved in database ========

        AgentEntity agent = AgentEntity.builder()
                .user(savedUser)
                .agentNumber(generateAgentCode())
                .firstName(request.firstName().trim())
                .middleName(middleName)
                .lastName(request.lastName().trim())
                .email(email)
                .phoneNumber(normalizedPhone)
                .businessName(request.businessName().trim())
                .tinNumber(request.tinNumber().trim())
                .agentStatus(AgentStatus.ACTIVE)
                .createdBy(admin)
                .gender(request.gender())
                .dateOfBirth(request.dateOfBirth())
                .region(region)
                .district(district)
                .build();

        AgentEntity savedAgent = agentRepository.save(agent); //=== Agent account created ====

        String floatNumber = generateFloatNumber();
        String commissionNumber = generateCommissionNumber();
        String agentFullName = savedAgent.getFirstName() + " " + savedAgent.getLastName();

        WalletEntity floatWallet = WalletEntity.builder()
                .user(savedUser)
                .walletType(WalletType.AGENT_FLOAT)
                .accountNumber(floatNumber)
                .accountName(agentFullName + " — Float")
                .balance(BigDecimal.ZERO)
                .currency("TZS")
                .status(WalletStatus.ACTIVE)
                .build();

        WalletEntity commissionWallet = WalletEntity.builder()
                .user(savedUser)
                .walletType(WalletType.AGENT_COMMISSION)
                .accountNumber(commissionNumber)
                .accountName(agentFullName + " — Commission")
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
    public AgentListResponse listAgents(Pageable pageable, AgentStatus status) {
        Page<AgentEntity> page = status != null
                ? agentRepository.findByAgentStatus(status, pageable)
                : agentRepository.findAll(pageable);
        List<AgentSummaryResponse> agents = page.getContent().stream()
                .map(this::toSummaryResponse)
                .toList();

        return new AgentListResponse(
                agents,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements());
    }

    // =================== List specific AGENT ================== //
    @Transactional(readOnly = true)
    public AgentDetailResponse listAgent(UUID agentId) {
        AgentEntity agent = findAgentOrThrow(agentId);
        return toDetailResponse(agent);
    }

    // =========== Agent views their OWN detail (self-service, no agentId param) ========== //
    @Transactional(readOnly = true)
    public AgentDetailResponse getOwnProfile(UUID callerUserId) {
        AgentEntity agent = agentRepository.findByUserId(callerUserId)
                .orElseThrow(() -> new BadRequestException("No agent profile for this account"));
        return toDetailResponse(agent);
    }

    //====================== Update AGENT ======================== //
    @Transactional
    public AgentUpdateResponse updateAgent(UUID agentId, AgentUpdateRequest request) {
        AgentEntity agent = findAgentOrThrow(agentId);

        RegionEntity region = regionRepository.findByExternalId(request.regionId())
                .orElseThrow(() -> new BadRequestException("Unknown regionId"));

        DistrictEntity district = districtRepository.findByExternalId(request.districtId())
                .orElseThrow(() -> new BadRequestException("Unknown districtId"));

        if (!district.getRegion().getId().equals(region.getId())) {
            throw new BadRequestException("districtId does not belong to regionId");
        }

        agent.setFirstName(request.firstName().trim());
        agent.setMiddleName(request.middleName() != null ? request.middleName().trim() : "");
        agent.setLastName(request.lastName().trim());
        agent.setGender(request.gender());
        agent.setDateOfBirth(request.dateOfBirth());
        agent.setRegion(region);
        agent.setDistrict(district);
        AgentEntity savedAgent = agentRepository.save(agent);

        return toUpdateResponse(savedAgent);
    }

    // ==================== Deactivate AGENT ===================== //
    // Kept as a convenience alias for updateAgentStatus(agentId, DEACTIVATED) - existing
    // callers of DELETE /api/v1/agents/{agentId} don't need to switch to the status endpoint.
    @Transactional
    public void deactivateAgent(UUID agentId) {
        AgentEntity agent = findAgentOrThrow(agentId);
        applyStatus(agent, AgentStatus.DEACTIVATED);
        agentRepository.save(agent);
    }

    // ==================== Change AGENT status (ACTIVE / SUSPENDED / DEACTIVATED) ========= //
    @Transactional
    public AgentUpdateResponse updateAgentStatus(UUID agentId, AgentStatusUpdateRequest request) {
        AgentEntity agent = findAgentOrThrow(agentId);
        applyStatus(agent, request.status());
        return toUpdateResponse(agentRepository.save(agent));
    }

    // Keeps the agent's status, the linked user's login status, and both wallets' status in
    // lockstep, and forces a logout whenever the agent is no longer ACTIVE. Idempotent - a
    // no-op when the agent is already in the requested status.
    private void applyStatus(AgentEntity agent, AgentStatus newStatus) {
        if (agent.getAgentStatus() == newStatus) {
            return;
        }

        UserEntity user = agent.getUser();
        agent.setAgentStatus(newStatus);
        user.setStatus(toUserStatus(newStatus));
        userRepository.save(user);

        WalletEntity floatWallet = walletRepository.findByUserIdAndWalletType(user.getId(), WalletType.AGENT_FLOAT)
                .orElseThrow(() -> new IllegalStateException("Agent float wallet not found"));
        floatWallet.setStatus(toWalletStatus(newStatus));
        walletRepository.save(floatWallet);

        WalletEntity commissionWallet = walletRepository.findByUserIdAndWalletType(user.getId(), WalletType.AGENT_COMMISSION)
                .orElseThrow(() -> new IllegalStateException("Agent commission wallet not found"));
        commissionWallet.setStatus(toWalletStatus(newStatus));
        walletRepository.save(commissionWallet);

        if (newStatus != AgentStatus.ACTIVE) {
            userSessionRepository.deleteAllByUserId(user.getId());
        }
    }

    private static UserStatus toUserStatus(AgentStatus status) {
        return switch (status) {
            case ACTIVE -> UserStatus.ACTIVE;
            case SUSPENDED -> UserStatus.SUSPENDED;
            case DEACTIVATED -> UserStatus.DISABLED;
        };
    }

    private static WalletStatus toWalletStatus(AgentStatus status) {
        return switch (status) {
            case ACTIVE -> WalletStatus.ACTIVE;
            case SUSPENDED -> WalletStatus.SUSPENDED;
            case DEACTIVATED -> WalletStatus.CLOSED;
        };
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
                agent.getFirstName(),
                agent.getMiddleName(),
                agent.getLastName(),
                agent.getEmail(),
                agent.getPhoneNumber(),
                agent.getGender(),
                agent.getDateOfBirth(),
                agent.getRegion().getExternalId(),
                agent.getRegion().getName(),
                agent.getDistrict().getExternalId(),
                agent.getDistrict().getName(),
                floatWallet.getAccountNumber(),
                commissionWallet.getAccountNumber(),
                agent.getAgentStatus().name()
        );
    }

    private AgentDetailResponse toDetailResponse(AgentEntity agent) {
        UserEntity user = agent.getUser();
        WalletEntity floatWallet = walletRepository.findByUserIdAndWalletType(user.getId(), WalletType.AGENT_FLOAT)
                .orElseThrow(() -> new IllegalStateException("Agent float wallet not found"));
        WalletEntity commissionWallet = walletRepository.findByUserIdAndWalletType(user.getId(), WalletType.AGENT_COMMISSION)
                .orElseThrow(() -> new IllegalStateException("Agent commission wallet not found"));

        return new AgentDetailResponse(
                agent.getId(),
                agent.getAgentNumber(),
                user.getId(),
                agent.getFirstName(),
                agent.getMiddleName(),
                agent.getLastName(),
                agent.getEmail(),
                agent.getPhoneNumber(),
                agent.getGender(),
                agent.getDateOfBirth(),
                agent.getRegion().getExternalId(),
                agent.getRegion().getName(),
                agent.getDistrict().getExternalId(),
                agent.getDistrict().getName(),
                FloatHealth.fromBalance(floatWallet.getBalance()),
                toAccountDetail(floatWallet),
                toAccountDetail(commissionWallet),
                recentTransactionsFor(floatWallet, commissionWallet),
                agent.getAgentStatus().name()
        );
    }

    private AgentSummaryResponse toSummaryResponse(AgentEntity agent) {
        UserEntity user = agent.getUser();
        WalletEntity floatWallet = walletRepository.findByUserIdAndWalletType(user.getId(), WalletType.AGENT_FLOAT)
                .orElseThrow(() -> new IllegalStateException("Agent float wallet not found"));
        WalletEntity commissionWallet = walletRepository.findByUserIdAndWalletType(user.getId(), WalletType.AGENT_COMMISSION)
                .orElseThrow(() -> new IllegalStateException("Agent commission wallet not found"));

        return new AgentSummaryResponse(
                agent.getId(),
                agent.getAgentNumber(),
                agent.getFirstName(),
                agent.getMiddleName(),
                agent.getLastName(),
                agent.getPhoneNumber(),
                agent.getRegion().getName(),
                agent.getDistrict().getName(),
                agent.getAgentStatus().name(),
                toWalletSummary(floatWallet),
                toWalletSummary(commissionWallet)
        );
    }

    private AgentUpdateResponse toUpdateResponse(AgentEntity agent) {
        return new AgentUpdateResponse(
                agent.getId(),
                agent.getFirstName(),
                agent.getMiddleName(),
                agent.getLastName(),
                agent.getGender(),
                agent.getDateOfBirth(),
                agent.getRegion().getExternalId(),
                agent.getRegion().getName(),
                agent.getDistrict().getExternalId(),
                agent.getDistrict().getName(),
                agent.getAgentStatus().name()
        );
    }

    private AgentWalletSummary toWalletSummary(WalletEntity wallet) {
        return new AgentWalletSummary(wallet.getAccountNumber(), wallet.getBalance(), wallet.getCurrency());
    }

    private AgentAccountDetail toAccountDetail(WalletEntity wallet) {
        return new AgentAccountDetail(
                wallet.getAccountNumber(),
                wallet.getBalance(),
                wallet.getStatus().name(),
                lastTransactionAt(wallet.getId())
        );
    }

    private OffsetDateTime lastTransactionAt(UUID walletId) {
        return transactionJournalLineRepository.findFirstByWalletIdOrderByJournal_CreatedAtDesc(walletId)
                .map(line -> line.getJournal().getCreatedAt())
                .orElse(null);
    }

    private List<AgentTransactionSummary> recentTransactionsFor(WalletEntity floatWallet, WalletEntity commissionWallet) {
        Map<UUID, String> accountNumberByWalletId = Map.of(
                floatWallet.getId(), floatWallet.getAccountNumber(),
                commissionWallet.getId(), commissionWallet.getAccountNumber()
        );

        return transactionJournalLineRepository
                .findTop5ByWalletIdInOrderByJournal_CreatedAtDesc(List.copyOf(accountNumberByWalletId.keySet()))
                .stream()
                .map(line -> new AgentTransactionSummary(
                        line.getJournal().getReferenceNumber(),
                        accountNumberByWalletId.get(line.getWalletId()),
                        line.getJournal().getTransactionType().name(),
                        line.getEntryType().name(),
                        line.getAmount(),
                        "COMPLETED",
                        line.getJournal().getCreatedAt()
                ))
                .toList();
    }

    private String generateAgentCode() {
        String code;
        do {
            code = String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 1_000_000));
        } while (agentRepository.existsByAgentNumber(code));

        return code;
    }

    // 12-digit account number, "01" lead (shared with CUSTOMER_MAIN by design), retried on collision.
    private String generateFloatNumber() {
        String number;
        do {
            number = FLOAT_ACCOUNT_PREFIX + randomDigits(10);
        } while (walletRepository.existsByAccountNumber(number));

        return number;
    }

    // 12-digit account number, "02" lead, retried on collision.
    private String generateCommissionNumber() {
        String number;
        do {
            number = COMMISSION_ACCOUNT_PREFIX + randomDigits(10);
        } while (walletRepository.existsByAccountNumber(number));

        return number;
    }

    private static String randomDigits(int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(ThreadLocalRandom.current().nextInt(10));
        }
        return sb.toString();
    }
}
