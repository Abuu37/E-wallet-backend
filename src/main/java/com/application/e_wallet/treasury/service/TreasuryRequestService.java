package com.application.e_wallet.treasury.service;

import com.application.e_wallet.agent.entity.AgentEntity;
import com.application.e_wallet.agent.repository.AgentRepository;
import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.treasury.dto.TreasuryRequestCreateRequest;
import com.application.e_wallet.treasury.dto.TreasuryRequestDecisionRequest;
import com.application.e_wallet.treasury.dto.TreasuryRequestResponse;
import com.application.e_wallet.treasury.entity.RequestStatus;
import com.application.e_wallet.treasury.entity.RequestType;
import com.application.e_wallet.treasury.entity.TreasuryRequestEntity;
import com.application.e_wallet.treasury.repository.TreasuryRequestRepository;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.user.repository.UserRepository;
import com.application.e_wallet.wallet.entity.WalletEntity;
import com.application.e_wallet.wallet.entity.WalletType;
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
public class TreasuryRequestService {

    private final TreasuryRequestRepository treasuryRequestRepository;
    private final AgentRepository agentRepository;
    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final TreasuryService treasuryService;

    //=================== Agent creates the treasury request ==================//
    @Transactional
    public TreasuryRequestResponse createRequest(UUID requestingUserId, TreasuryRequestCreateRequest request) {

        AgentEntity agent = agentRepository.findByUserId(requestingUserId)
                .orElseThrow(() -> new BadRequestException("No agent profile for this account"));

        if (request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException("Treasury request amount must be greater than zero");
        }

        if (request.reason() == null || request.reason().isBlank()) {
            throw new BadRequestException("Request reason is required");
        }

        TreasuryRequestEntity treasuryRequest = TreasuryRequestEntity.builder()
                .agent(agent)
                .amount(request.amount())
                .reason(request.reason())
                .requestType(request.requestType())
                .status(RequestStatus.PENDING)
                .build();

        TreasuryRequestEntity savedRequest = treasuryRequestRepository.save(treasuryRequest);

        return toResponse(savedRequest);
    }

    //======================== LIST TREASURY REQUESTS =========================== //
    @Transactional(readOnly = true)
    public List<TreasuryRequestResponse> listRequests(UUID callerUserId, boolean canManage) {

        List<TreasuryRequestEntity> requests;

        if (canManage) {
            requests = treasuryRequestRepository
                    .findAllByOrderByCreatedAtDesc();
        } else {

            AgentEntity agent = agentRepository.findByUserId(callerUserId)
                    .orElseThrow(() ->
                            new BadRequestException("No agent profile for this account"));

            requests = treasuryRequestRepository
                    .findAllByAgent_IdOrderByCreatedAtDesc(agent.getId());
        }

        return requests.stream()
                .map(this::toResponse)
                .toList();
    }

    //============================== GET SINGLE TREASURY REQUEST ================= //
    @Transactional(readOnly = true)
    public TreasuryRequestResponse getRequest(UUID requestId, UUID callerUserId, boolean canManage) {

        TreasuryRequestEntity treasuryRequest = findUnlocked(requestId);

        if (!canManage && !treasuryRequest.getAgent().getUser().getId().equals(callerUserId)) {
            throw new BadRequestException("Treasury request not found");
        }

        return toResponse(treasuryRequest);
    }

    //======================== ADMIN APPROVES TREASURY REQUEST ================ //
    @Transactional
    public TreasuryRequestResponse approveRequest(UUID requestId, UUID adminUserId) {

        TreasuryRequestEntity treasuryRequest = findForUpdate(requestId);

        if (treasuryRequest.getStatus() != RequestStatus.PENDING) {
            throw new BadRequestException("Only a pending treasury request can be approved");
        }

        UserEntity admin = resolveAdmin(adminUserId);

        String description = "Treasury request " + treasuryRequest.getRequestRef() + " approved";
        if (treasuryRequest.getRequestType() == RequestType.TOPUP) {
            treasuryService.topup(treasuryRequest.getAgent().getId(), treasuryRequest.getAmount(), description, adminUserId);
        } else {
            treasuryService.withdraw(treasuryRequest.getAgent().getId(), treasuryRequest.getAmount(), description, adminUserId);
        }

        treasuryRequest.setStatus(RequestStatus.APPROVED);
        treasuryRequest.setDecidedBy(admin);
        treasuryRequest.setDecidedAt(OffsetDateTime.now());
        treasuryRequest.setDecisionNote(null);

        TreasuryRequestEntity saved = treasuryRequestRepository.save(treasuryRequest);
        return toResponse(saved);
    }

