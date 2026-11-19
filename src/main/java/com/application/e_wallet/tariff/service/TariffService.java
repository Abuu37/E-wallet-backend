package com.application.e_wallet.tariff.service;

import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.tariff.entity.TariffEntity;
import com.application.e_wallet.tariff.repository.TariffRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class TariffService {

    private final TariffRepository tariffRepository;

    @Transactional(readOnly = true)
    public TariffEntity findApplicableTariff(String transactionType, BigDecimal amount) {
        return tariffRepository
                .findByTransactionTypeAndActiveTrueAndMinAmountLessThanEqualAndMaxAmountGreaterThanEqual(transactionType, amount, amount)
                .orElseThrow(() -> new BadRequestException(
                        "No applicable tariff configured for this amount"
                ));
    }
}
