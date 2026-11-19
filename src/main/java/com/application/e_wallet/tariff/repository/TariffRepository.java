package com.application.e_wallet.tariff.repository;

import com.application.e_wallet.tariff.entity.TariffEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TariffRepository extends JpaRepository<TariffEntity, UUID> {

    List<TariffEntity> findByTransactionTypeAndActiveTrue(String transactionType);

    Optional<TariffEntity> findByTransactionTypeAndActiveTrueAndMinAmountLessThanEqualAndMaxAmountGreaterThanEqual(
            String transactionType, BigDecimal minAmount, BigDecimal maxAmount);
}
