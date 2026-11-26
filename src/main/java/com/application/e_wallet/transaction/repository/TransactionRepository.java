package com.application.e_wallet.transaction.repository;

import com.application.e_wallet.transaction.entity.TransactionEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<TransactionEntity, UUID> {

    boolean existsByReferenceNo(String referenceNo);

    Optional<TransactionEntity> findByReferenceNo(String referenceNo);

    // "View all transactions" (admin) - JOIN FETCH avoids an N+1 lazy-load per row for the
    // sender/receiver wallet account name/number the list response displays. A separate
    // countQuery is required since Spring Data's auto-derived count can't always strip a
    // JOIN FETCH cleanly.
    @Query(value = """
            SELECT t FROM TransactionEntity t
            JOIN FETCH t.senderWallet
            JOIN FETCH t.receiverWallet
            """,
            countQuery = "SELECT COUNT(t) FROM TransactionEntity t")
    Page<TransactionEntity> findAllWithWallets(Pageable pageable);

    // "View own transactions" (customer/agent) - same JOIN FETCH reasoning as above, scoped to
    // the caller's own wallet id(s) (one for a customer, two - float + commission - for an agent).
    @Query(value = """
            SELECT t FROM TransactionEntity t
            JOIN FETCH t.senderWallet
            JOIN FETCH t.receiverWallet
            WHERE t.senderWallet.id IN :walletIds OR t.receiverWallet.id IN :walletIds
            """,
            countQuery = """
            SELECT COUNT(t) FROM TransactionEntity t
            WHERE t.senderWallet.id IN :walletIds OR t.receiverWallet.id IN :walletIds
            """)
    Page<TransactionEntity> findByWalletIdInWithWallets(@Param("walletIds") List<UUID> walletIds, Pageable pageable);

    Optional<TransactionEntity> findByExternalReference(String externalReference);

    // Method name ends in "ForUpdate" to signal pessimistic locking to callers; the suffix is
    // not derivable by Spring Data's query parser, so the query is spelled out explicitly here
    // rather than relying on name-derivation (which would fail at startup on "ForUpdate"). See
    // WalletRepository.findByAccountNumberForUpdate for the same pattern. This closes a race
    // where two concurrent deliveries of the same webhook event both read the transaction while
    // it is still PENDING and both pass the idempotency check in PaymentService.handleWebhookEvent.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM TransactionEntity t WHERE t.externalReference = :externalReference")
    Optional<TransactionEntity> findByExternalReferenceForUpdate(@Param("externalReference") String externalReference);

    List<TransactionEntity> findByStatusAndCreatedAtBetween(String status, OffsetDateTime start, OffsetDateTime end);

    // Unscoped by status or date - the admin dashboard's recent-activity feed, across every
    // transaction type (CASH_IN, CASH_OUT, ...), so admins see pending/failed
    // attempts too, not just completed ones. JOIN FETCH avoids an N+1 lazy-load per row for
    // the sender/receiver wallet account name and number the dashboard also displays.
    @Query("""
            SELECT t FROM TransactionEntity t
            JOIN FETCH t.senderWallet
            JOIN FETCH t.receiverWallet
            ORDER BY t.createdAt DESC
            """)
    List<TransactionEntity> findRecentWithWallets(Pageable pageable);

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

    // Same as countByWalletId, but for an agent's float+commission wallets together -
    // all-time lifetime count, unscoped by date/status.
    @Query("""
            SELECT COUNT(t) FROM TransactionEntity t
            WHERE t.senderWallet.id IN :walletIds OR t.receiverWallet.id IN :walletIds
            """)
    long countByWalletIdIn(@Param("walletIds") List<UUID> walletIds);

    // Money received: the full amount, no fee deducted on the receiving side today.
    // Restricted to COMPLETED rows only - since top-ups can be PENDING/FAILED
    // (every prior transaction type completed synchronously), an abandoned/expired checkout
    // session must not be counted as real money in the customer's summary.
    @Query("""
            SELECT COALESCE(SUM(t.amount), 0) FROM TransactionEntity t
            WHERE t.receiverWallet.id = :walletId AND t.status = 'COMPLETED'
            """)
    BigDecimal sumAmountInByWalletId(@Param("walletId") UUID walletId);

    // Money paid out: amount + fee, since the fee is debited from the same wallet
    // alongside the principal amount (see TransactionService.cashOut).
    // Restricted to COMPLETED rows only - see sumAmountInByWalletId above.
    @Query("""
            SELECT COALESCE(SUM(t.amount + t.fee), 0) FROM TransactionEntity t
            WHERE t.senderWallet.id = :walletId AND t.status = 'COMPLETED'
            """)
    BigDecimal sumAmountOutByWalletId(@Param("walletId") UUID walletId);

    // Date-scoped counterparts of the three queries above, for the customer dashboard's
    // "this month" cash flow card. Same COMPLETED-only restriction and the same fee-inclusive
    // "money out" definition.
    @Query("""
            SELECT COALESCE(SUM(t.amount), 0) FROM TransactionEntity t
            WHERE t.receiverWallet.id = :walletId AND t.status = 'COMPLETED'
            AND t.createdAt BETWEEN :start AND :end
            """)
    BigDecimal sumAmountInByWalletIdAndCreatedAtBetween(
            @Param("walletId") UUID walletId, @Param("start") OffsetDateTime start, @Param("end") OffsetDateTime end);

    @Query("""
            SELECT COALESCE(SUM(t.amount + t.fee), 0) FROM TransactionEntity t
            WHERE t.senderWallet.id = :walletId AND t.status = 'COMPLETED'
            AND t.createdAt BETWEEN :start AND :end
            """)
    BigDecimal sumAmountOutByWalletIdAndCreatedAtBetween(
            @Param("walletId") UUID walletId, @Param("start") OffsetDateTime start, @Param("end") OffsetDateTime end);

    @Query("""
            SELECT COUNT(t) FROM TransactionEntity t
            WHERE (t.senderWallet.id = :walletId OR t.receiverWallet.id = :walletId)
            AND t.status = 'COMPLETED' AND t.createdAt BETWEEN :start AND :end
            """)
    long countByWalletIdAndCreatedAtBetween(
            @Param("walletId") UUID walletId, @Param("start") OffsetDateTime start, @Param("end") OffsetDateTime end);

    // In-progress incoming activity (e.g. a bank top-up the customer started but whose
    // webhook hasn't landed yet) - the customer is always the receiver on these, never the sender.
    @Query("""
            SELECT COUNT(t) FROM TransactionEntity t
            WHERE t.receiverWallet.id = :walletId AND t.status = 'PENDING'
            """)
    long countPendingByReceiverWalletId(@Param("walletId") UUID walletId);

    @Query("""
            SELECT COALESCE(SUM(t.amount), 0) FROM TransactionEntity t
            WHERE t.receiverWallet.id = :walletId AND t.status = 'PENDING'
            """)
    BigDecimal sumPendingAmountByReceiverWalletId(@Param("walletId") UUID walletId);


    /** Completed money that left this wallet in a time window, for the daily limit. */
    @Query("""
            SELECT COALESCE(SUM(t.amount), 0) FROM TransactionEntity t
            WHERE t.senderWallet.id = :walletId
              AND t.status = 'COMPLETED'
              AND t.transactionType IN :types
              AND t.createdAt >= :start AND t.createdAt < :end
            """)
    BigDecimal sumSentBetween(@Param("walletId") UUID walletId, @Param("types") java.util.Collection<String> types,
                              @Param("start") OffsetDateTime start, @Param("end") OffsetDateTime end);

    /** A customer's or agent's own transactions, with the filters of the Transactions screen. Empty strings mean "no filter". */
    /** Completed money received by a wallet through one transaction type in a window, e.g. float topped up by bank today. */
    @Query("""
            SELECT COALESCE(SUM(t.amount), 0) FROM TransactionEntity t
            WHERE t.receiverWallet.id = :walletId
              AND t.status = 'COMPLETED'
              AND t.transactionType = :type
              AND t.createdAt >= :start AND t.createdAt < :end
            """)
    BigDecimal sumReceivedByTypeBetween(@Param("walletId") UUID walletId, @Param("type") String type,
                                        @Param("start") OffsetDateTime start, @Param("end") OffsetDateTime end);

    @Query(value = """
            SELECT t FROM TransactionEntity t
              JOIN FETCH t.senderWallet sw JOIN FETCH sw.user
              JOIN FETCH t.receiverWallet rw JOIN FETCH rw.user
            WHERE (sw.id IN :walletIds OR rw.id IN :walletIds)
              AND (:type = '' OR t.transactionType = :type)
              AND t.createdAt >= :start AND t.createdAt < :end
              AND (:q = '' OR LOWER(t.referenceNo) LIKE CONCAT('%', :q, '%')
                   OR LOWER(sw.accountName) LIKE CONCAT('%', :q, '%')
                   OR LOWER(rw.accountName) LIKE CONCAT('%', :q, '%'))
            ORDER BY t.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(t) FROM TransactionEntity t
            WHERE (t.senderWallet.id IN :walletIds OR t.receiverWallet.id IN :walletIds)
              AND (:type = '' OR t.transactionType = :type)
              AND t.createdAt >= :start AND t.createdAt < :end
              AND (:q = '' OR LOWER(t.referenceNo) LIKE CONCAT('%', :q, '%')
                   OR LOWER(t.senderWallet.accountName) LIKE CONCAT('%', :q, '%')
                   OR LOWER(t.receiverWallet.accountName) LIKE CONCAT('%', :q, '%'))
            """)
    Page<TransactionEntity> findMine(@Param("walletIds") java.util.Collection<UUID> walletIds, @Param("type") String type,
                                     @Param("start") OffsetDateTime start, @Param("end") OffsetDateTime end,
                                     @Param("q") String q, Pageable pageable);

    /** What an agent conducted: transactions where their float wallet is a party, of the given types, in a window, searchable. */
    @Query(value = """
            SELECT t FROM TransactionEntity t
              JOIN FETCH t.senderWallet sw JOIN FETCH t.receiverWallet rw
            WHERE (sw.id = :floatId OR rw.id = :floatId)
              AND t.transactionType IN :types
              AND t.createdAt >= :start AND t.createdAt < :end
              AND (:q = '' OR LOWER(t.referenceNo) LIKE CONCAT('%', :q, '%')
                   OR LOWER(sw.accountName) LIKE CONCAT('%', :q, '%') OR LOWER(rw.accountName) LIKE CONCAT('%', :q, '%')
                   OR sw.accountNumber LIKE CONCAT('%', :q, '%') OR rw.accountNumber LIKE CONCAT('%', :q, '%'))
            ORDER BY t.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(t) FROM TransactionEntity t
            WHERE (t.senderWallet.id = :floatId OR t.receiverWallet.id = :floatId)
              AND t.transactionType IN :types
              AND t.createdAt >= :start AND t.createdAt < :end
              AND (:q = '' OR LOWER(t.referenceNo) LIKE CONCAT('%', :q, '%')
                   OR LOWER(t.senderWallet.accountName) LIKE CONCAT('%', :q, '%') OR LOWER(t.receiverWallet.accountName) LIKE CONCAT('%', :q, '%')
                   OR t.senderWallet.accountNumber LIKE CONCAT('%', :q, '%') OR t.receiverWallet.accountNumber LIKE CONCAT('%', :q, '%'))
            """)
    Page<TransactionEntity> findAgentActivity(@Param("floatId") UUID floatId, @Param("types") java.util.Collection<String> types,
                                              @Param("start") OffsetDateTime start, @Param("end") OffsetDateTime end,
                                              @Param("q") String q, Pageable pageable);

    /** The same window without paging, for the 7-day chart and today's numbers. */
    @Query("""
            SELECT t FROM TransactionEntity t
              JOIN FETCH t.senderWallet sw JOIN FETCH t.receiverWallet rw
            WHERE (sw.id = :floatId OR rw.id = :floatId)
              AND t.transactionType IN :types
              AND t.createdAt >= :start AND t.createdAt < :end
            ORDER BY t.createdAt DESC
            """)
    List<TransactionEntity> findAgentActivityBetween(@Param("floatId") UUID floatId, @Param("types") java.util.Collection<String> types,
                                                     @Param("start") OffsetDateTime start, @Param("end") OffsetDateTime end);

    /** Withdrawals paid at this agent's till since a moment ago, newest first. */
    @Query("""
            SELECT t FROM TransactionEntity t
              JOIN FETCH t.senderWallet sw JOIN FETCH t.receiverWallet rw
            WHERE rw.id = :floatId AND t.transactionType = 'CASH_OUT' AND t.createdAt >= :since
            ORDER BY t.createdAt DESC
            """)
    List<TransactionEntity> findLiveWithdrawals(@Param("floatId") UUID floatId, @Param("since") OffsetDateTime since, Pageable pageable);
}
