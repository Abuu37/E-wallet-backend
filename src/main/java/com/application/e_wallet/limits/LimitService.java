package com.application.e_wallet.limits;

import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.kyc.entity.KycStatus;
import com.application.e_wallet.kyc.service.KycLimitsProperties;
import com.application.e_wallet.transaction.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;

/**
 * The one place that decides what a person may send or hold. Transfers, withdrawals and bank top-ups all ask
 * here. The numbers come from configuration (limits.basic.*, limits.verified.*), never from code:
 * Basic until Plaid identity verification passes, then Verified.
 */
@Service
@RequiredArgsConstructor
public class LimitService {

    static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");

    /** Money leaving a wallet counts towards the daily total. Adding money or receiving it does not. */
    static final Set<String> COUNTS_TOWARDS_DAILY = Set.of("TRANSFER", "CASH_OUT", "BANK_WITHDRAWAL");

    private final KycLimitsProperties properties;
    private final TransactionRepository transactionRepository;

    public record Limits(BigDecimal perPayment, BigDecimal daily, BigDecimal maxBalance) { }

    public Limits limitsFor(KycStatus status) {
        KycLimitsProperties.Tier tier = status == KycStatus.VERIFIED ? properties.getVerified() : properties.getBasic();
        return new Limits(tier.getPerPayment(), tier.getDaily(), tier.getMaxBalance());
    }

    /** How much this wallet has already sent today (Dar es Salaam time). */
    public BigDecimal usedToday(UUID walletId) {
        LocalDate today = LocalDate.now(ZONE);
        OffsetDateTime start = today.atStartOfDay(ZONE).toOffsetDateTime();
        OffsetDateTime end = today.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();
        BigDecimal used = transactionRepository.sumSentBetween(walletId, COUNTS_TOWARDS_DAILY, start, end);
        return used == null ? BigDecimal.ZERO : used;
    }

    /** Before money leaves a wallet: within the per-payment limit and the daily total. */
    public void checkSend(UUID walletId, KycStatus status, BigDecimal amount) {
        Limits limits = limitsFor(status);
        boolean verified = status == KycStatus.VERIFIED;

        if (amount.compareTo(limits.perPayment()) > 0) {
            throw new BadRequestException("This is over your " + tzs(limits.perPayment()) + " TZS per-payment limit." + raise(verified));
        }
        BigDecimal usedToday = usedToday(walletId);
        if (usedToday.add(amount).compareTo(limits.daily()) > 0) {
            BigDecimal left = limits.daily().subtract(usedToday).max(BigDecimal.ZERO);
            throw new BadRequestException("This is over your " + tzs(limits.daily()) + " TZS daily limit. You can still send "
                    + tzs(left) + " TZS today." + raise(verified));
        }
    }

    /** Before money lands in a wallet: the balance after must stay under the max balance. */
    public void checkReceive(BigDecimal currentBalance, KycStatus status, BigDecimal incoming) {
        Limits limits = limitsFor(status);
        if (currentBalance.add(incoming).compareTo(limits.maxBalance()) > 0) {
            throw new BadRequestException("A wallet can hold at most " + tzs(limits.maxBalance()) + " TZS." + raise(status == KycStatus.VERIFIED));
        }
    }

    private static String raise(boolean alreadyVerified) {
        return alreadyVerified ? "" : " Verify your identity to raise your limits.";
    }

    private static String tzs(BigDecimal amount) {
        return new DecimalFormat("#,##0").format(amount);
    }
}
