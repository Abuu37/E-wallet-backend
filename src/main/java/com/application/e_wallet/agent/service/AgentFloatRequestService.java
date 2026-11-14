package com.application.e_wallet.agent.service;

import com.application.e_wallet.agent.dto.FloatRequestCreateRequest;
import com.application.e_wallet.agent.dto.FloatRequestDecisionRequest;
import com.application.e_wallet.agent.dto.FloatRequestResponse;
import com.application.e_wallet.agent.dto.FloatTopupRequest;
import com.application.e_wallet.agent.entity.AgentEntity;
import com.application.e_wallet.agent.entity.AgentFloatRequestEntity;
import com.application.e_wallet.agent.entity.FloatRequestStatus;
import com.application.e_wallet.agent.repository.AgentFloatRequestRepository;
import com.application.e_wallet.agent.repository.AgentRepository;
import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.financial_account.entity.FinancialAccountEntity;
import com.application.e_wallet.financial_account.repository.FinancialAccountRepository;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.user.repository.UserRepository;
import com.application.e_wallet.wallet.entity.WalletEntity;
import com.application.e_wallet.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class AgentFloatRequestService {

    private final AgentFloatRequestRepository floatRequestRepository;
    private final AgentRepository agentRepository;
    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final FinancialAccountRepository financialAccountRepository;
    private final AgentService agentService;

    //=================== Agent create the float request ==================//
    @Transactional
    public FloatRequestResponse createRequest(UUID requestingUserId, FloatRequestCreateRequest request) {

        AgentEntity agent = agentRepository.findByUserId(requestingUserId)
                .orElseThrow(() -> new BadRequestException("No agent profile for this account"));

        if (request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException("Float request amount must be greater than zero");
        }

        if (request.reason() == null || request.reason().isBlank()) {
            throw new BadRequestException("Request reason is required");
        }

        AgentFloatRequestEntity floatRequest = AgentFloatRequestEntity.builder()
                .agent(agent)
                .amount(request.amount())
                .reason(request.reason())
                .status(FloatRequestStatus.PENDING)
                .build();

        AgentFloatRequestEntity savedRequest = floatRequestRepository.save(floatRequest);

        return toResponse(savedRequest);
    }

    //======================== LIST FLOAT REQUESTS =========================== //
    @Transactional(readOnly = true)
    public List<FloatRequestResponse> listRequests(UUID callerUserId, boolean canManage) {

        List<AgentFloatRequestEntity> requests;

        if (canManage) {
            requests = floatRequestRepository
                    .findAllByOrderByCreatedAtDesc();
        } else {

            AgentEntity agent = agentRepository.findByUserId(callerUserId)
                    .orElseThrow(() ->
                            new BadRequestException("No agent profile for this account"));

            requests = floatRequestRepository
                    .findAllByAgent_IdOrderByCreatedAtDesc(agent.getId());
        }

        return requests.stream()
                .map(this::toResponse)
                .toList();
    }

    //============================== GET SINGLE FLOAT REQUEST ================= //
    @Transactional(readOnly = true)
    public FloatRequestResponse getRequest(UUID requestId, UUID callerUserId, boolean canManage) {

        AgentFloatRequestEntity floatRequest = findOrThrow(requestId);

        if (!canManage && !floatRequest.getAgent().getUser().getId().equals(callerUserId)) {
            throw new BadRequestException("Float request not found");
        }

        return toResponse(floatRequest);
    }

    //======================== ADMIN APPROVED FLOAT REQUEST ================ //
    @Transactional
    public FloatRequestResponse approveRequest(UUID requestId, UUID adminUserId) {

        AgentFloatRequestEntity floatRequest = findOrThrow(requestId);

        if (floatRequest.getStatus() != FloatRequestStatus.PENDING) {
            throw new BadRequestException("Only a pending float request can be approved");
        }

        UserEntity admin = resolveAdmin(adminUserId);
        agentService.topupFloat(
                floatRequest.getAgent().getId(),
                new FloatTopupRequest(
                        floatRequest.getAmount(),
                        "Float request " + floatRequest.getId() + " approved"
                ),
                adminUserId
        );

        floatRequest.setStatus(FloatRequestStatus.APPROVED);
        floatRequest.setDecidedBy(admin);
        floatRequest.setDecidedAt(OffsetDateTime.now());
        floatRequest.setDecisionNote(null);

        AgentFloatRequestEntity saved = floatRequestRepository.save(floatRequest);
        return toResponse(saved);
    }

    //================================== ADMIN REJECTS FLOAT REQUEST ===================== //
    @Transactional
    public FloatRequestResponse rejectRequest(UUID requestId, UUID adminUserId, FloatRequestDecisionRequest request) {

        AgentFloatRequestEntity floatRequest = findForUpdate(requestId);

        if (floatRequest.getStatus() != FloatRequestStatus.PENDING) {
            throw new BadRequestException(
                    "Only a pending float request can be rejected"
            );
        }

        if (request.reason() == null || request.reason().isBlank()) {
            throw new BadRequestException("Rejection reason is required");
        }

        UserEntity admin = resolveAdmin(adminUserId);

        floatRequest.setStatus(FloatRequestStatus.REJECTED);
        floatRequest.setDecidedBy(admin);
        floatRequest.setDecisionNote(request.reason().trim());
        floatRequest.setDecidedAt(OffsetDateTime.now());

        return toResponse(floatRequestRepository.save(floatRequest));
    }

    //==================== FIND REQUEST ======================== //
    private AgentFloatRequestEntity findOrThrow(UUID requestId) {
        return floatRequestRepository.findById(requestId)
                .orElseThrow(() -> new BadRequestException("Float request not found"));
    }

    //=============== FIND REQUEST WITH DATABASE SET ================ //
    private AgentFloatRequestEntity findForUpdate(UUID requestId) {
        return floatRequestRepository.findById(requestId)
                .orElseThrow(() -> new BadRequestException("Float request not found"));
    }

    //=================== RESOLVE ADMIN ======================== //
    private UserEntity resolveAdmin(UUID adminUserId) {
        return userRepository.findById(adminUserId)
                .orElseThrow(() ->
                        new IllegalStateException("Authenticated user not found"));
    }

    private FloatRequestResponse toResponse(AgentFloatRequestEntity floatRequest) {
        AgentEntity agent = floatRequest.getAgent();
        UserEntity user = agent.getUser();
        UserEntity decidedBy = floatRequest.getDecidedBy();

        WalletEntity wallet = walletRepository.findByAgentId(agent.getId())
                .orElseThrow(() -> new IllegalStateException("Agent wallet not found"));
        FinancialAccountEntity account = financialAccountRepository.findByWallet_Agent_Id(agent.getId())
                .orElseThrow(() -> new IllegalStateException("Agent financial account not found"));

        return new FloatRequestResponse(
                floatRequest.getId(),
                floatRequest.getRequestRef(),
                agent.getId(),
                agent.getAgentNumber(),
                fullName(user),
                agent.getRegion().getName(),
                agent.getDistrict().getName(),
                user.getPhone(),
                wallet.getWalletNumber(),
                account.getAccountNumber(),
                floatRequest.getAmount(),
                floatRequest.getReason(),
                floatRequest.getStatus().name(),
                decidedBy != null ? fullName(decidedBy) : null,
                floatRequest.getDecisionNote(),
                floatRequest.getDecidedAt(),
                floatRequest.getCreatedAt()
        );
    }

    private static String fullName(UserEntity user) {
        return Stream.of(user.getFirstName(), user.getMiddleName(), user.getLastName())
                .filter(part -> part != null && !part.isBlank())
                .collect(Collectors.joining(" "));
    }
}
