package com.application.e_wallet.agent.service;

import com.application.e_wallet.agent.dto.AgentCreationRequest;
import com.application.e_wallet.agent.dto.AgentCreationResponse;
import com.application.e_wallet.agent.entity.AgentEntity;
import com.application.e_wallet.agent.repository.AgentRepository;
import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.common.exception.DuplicationResourceException;
import com.application.e_wallet.common.validation.PhoneNumberNormalizer;
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
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
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
    private final PhoneNumberNormalizer phoneNumberNormalizer;
    private final OtpGenerator otpGenerator;
    private final PasswordEncoder pinEncoder;
    private final EmailService emailService;

    @Transactional
    public AgentCreationResponse createAgent(AgentCreationRequest request) {

        String email = request.email().trim().toLowerCase();
        String normalizedPhone = phoneNumberNormalizer.normalize(request.phone());

        if (userRepository.existsByEmail(email)) {
            throw new DuplicationResourceException("Email already exists");
        }

        if (userRepository.existsByPhone(normalizedPhone)) {
            throw new DuplicationResourceException("Phone already exists");
        }

        RoleEntity agentRole = roleRepository.findByName("AGENT")
                .orElseThrow(() ->
                        new IllegalStateException(
                                "AGENT role not found"
                        ));

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

        String floatNumber = generateFloatNumber();

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

        return new AgentCreationResponse(
                savedAgent.getId(),
                savedAgent.getAgentNumber(),
                savedUser.getId(),
                savedUser.getPhone(),
                savedWallet.getWalletNumber(),
                savedAccount.getAccountNumber(),
                savedAccount.getCurrentBalance()
        );
    }

    // Short numeric till code the customer keys in at withdrawal (like an M-Pesa agent/store number).
    // Numeric codespace is small enough that collisions are plausible, so retry until unique.
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
