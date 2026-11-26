package com.application.e_wallet.transaction.repository;

import com.application.e_wallet.transaction.entity.CommissionOwedEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface CommissionOwedRepository extends JpaRepository<CommissionOwedEntity, UUID> {

    @Query("SELECT o FROM CommissionOwedEntity o JOIN FETCH o.agent WHERE o.paidAt IS NULL ORDER BY o.createdAt ASC")
    List<CommissionOwedEntity> findUnpaidOldestFirst();

    @Query("SELECT COALESCE(SUM(o.amount), 0) FROM CommissionOwedEntity o WHERE o.paidAt IS NULL")
    BigDecimal sumUnpaid();
}
