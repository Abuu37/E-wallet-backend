package com.application.e_wallet.treasury.operations;

import com.application.e_wallet.audit.service.AuditService;
import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.ledger.dto.JournalLineRequest;
import com.application.e_wallet.ledger.entity.EntryType;
import com.application.e_wallet.ledger.entity.LedgerTransactionType;
import com.application.e_wallet.ledger.service.LedgerService;
import com.application.e_wallet.staff.repository.StaffRepository;
import com.application.e_wallet.transaction.entity.TransactionEntity;
import com.application.e_wallet.transaction.entity.TransactionType;
import com.application.e_wallet.transaction.repository.TransactionRepository;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.user.repository.UserRepository;
import com.application.e_wallet.wallet.entity.WalletEntity;
import com.application.e_wallet.wallet.entity.WalletType;
import com.application.e_wallet.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Treasury moves that take two different staff members: sweep revenue to the company bank, reverse a wrong transfer,
 * and correct escrow against revenue. One person asks; a different person approves, and only then does anything move.
 * Every approval is one transaction with a balanced journal, and a reversal is a new opposite entry: nothing is edited
 * or deleted. Escrow always equals the sum of the wallets afterwards.
 */
@Service
@RequiredArgsConstructor
public class TreasuryOperationService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final TreasuryOperationRepository operations;
    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerService ledgerService;
    private final UserRepository userRepository;
    private final StaffRepository staffRepository;
    private final AuditService auditService;

    @Transactional
    public OperationResponse request(UUID staffUserId, OperationRequest request) {
        BigDecimal amount = request.amount();
        String target = request.targetReference() == null ? null : request.targetReference().trim();

        if (request.type() == OperationType.REVERSAL) {
            if (target == null || target.isEmpty()) {
                throw new BadRequestException("Enter the reference of the transfer to reverse.");
            }
            TransactionEntity original = reversible(target);
            if (operations.existsByTargetReferenceAndOpTypeAndStatusIn(target, OperationType.REVERSAL,
                    EnumSet.of(OperationStatus.PENDING, OperationStatus.APPROVED))) {
                throw new BadRequestException("This transfer already has a reversal waiting or done.");
            }
            amount = original.getAmount();
        } else {
            if (amount == null || amount.signum() <= 0) {
                throw new BadRequestException("Amount must be greater than zero.");
            }
            if (amount.stripTrailingZeros().scale() > 2) {
                throw new BadRequestException("Amount must have at most 2 decimal places.");
            }
            target = null;
        }

        TreasuryOperationEntity saved = operations.save(TreasuryOperationEntity.builder()
                .opType(request.type()).amount(amount).reason(request.reason().trim()).targetReference(target).requestedBy(staffUserId).build());
        auditService.record(staffUserId, "TREASURY_OPERATION_REQUEST", "TREASURY_OPERATION", saved.getId(), null,
                Map.of("type", saved.getOpType().name(), "amount", saved.getAmount().toPlainString(), "ref", saved.getOpRef()), saved.getReason(), null);
        return toResponse(saved, staffUserId);
    }

    @Transactional(readOnly = true)
    public List<OperationResponse> list(UUID callerUserId, OperationStatus status, int size) {
        PageRequest page = PageRequest.of(0, Math.min(Math.max(size, 1), 100));
        List<TreasuryOperationEntity> rows = status == null ? operations.findAllByOrderByCreatedAtDesc(page) : operations.findAllByStatusOrderByCreatedAtDesc(status, page);
        return rows.stream().map(o -> toResponse(o, callerUserId)).toList();
    }

    @Transactional
    public OperationResponse approve(UUID operationId, UUID approverUserId) {
        TreasuryOperationEntity op = operations.lockById(operationId).orElseThrow(() -> new BadRequestException("Operation not found"));
        if (op.getStatus() != OperationStatus.PENDING) {
            throw new BadRequestException("Only a pending operation can be approved.");
        }
        if (op.getRequestedBy().equals(approverUserId)) {
            throw new BadRequestException("A second staff member must approve this. You asked for it yourself.");
        }
        UserEntity approver = userRepository.findById(approverUserId).orElseThrow(() -> new IllegalStateException("Authenticated user not found"));

        String result = switch (op.getOpType()) {
            case SWEEP -> moveRevenueAndEscrow(op, approver, false, LedgerTransactionType.TREASURY_SWEEP, "Sweep revenue to the company bank");
            case ADJUSTMENT_CHARGE -> moveRevenueAndEscrow(op, approver, false, LedgerTransactionType.TREASURY_ADJUSTMENT, "Escrow correction: bank charge");
            case ADJUSTMENT_PAY_IN -> moveRevenueAndEscrow(op, approver, true, LedgerTransactionType.TREASURY_ADJUSTMENT, "Escrow correction: company paid in");
            case REVERSAL -> reverseTransfer(op, approver);
        };

        op.setStatus(OperationStatus.APPROVED);
        op.setDecidedBy(approverUserId);
        op.setDecidedAt(OffsetDateTime.now());
        op.setResultReference(result);
        operations.save(op);
        auditService.record(approverUserId, "TREASURY_OPERATION_APPROVE", "TREASURY_OPERATION", op.getId(), Map.of("status", "PENDING"),
                Map.of("status", "APPROVED", "type", op.getOpType().name(), "amount", op.getAmount().toPlainString(), "ref", op.getOpRef()), null, null);
        return toResponse(op, approverUserId);
    }

    @Transactional
    public OperationResponse reject(UUID operationId, UUID staffUserId, String reason) {
        TreasuryOperationEntity op = operations.lockById(operationId).orElseThrow(() -> new BadRequestException("Operation not found"));
        if (op.getStatus() != OperationStatus.PENDING) {
            throw new BadRequestException("Only a pending operation can be rejected.");
        }
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("Say why you are rejecting it.");
        }
        op.setStatus(OperationStatus.REJECTED);
        op.setDecidedBy(op.getRequestedBy().equals(staffUserId) ? null : staffUserId);
        op.setDecisionNote(reason.trim());
        op.setDecidedAt(OffsetDateTime.now());
        operations.save(op);
        auditService.record(staffUserId, "TREASURY_OPERATION_REJECT", "TREASURY_OPERATION", op.getId(), Map.of("status", "PENDING"),
                Map.of("status", "REJECTED", "type", op.getOpType().name(), "ref", op.getOpRef()), reason, null);
        return toResponse(op, staffUserId);
    }

    // ====================================================================== the moves
    /** Revenue and escrow move together, so every wallet shilling stays backed by escrow. */
    private String moveRevenueAndEscrow(TreasuryOperationEntity op, UserEntity approver, boolean up, LedgerTransactionType journalType, String description) {
        UUID revenueId = wallet(WalletType.REVENUE).getId();
        UUID escrowId = wallet(WalletType.ESCROW_BANK).getId();
        Map<UUID, WalletEntity> locked = lock(List.of(revenueId, escrowId));
        WalletEntity revenue = locked.get(revenueId);
        WalletEntity escrow = locked.get(escrowId);
        BigDecimal amount = op.getAmount();

        if (up) {
            revenue.setBalance(revenue.getBalance().add(amount));
            escrow.setBalance(escrow.getBalance().add(amount));
        } else {
            if (revenue.getBalance().compareTo(amount) < 0) {
                throw new BadRequestException("Revenue only holds TZS " + revenue.getBalance().toPlainString() + ", so this cannot be taken out.");
            }
            revenue.setBalance(revenue.getBalance().subtract(amount));
            escrow.setBalance(escrow.getBalance().subtract(amount));
        }
        // Escrow debit = money in. Revenue credit = revenue up. Down is the exact opposite, so both lines still balance.
        List<JournalLineRequest> lines = up
                ? List.of(new JournalLineRequest(escrowId, EntryType.DEBIT, amount), new JournalLineRequest(revenueId, EntryType.CREDIT, amount))
                : List.of(new JournalLineRequest(revenueId, EntryType.DEBIT, amount), new JournalLineRequest(escrowId, EntryType.CREDIT, amount));
        ledgerService.postJournal(journalType, description + " (" + op.getOpRef() + ")", approver, lines);
        walletRepository.save(revenue);
        walletRepository.save(escrow);
        return op.getOpRef();
    }

    /** The transfer is undone by a new opposite entry: the recipient gives the amount back, and the fee comes from revenue. */
    private String reverseTransfer(TreasuryOperationEntity op, UserEntity approver) {
        TransactionEntity original = reversible(op.getTargetReference());
        UUID fromId = original.getReceiverWallet().getId();
        UUID toId = original.getSenderWallet().getId();
        BigDecimal amount = original.getAmount();
        BigDecimal fee = original.getFee();
        UUID revenueId = fee.signum() > 0 ? wallet(WalletType.REVENUE).getId() : null;

        List<UUID> ids = new ArrayList<>(List.of(fromId, toId));
        if (revenueId != null) {
            ids.add(revenueId);
        }
        Map<UUID, WalletEntity> locked = lock(ids);
        WalletEntity from = locked.get(fromId);
        WalletEntity to = locked.get(toId);
        WalletEntity revenue = revenueId == null ? null : locked.get(revenueId);

        if (from.getBalance().compareTo(amount) < 0) {
            throw new BadRequestException("The recipient has already spent this money (balance TZS " + from.getBalance().toPlainString()
                    + "), so it cannot be taken back automatically.");
        }
        if (revenue != null && revenue.getBalance().compareTo(fee) < 0) {
            throw new BadRequestException("Revenue cannot cover returning the fee.");
        }

        from.setBalance(from.getBalance().subtract(amount));
        to.setBalance(to.getBalance().add(amount).add(fee));
        List<JournalLineRequest> lines = new ArrayList<>();
        lines.add(new JournalLineRequest(fromId, EntryType.DEBIT, amount));
        lines.add(new JournalLineRequest(toId, EntryType.CREDIT, amount.add(fee)));
        if (revenue != null) {
            revenue.setBalance(revenue.getBalance().subtract(fee));
            lines.add(new JournalLineRequest(revenueId, EntryType.DEBIT, fee));
        }
        ledgerService.postJournal(LedgerTransactionType.TRANSFER_REVERSAL, "Reversal of " + original.getReferenceNo() + " (" + op.getOpRef() + ")", approver, lines);
        walletRepository.save(from);
        walletRepository.save(to);
        if (revenue != null) {
            walletRepository.save(revenue);
        }

        String reference = "REV-" + OffsetDateTime.now().format(DATE) + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        transactionRepository.save(TransactionEntity.builder()
                .referenceNo(reference).senderWallet(from).receiverWallet(to).amount(amount.add(fee)).fee(BigDecimal.ZERO).commission(BigDecimal.ZERO)
                .transactionType(TransactionType.REVERSAL.name()).status("COMPLETED").externalReference(original.getReferenceNo()).build());
        return reference;
    }

    /** Only a completed wallet-to-wallet transfer can be reversed. */
    private TransactionEntity reversible(String reference) {
        TransactionEntity original = transactionRepository.findByReferenceNo(reference).orElseThrow(() -> new BadRequestException("No transaction has this reference."));
        if (!TransactionType.TRANSFER.name().equals(original.getTransactionType()) || !"COMPLETED".equals(original.getStatus())) {
            throw new BadRequestException("Only a completed wallet-to-wallet transfer can be reversed.");
        }
        return original;
    }

    // ====================================================================== helpers
    private WalletEntity wallet(WalletType type) {
        return walletRepository.findByWalletType(type).orElseThrow(() -> new IllegalStateException(type + " wallet not initialized"));
    }

    private Map<UUID, WalletEntity> lock(List<UUID> ids) {
        Map<UUID, WalletEntity> byId = new HashMap<>();
        for (WalletEntity w : walletRepository.lockAllOrdered(ids)) {
            byId.put(w.getId(), w);
        }
        return byId;
    }

    private OperationResponse toResponse(TreasuryOperationEntity o, UUID callerUserId) {
        return new OperationResponse(o.getId(), o.getOpRef(), o.getOpType().name(), o.getAmount(), o.getReason(), o.getTargetReference(), o.getStatus().name(),
                nameOf(o.getRequestedBy()), o.getDecidedBy() == null ? null : nameOf(o.getDecidedBy()), o.getDecisionNote(), o.getResultReference(),
                o.getCreatedAt(), o.getDecidedAt(), o.getRequestedBy().equals(callerUserId));
    }

    private String nameOf(UUID userId) {
        return staffRepository.findByUserId(userId)
                .map(s -> (s.getFirstName() + " " + s.getLastName()).trim())
                .orElse("Staff");
    }
}
