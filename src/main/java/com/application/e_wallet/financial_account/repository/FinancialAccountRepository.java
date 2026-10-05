package com.application.e_wallet.financial_account.repository;

import com.application.e_wallet.financial_account.entity.FinancialAccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface FinancialAccountRepository extends JpaRepository<FinancialAccountEntity, UUID> {

    boolean existsByWalletId(UUID walletId);

    Optional<FinancialAccountEntity> findByWalletId(UUID walletId);

    Optional<FinancialAccountEntity> findByAccountNumber(String accountNumber);

}
