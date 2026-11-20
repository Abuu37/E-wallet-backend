package com.application.e_wallet.transaction.repository;

import com.application.e_wallet.transaction.entity.TransactionEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<TransactionEntity, UUID> {

    boolean existsByReferenceNo(String referenceNo);

    List<TransactionEntity> findByStatusAndCreatedAtBetween(String status, OffsetDateTime start, OffsetDateTime end);

    // "Today" for one agent's own wallets - a transaction counts if either side (sender or
    // receiver) is one of the agent's wallets (float or commission).
    @Query("""
            SELECT t FROM TransactionEntity t
            WHERE t.status = :status
            AND t.createdAt BETWEEN :start AND :end
            AND (t.senderWallet.id IN :walletIds OR t.receiverWallet.id IN :walletIds)
            """)
    List<TransactionEntity> findByStatusAndCreatedAtBetweenAndWalletIdIn(
            @Param("status") String status,
            @Param("start") OffsetDateTime start,
            @Param("end") OffsetDateTime end,
            @Param("walletIds") List<UUID> walletIds);

    // Unscoped by date/status - most recent activity on either of an agent's wallets.
    @Query("""
            SELECT t FROM TransactionEntity t
            WHERE t.senderWallet.id IN :walletIds OR t.receiverWallet.id IN :walletIds
            ORDER BY t.createdAt DESC
            """)
    List<TransactionEntity> findRecentByWalletIdIn(@Param("walletIds") List<UUID> walletIds, Pageable pageable);

    @Query("""
            SELECT COUNT(t) FROM TransactionEntity t
            WHERE t.senderWallet.id = :walletId OR t.receiverWallet.id = :walletId
            """)
    long countByWalletId(@Param("walletId") UUID walletId);

    // Money received: the full amount, no fee deducted on the receiving side today.
    @Query("""
            SELECT COALESCE(SUM(t.amount), 0) FROM TransactionEntity t
            WHERE t.receiverWallet.id = :walletId
            """)
    BigDecimal sumAmountInByWalletId(@Param("walletId") UUID walletId);

    // Money paid out: amount + fee, since the fee is debited from the same wallet
    // alongside the principal amount (see TransactionService.cashOut).
    @Query("""
            SELECT COALESCE(SUM(t.amount + t.fee), 0) FROM TransactionEntity t
            WHERE t.senderWallet.id = :walletId
            """)
    BigDecimal sumAmountOutByWalletId(@Param("walletId") UUID walletId);

}
