package com.application.e_wallet.treasury.service;

import com.application.e_wallet.agent.entity.AgentEntity;
import com.application.e_wallet.agent.entity.AgentStatus;
import com.application.e_wallet.agent.repository.AgentRepository;
import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.common.storage.SlipStorage;
import com.application.e_wallet.notification.NotificationFormat;
import com.application.e_wallet.notification.NotificationKey;
import com.application.e_wallet.notification.NotificationRecipient;
import com.application.e_wallet.notification.Notifier;
import com.application.e_wallet.treasury.dto.EscrowDetailsResponse;
import com.application.e_wallet.treasury.dto.FloatRequestResponse;
import com.application.e_wallet.treasury.entity.RequestStatus;
import com.application.e_wallet.treasury.entity.RequestType;
import com.application.e_wallet.treasury.entity.TreasuryRequestEntity;
import com.application.e_wallet.treasury.repository.TreasuryRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Float request, Path B: the agent paid money into the escrow bank account themselves and now asks for float.
 * The request carries the deposit reference and a photo of the slip; one staff member checks the escrow statement
 * and approves or rejects it (the approval itself is in {@link TreasuryRequestService}).
 */
@Service
@RequiredArgsConstructor
public class FloatRequestService {

    private final AgentRepository agentRepository;
    private final TreasuryRequestRepository requests;
    private final SlipStorage slips;
    private final Notifier notifier;

    @Value("${escrow.bank-name:Demo Trust Bank}")
    private String escrowBank;
    @Value("${escrow.account-name:WalletPesa Escrow Account}")
    private String escrowAccountName;
    @Value("${escrow.account-number:0150 2233 4455}")
    private String escrowAccountNumber;

    @Transactional(readOnly = true)
    public EscrowDetailsResponse escrowDetails(UUID userId) {
        AgentEntity agent = agent(userId);
        return new EscrowDetailsResponse(escrowBank, escrowAccountName, escrowAccountNumber, agent.getAgentNumber());
    }

    @Transactional
    public FloatRequestResponse create(UUID userId, BigDecimal amount, String depositReference, byte[] slip) {
        AgentEntity agent = agent(userId);
        if (agent.getAgentStatus() != AgentStatus.ACTIVE) {
            throw new BadRequestException("Your agent account is not active.");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new BadRequestException("Amount must be greater than zero.");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new BadRequestException("Amount must have at most 2 decimal places.");
        }
        String reference = depositReference == null ? "" : depositReference.trim();
        if (reference.length() < 3 || reference.length() > 60) {
            throw new BadRequestException("Enter the slip or deposit number from your bank slip (3 to 60 characters).");
        }
        if (requests.countByAgent_IdAndStatus(agent.getId(), RequestStatus.PENDING) > 0) {
            throw new BadRequestException("You already have a float request waiting. Wait for it to be checked first.");
        }
        if (requests.existsByExternalReference(reference)) {
            throw new BadRequestException("This slip number was already used in another request.");
        }

        SlipStorage.Stored stored = slips.save(slip);
        TreasuryRequestEntity saved = requests.save(TreasuryRequestEntity.builder()
                .agent(agent).amount(amount).requestType(RequestType.TOPUP).status(RequestStatus.PENDING)
                .reason("Bank deposit to escrow, slip " + reference).externalReference(reference)
                .requestedBy(agent.getUser()).slipFileKey(stored.key()).slipContentType(stored.contentType()).build());

        notifier.notify(NotificationKey.A4, NotificationRecipient.of(agent.getUser()),
                Map.of("amount", NotificationFormat.tzs(amount), "ref", saved.getRequestRef()));
        return new FloatRequestResponse(saved.getId(), saved.getRequestRef(), saved.getStatus().name(), saved.getAmount(), reference,
                saved.getCreatedAt() == null ? OffsetDateTime.now() : saved.getCreatedAt());
    }

    /** The slip photo: staff who manage float, or the agent who sent it. Anyone else gets "not found". */
    @Transactional(readOnly = true)
    public SlipStorage.Loaded slip(UUID requestId, UUID callerUserId, boolean canManage) {
        TreasuryRequestEntity request = requests.findById(requestId).orElseThrow(() -> new BadRequestException("Slip not found"));
        boolean owner = request.getAgent() != null && request.getAgent().getUser().getId().equals(callerUserId);
        if ((!canManage && !owner) || request.getSlipFileKey() == null) {
            throw new BadRequestException("Slip not found");
        }
        return slips.load(request.getSlipFileKey(), request.getSlipContentType());
    }

    private AgentEntity agent(UUID userId) {
        return agentRepository.findByUserId(userId).orElseThrow(() -> new BadRequestException("No agent profile for this account"));
    }
}
