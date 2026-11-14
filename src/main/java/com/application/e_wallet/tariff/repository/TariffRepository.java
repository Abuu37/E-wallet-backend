package com.application.e_wallet.tariff.repository;

import com.application.e_wallet.tariff.entity.TariffEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TariffRepository extends JpaRepository<TariffEntity, UUID> {

    List<TariffEntity> findByTransactionTypeAndActiveTrue(String transactionType);
}
