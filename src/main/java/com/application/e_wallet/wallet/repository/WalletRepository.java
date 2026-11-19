package com.application.e_wallet.wallet.repository;

import com.application.e_wallet.wallet.entity.WalletEntity;
import com.application.e_wallet.wallet.entity.WalletType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public interface WalletRepository extends JpaRepository<WalletEntity, UUID> {

    boolean existsByUserIdAndWalletType(UUID userId, WalletType walletType);

    Optional<WalletEntity> findByUserIdAndWalletType(UUID userId, WalletType walletType);

    Optional<WalletEntity> findByWalletType(WalletType walletType);

    // Method names end in "ForUpdate" to signal pessimistic locking to callers; the suffix is
    // not derivable by Spring Data's query parser, so the query is spelled out explicitly here
    // rather than relying on name-derivation (which would fail at startup on "ForUpdate").
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM WalletEntity w WHERE w.user.id = :userId AND w.walletType = :walletType")
    Optional<WalletEntity> findByUserIdAndWalletTypeForUpdate(@Param("userId") UUID userId, @Param("walletType") WalletType walletType);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM WalletEntity w WHERE w.walletType = :walletType")
    Optional<WalletEntity> findByWalletTypeForUpdate(@Param("walletType") WalletType walletType);

    @Query("SELECT COALESCE(SUM(w.balance), 0) FROM WalletEntity w WHERE w.walletType = :walletType")
    BigDecimal sumBalanceByWalletType(@Param("walletType") WalletType walletType);

    Optional<WalletEntity> findByAccountNumber(String accountNumber);

    boolean existsByAccountNumber(String accountNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM WalletEntity w WHERE w.accountNumber = :accountNumber")
    Optional<WalletEntity> findByAccountNumberForUpdate(@Param("accountNumber") String accountNumber);
}
