package com.application.e_wallet.treasury.service;

import com.application.e_wallet.agent.entity.AgentEntity;
import com.application.e_wallet.agent.repository.AgentRepository;
import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.treasury.dto.RequestStatusSummary;
import com.application.e_wallet.treasury.dto.TreasuryRequestCreateRequest;
import com.application.e_wallet.treasury.dto.TreasuryRequestDecisionRequest;
import com.application.e_wallet.treasury.dto.TreasuryRequestListResponse;
import com.application.e_wallet.treasury.dto.TreasuryRequestResponse;
import com.application.e_wallet.treasury.dto.TreasuryRequestsSummary;
import com.application.e_wallet.treasury.entity.RequestStatus;
import com.application.e_wallet.treasury.entity.RequestType;
import com.application.e_wallet.treasury.entity.TreasuryRequestEntity;
import com.application.e_wallet.staff.entity.StaffEntity;
import com.application.e_wallet.staff.repository.StaffRepository;
import com.application.e_wallet.treasury.repository.TreasuryRequestRepository;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.user.repository.UserRepository;
import com.application.e_wallet.wallet.entity.WalletEntity;
import com.application.e_wallet.wallet.entity.WalletType;
import com.application.e_wallet.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
    private final StaffRepository staffRepository;
    private final WalletRepository walletRepository;
    private final TreasuryService treasuryService;
    private final com.application.e_wallet.audit.service.AuditService auditService;

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

        if (treasuryRequestRepository.existsByExternalReference(request.externalReference())) {
            throw new BadRequestException("external reference already exists" + request.externalReference());
        }

        TreasuryRequestEntity treasuryRequest = TreasuryRequestEntity.builder()
                .agent(agent)
                .amount(request.amount())
                .reason(request.reason())
                .requestType(request.requestType())
                .externalReference(request.externalReference())
                .status(RequestStatus.PENDING)
                .build();

        TreasuryRequestEntity savedRequest = treasuryRequestRepository.save(treasuryRequest);

        return toResponse(savedRequest);
    }

    //======================== LIST TREASURY REQUESTS =========================== //
    @Transactional(readOnly = true)
    public TreasuryRequestListResponse listRequests(UUID callerUserId, boolean canManage, RequestStatus status, Pageable pageable) {

        List<TreasuryRequestEntity> requests;

        if (canManage) {
            requests = (status != null)
                    ? treasuryRequestRepository.findAllByStatusOrderByCreatedAtDesc(status, pageable)
                    : treasuryRequestRepository.findAllByOrderByCreatedAtDesc(pageable);
        } else {
            AgentEntity agent = agentRepository.findByUserId(callerUserId)
                    .orElseThrow(() -> new BadRequestException("No agent profile for this account"));

            requests = (status != null)
                    ? treasuryRequestRepository.findAllByAgent_IdAndStatusOrderByCreatedAtDesc(agent.getId(), status, pageable)
                    : treasuryRequestRepository.findAllByAgent_IdOrderByCreatedAtDesc(agent.getId(), pageable);
        }

        List<TreasuryRequestResponse> responses = requests.stream()
                .map(this::toResponse)
                .toList();

        return new TreasuryRequestListResponse(
                summarize(requests),
                responses
        );
    }

    //======== return the summary based on status on list (count , total amount) ======
    private TreasuryRequestsSummary summarize(List<TreasuryRequestEntity> requests) {
        RequestStatusSummary pending = summarizeByStatus(requests, RequestStatus.PENDING);
        RequestStatusSummary approved = summarizeByStatus(requests, RequestStatus.APPROVED);
        RequestStatusSummary rejected = summarizeByStatus(requests, RequestStatus.REJECTED);

        return new TreasuryRequestsSummary(
                requests.size(),
                pending,
                approved,
                rejected
        );
    }

    private static RequestStatusSummary summarizeByStatus(List<TreasuryRequestEntity> requests, RequestStatus status) {
        long count = 0;
        BigDecimal balance = BigDecimal.ZERO;
        for (TreasuryRequestEntity request : requests) {
            if (request.getStatus() == status) {
                count++;
                balance = balance.add(request.getAmount());
            }
        }
        return new RequestStatusSummary(count, balance.setScale(2, RoundingMode.HALF_UP));
    }

    //============================== GET SINGLE TREASURY REQUEST ================= //
    @Transactional(readOnly = true)
    public TreasuryRequestResponse getRequest(UUID requestId, UUID callerUserId, boolean canManage) {

        TreasuryRequestEntity treasuryRequest = findUnlocked(requestId);

        if (!canManage && (treasuryRequest.getAgent() == null || !treasuryRequest.getAgent().getUser().getId().equals(callerUserId))) {
            throw new BadRequestException("Treasury request not found");
        }

        return toResponse(treasuryRequest);
    }

    /** Key/value pairs for an audit entry; null values are skipped so auditing can never break the action. */
    private static java.util.Map<String, Object> details(Object... keyValues) {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
            }
        }
        return map;
    }

    /** Mint and commission top-up requests existed before float was issued straight from escrow. Old rows still read; they cannot be decided. */
    private static void rejectLegacyType(TreasuryRequestEntity request) {
        if (request.getRequestType() == RequestType.MINT || request.getRequestType() == RequestType.COMMISSION_TOPUP) {
            throw new BadRequestException("This request type is no longer used.");
        }
    }

    //======================== ADMIN APPROVES TREASURY REQUEST ================ //
    @Transactional
    public TreasuryRequestResponse approveRequest(UUID requestId, UUID adminUserId) {

        TreasuryRequestEntity treasuryRequest = findForUpdate(requestId);
        if (treasuryRequest.getStatus() != RequestStatus.PENDING) {
            throw new BadRequestException("Only a pending treasury request can be approved");
        }

        rejectLegacyType(treasuryRequest);
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
        auditService.record(adminUserId, "TREASURY_REQUEST_APPROVE", "TREASURY_REQUEST", saved.getId(),
                java.util.Map.of("status", "PENDING"), details("status", "APPROVED", "type", saved.getRequestType().name(), "amount", saved.getAmount().toPlainString(), "ref", saved.getRequestRef()),
                null, null);
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
        rejectLegacyType(treasuryRequest);

        UserEntity admin = resolveAdmin(adminUserId);

        treasuryRequest.setStatus(RequestStatus.REJECTED);
        treasuryRequest.setDecidedBy(admin);
        treasuryRequest.setDecisionNote(request.reason().trim());
        treasuryRequest.setDecidedAt(OffsetDateTime.now());

        TreasuryRequestEntity rejected = treasuryRequestRepository.save(treasuryRequest);
        auditService.record(adminUserId, "TREASURY_REQUEST_REJECT", "TREASURY_REQUEST", rejected.getId(),
                java.util.Map.of("status", "PENDING"), details("status", "REJECTED", "type", rejected.getRequestType().name(), "amount", rejected.getAmount().toPlainString(), "ref", rejected.getRequestRef()),
                request.reason(), null);
        return toResponse(rejected);
    }

    // Unlocked lookup for read paths (getRequest). Must never be used by approve/reject -
    // those need the locked query below to serialize concurrent decisions on the same row.
    private TreasuryRequestEntity findUnlocked(UUID requestId) {
        return treasuryRequestRepository.findById(requestId)
                .orElseThrow(() -> new BadRequestException("Treasury request not found"));
    }

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
        UserEntity user = agent != null ? agent.getUser() : null;
        UserEntity decidedBy = treasuryRequest.getDecidedBy();

        WalletEntity wallet = user == null ? null : walletRepository.findByUserIdAndWalletType(user.getId(), WalletType.AGENT_FLOAT)
                .orElseThrow(() -> new IllegalStateException("Agent float wallet not found"));
        UserEntity requestedBy = treasuryRequest.getRequestedBy();

        return new TreasuryRequestResponse(
                treasuryRequest.getId(),
                treasuryRequest.getRequestRef(),
                treasuryRequest.getRequestType().name(),
                agent != null ? agent.getId() : null,
                agent != null ? agent.getAgentNumber() : null,
                agent != null ? fullName(agent.getFirstName(), agent.getMiddleName(), agent.getLastName()) : null,
                agent != null ? agent.getRegion().getName() : null,
                agent != null ? agent.getDistrict().getName() : null,
                user != null ? user.getPhone() : null,
                wallet != null ? wallet.getAccountNumber() : null,
                treasuryRequest.getAmount(),
                treasuryRequest.getReason(),
                treasuryRequest.getStatus().name(),
                decidedBy != null ? decidedByName(decidedBy) : null,
                treasuryRequest.getDecisionNote(),
                treasuryRequest.getDecidedAt(),
                treasuryRequest.getCreatedAt(),
                treasuryRequest.getExternalReference(),
                requestedBy != null ? requestedBy.getId() : null,
                requestedBy != null ? decidedByName(requestedBy) : null
        );
    }

    // The deciding admin's name now lives on their StaffEntity profile, not UserEntity -
    // every admin/staff user is expected to have one (seeded at bootstrap, or onboarded
    // through StaffService). Falls back to an empty name rather than failing the request
    // if that invariant is ever violated.
    private String decidedByName(UserEntity decidedBy) {
        return staffRepository.findByUserId(decidedBy.getId())
                .map(staff -> fullName(staff.getFirstName(), staff.getMiddleName(), staff.getLastName()))
                .orElse("");
    }

    private static String fullName(String firstName, String middleName, String lastName) {
        return Stream.of(firstName, middleName, lastName)
                .filter(part -> part != null && !part.isBlank())
                .collect(Collectors.joining(" "));
    }
}
