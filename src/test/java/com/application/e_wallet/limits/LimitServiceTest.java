package com.application.e_wallet.limits;

import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.kyc.entity.KycStatus;
import com.application.e_wallet.kyc.service.KycLimitsProperties;
import com.application.e_wallet.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class LimitServiceTest {

    @Mock private TransactionRepository transactions;

    private LimitService limits;
    private final UUID wallet = UUID.randomUUID();

    private static BigDecimal tzs(long amount) {
        return BigDecimal.valueOf(amount);
    }

    private void alreadySentToday(long amount) {
        lenient().when(transactions.sumSentBetween(eq(wallet), any(), any(), any())).thenReturn(tzs(amount));
    }

    @BeforeEach
    void setUp() {
        limits = new LimitService(new KycLimitsProperties(), transactions);
        alreadySentToday(0);
    }

    // ---- the tiers

    @Test
    void basicAndVerifiedTiersMatchTheAgreedNumbers() {
        assertThat(limits.limitsFor(KycStatus.NOT_STARTED)).isEqualTo(new LimitService.Limits(tzs(200_000), tzs(500_000), tzs(1_000_000)));
        assertThat(limits.limitsFor(KycStatus.PENDING).perPayment()).isEqualByComparingTo("200000");
        assertThat(limits.limitsFor(KycStatus.FAILED).daily()).isEqualByComparingTo("500000");
        assertThat(limits.limitsFor(KycStatus.VERIFIED)).isEqualTo(new LimitService.Limits(tzs(3_000_000), tzs(5_000_000), tzs(10_000_000)));
    }

    // ---- per payment

    @Test
    void aPaymentUpToTheLimitIsAllowed() {
        assertThatCode(() -> limits.checkSend(wallet, KycStatus.NOT_STARTED, tzs(200_000))).doesNotThrowAnyException();
    }

    @Test
    void aPaymentOverTheLimitIsRefusedAndTellsThePersonHowToRaiseIt() {
        assertThatThrownBy(() -> limits.checkSend(wallet, KycStatus.NOT_STARTED, tzs(200_001)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("200,000").hasMessageContaining("per-payment").hasMessageContaining("Verify your identity");
    }

    @Test
    void aVerifiedPersonHasTheHigherLimitAndNoVerifyHint() {
        assertThatCode(() -> limits.checkSend(wallet, KycStatus.VERIFIED, tzs(3_000_000))).doesNotThrowAnyException();
        assertThatThrownBy(() -> limits.checkSend(wallet, KycStatus.VERIFIED, tzs(3_000_001)))
                .hasMessageContaining("3,000,000").hasMessageNotContaining("Verify your identity");
    }

    // ---- per day

    @Test
    void theDailyTotalCountsWhatWasAlreadySent() {
        alreadySentToday(400_000);

        assertThatCode(() -> limits.checkSend(wallet, KycStatus.NOT_STARTED, tzs(100_000))).doesNotThrowAnyException();
        assertThatThrownBy(() -> limits.checkSend(wallet, KycStatus.NOT_STARTED, tzs(100_001)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("daily").hasMessageContaining("100,000 TZS today");
    }

    @Test
    void whenTheDayIsUsedUpTheMessageSaysNothingIsLeft() {
        alreadySentToday(500_000);

        assertThatThrownBy(() -> limits.checkSend(wallet, KycStatus.NOT_STARTED, tzs(1)))
                .hasMessageContaining("0 TZS today");
    }

    @Test
    void anEmptyDayStartsFromZero() {
        lenient().when(transactions.sumSentBetween(eq(wallet), any(), any(), any())).thenReturn(null);

        assertThat(limits.usedToday(wallet)).isEqualByComparingTo("0");
    }

    @Test
    void onlyMoneyLeavingTheWalletCounts() {
        assertThat(LimitService.COUNTS_TOWARDS_DAILY).containsExactlyInAnyOrder("TRANSFER", "CASH_OUT", "BANK_WITHDRAWAL")
                .doesNotContain("CASH_IN", "BANK_TOPUP");
    }

    // ---- max balance

    @Test
    void aWalletCannotHoldMoreThanItsMaxBalance() {
        assertThatCode(() -> limits.checkReceive(tzs(900_000), KycStatus.NOT_STARTED, tzs(100_000))).doesNotThrowAnyException();
        assertThatThrownBy(() -> limits.checkReceive(tzs(900_000), KycStatus.NOT_STARTED, tzs(100_001)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("1,000,000");
    }

    @Test
    void verifiedWalletsMayHoldMore() {
        assertThatCode(() -> limits.checkReceive(tzs(9_000_000), KycStatus.VERIFIED, tzs(1_000_000))).doesNotThrowAnyException();
    }
}
