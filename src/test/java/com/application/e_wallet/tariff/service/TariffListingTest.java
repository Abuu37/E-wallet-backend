package com.application.e_wallet.tariff.service;

import com.application.e_wallet.tariff.dto.TariffBandItem;
import com.application.e_wallet.tariff.entity.TariffEntity;
import com.application.e_wallet.tariff.repository.TariffRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TariffListingTest {

    @Mock private TariffRepository tariffRepository;
    @InjectMocks private TariffService service;

    private static TariffEntity band(String type, String min, String max, String fee, String commission, boolean active) {
        return TariffEntity.builder().id(UUID.randomUUID()).transactionType(type)
                .minAmount(new BigDecimal(min)).maxAmount(new BigDecimal(max))
                .fee(new BigDecimal(fee)).agentCommission(new BigDecimal(commission)).active(active).build();
    }

    @Test
    void listsEveryBandGroupedByTypeAndOrderedByAmountRange() {
        when(tariffRepository.findAll()).thenReturn(List.of(
                band("CASH_OUT", "50000.00", "100000000.00", "600.00", "200.00", true),
                band("CASH_IN", "1000.00", "100000000.00", "0.00", "200.00", true),
                band("CASH_OUT", "1000.00", "49999.99", "300.00", "200.00", true)));

        List<TariffBandItem> items = service.listBands();

        assertThat(items).extracting(TariffBandItem::transactionType).containsExactly("CASH_IN", "CASH_OUT", "CASH_OUT");
        assertThat(items.get(1).minAmount()).isEqualByComparingTo("1000");
        assertThat(items.get(2).minAmount()).isEqualByComparingTo("50000");
    }

    @Test
    void inactiveBandsAreIncludedAndMarkedSoStaffCanSeeThem() {
        when(tariffRepository.findAll()).thenReturn(List.of(band("CASH_IN", "1000.00", "100000000.00", "0.00", "200.00", false)));

        assertThat(service.listBands().get(0).active()).isFalse();
    }
}