    //================================== ADMIN REJECTS TREASURY REQUEST ===================== //
    @Transactional
    public TreasuryRequestResponse rejectRequest(UUID requestId, UUID adminUserId, TreasuryRequestDecisionRequest request) {

        TreasuryRequestEntity treasuryRequest = findForUpdate(requestId);

        if (treasuryRequest.getStatus() != RequestStatus.PENDING) {
            throw new BadRequestException(
                    "Only a pending treasury request can be rejected"
            );
        }

        if (request.reason() == null || request.reason().isBlank()) {
            throw new BadRequestException("Rejection reason is required");
        }

        UserEntity admin = resolveAdmin(adminUserId);

        treasuryRequest.setStatus(RequestStatus.REJECTED);
        treasuryRequest.setDecidedBy(admin);
        treasuryRequest.setDecisionNote(request.reason().trim());
        treasuryRequest.setDecidedAt(OffsetDateTime.now());

        return toResponse(treasuryRequestRepository.save(treasuryRequest));
    }

    // Unlocked lookup for read paths (getRequest). Must never be used by approve/reject -
    // those need the locked query below to serialize concurrent decisions on the same row.
    private TreasuryRequestEntity findUnlocked(UUID requestId) {
        return treasuryRequestRepository.findById(requestId)
                .orElseThrow(() -> new BadRequestException("Treasury request not found"));
    }

    // Locked lookup for approve/reject only. A read-only transaction (getRequest/listRequests)
    // must never route through this - Postgres rejects SELECT ... FOR UPDATE inside a read-only
    // transaction, and even where it wouldn't throw, it would needlessly serialize a view
    // behind any in-flight approve/reject on the same row.
    private TreasuryRequestEntity findForUpdate(UUID requestId) {
        return treasuryRequestRepository.findByRequestId(requestId)
                .orElseThrow(() -> new BadRequestException("Treasury request not found"));
    }

    //=================== RESOLVE ADMIN ======================== //
    private UserEntity resolveAdmin(UUID adminUserId) {
        return userRepository.findById(adminUserId)
                .orElseThrow(() ->
                        new IllegalStateException("Authenticated user not found"));
    }

    private TreasuryRequestResponse toResponse(TreasuryRequestEntity treasuryRequest) {
        AgentEntity agent = treasuryRequest.getAgent();
        UserEntity user = agent.getUser();
        UserEntity decidedBy = treasuryRequest.getDecidedBy();

        WalletEntity wallet = walletRepository.findByUserIdAndWalletType(user.getId(), WalletType.AGENT_FLOAT)
                .orElseThrow(() -> new IllegalStateException("Agent float wallet not found"));

        return new TreasuryRequestResponse(
                treasuryRequest.getId(),
                treasuryRequest.getRequestRef(),
                treasuryRequest.getRequestType().name(),
                agent.getId(),
                agent.getAgentNumber(),
                fullName(user),
                agent.getRegion().getName(),
                agent.getDistrict().getName(),
                user.getPhone(),
                wallet.getWalletNumber(),
                wallet.getAccountNumber(),
                treasuryRequest.getAmount(),
                treasuryRequest.getReason(),
                treasuryRequest.getStatus().name(),
                decidedBy != null ? fullName(decidedBy) : null,
                treasuryRequest.getDecisionNote(),
                treasuryRequest.getDecidedAt(),
                treasuryRequest.getCreatedAt()
        );
    }

    private static String fullName(UserEntity user) {
        return Stream.of(user.getFirstName(), user.getMiddleName(), user.getLastName())
                .filter(part -> part != null && !part.isBlank())
                .collect(Collectors.joining(" "));
    }
}
