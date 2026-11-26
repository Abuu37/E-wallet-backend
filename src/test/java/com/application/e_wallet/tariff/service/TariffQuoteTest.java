package com.application.e_wallet.tariff.service;

import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.tariff.dto.QuoteResponse;
import com.application.e_wallet.tariff.entity.TariffEntity;
import com.application.e_wallet.tariff.repository.TariffRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TariffQuoteTest {

    @Mock private TariffRepository repository;
    @InjectMocks private TariffService service;

    private void fee(String type, String fee) {
        when(repository.findByTransactionTypeAndActiveTrueAndMinAmountLessThanEqualAndMaxAmountGreaterThanEqual(eq(type), any(), any()))
                .thenReturn(Optional.of(TariffEntity.builder().fee(new BigDecimal(fee)).build()));
    }

    @Test
    void aTransferQuoteAddsTheFeeToTheAmount() {
        fee("TRANSFER", "300");

        QuoteResponse quote = service.quote("transfer", new BigDecimal("20000"));

        assertThat(quote.type()).isEqualTo("TRANSFER");
        assertThat(quote.fee()).isEqualByComparingTo("300");
        assertThat(quote.total()).isEqualByComparingTo("20300");
    }

    @Test
    void anAgentWithdrawalQuoteIsTheCashOutFee() {
        fee("CASH_OUT", "300");

        assertThat(service.quote("CASH_OUT", new BigDecimal("30000")).total()).isEqualByComparingTo("30300");
    }

    @Test
    void onlyKnownTypesCanBeQuoted() {
        assertThatThrownBy(() -> service.quote("CASH_IN", new BigDecimal("1000"))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.quote(null, new BigDecimal("1000"))).isInstanceOf(BadRequestException.class);
    }

    @Test
    void theAmountMustBePositiveWithAtMostTwoDecimals() {
        assertThatThrownBy(() -> service.quote("TRANSFER", BigDecimal.ZERO)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.quote("TRANSFER", null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.quote("TRANSFER", new BigDecimal("10.001"))).isInstanceOf(BadRequestException.class);
    }
}
