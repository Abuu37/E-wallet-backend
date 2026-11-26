package com.application.e_wallet.tariff.service;

import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.tariff.dto.TariffBandItem;
import com.application.e_wallet.tariff.entity.TariffEntity;
import com.application.e_wallet.tariff.repository.TariffRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

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

    private static final java.util.Set<String> QUOTABLE = java.util.Set.of("TRANSFER", "CASH_OUT", "BANK_WITHDRAWAL");

    /** The fee and total for an action the customer is about to confirm. */
    @Transactional(readOnly = true)
    public com.application.e_wallet.tariff.dto.QuoteResponse quote(String type, BigDecimal amount) {
        String normalized = type == null ? "" : type.trim().toUpperCase();
        if (!QUOTABLE.contains(normalized)) {
            throw new BadRequestException("Unknown type. Use one of " + QUOTABLE.stream().sorted().toList());
        }
        if (amount == null || amount.signum() <= 0) {
            throw new BadRequestException("Amount must be greater than zero");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new BadRequestException("Amount must have at most 2 decimal places");
        }
        BigDecimal fee = findApplicableTariff(normalized, amount).getFee();
        return new com.application.e_wallet.tariff.dto.QuoteResponse(normalized, amount, fee, amount.add(fee));
    }

    /** Every band (live and inactive), grouped by transaction type and ordered by amount range. */
    @Transactional(readOnly = true)
    public List<TariffBandItem> listBands() {
        return tariffRepository.findAll().stream()
                .sorted(Comparator.comparing(TariffEntity::getTransactionType).thenComparing(TariffEntity::getMinAmount))
                .map(t -> new TariffBandItem(t.getId(), t.getTransactionType(), t.getMinAmount(), t.getMaxAmount(),
                        t.getFee(), t.getAgentCommission(), Boolean.TRUE.equals(t.getActive())))
                .toList();
    }
}
