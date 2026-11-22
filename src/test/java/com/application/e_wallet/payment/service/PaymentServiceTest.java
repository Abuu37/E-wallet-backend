package com.application.e_wallet.payment.service;

import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.payment.dto.CheckoutSessionResponse;
import com.application.e_wallet.payment.dto.CreateCheckoutSessionRequest;
import com.application.e_wallet.payment.gateway.CheckoutSessionResult;
import com.application.e_wallet.payment.gateway.StripeGatewayClient;
import com.application.e_wallet.tariff.entity.TariffEntity;
import com.application.e_wallet.tariff.service.TariffService;
import com.application.e_wallet.transaction.entity.TransactionEntity;
import com.application.e_wallet.transaction.entity.TransactionStatus;
import com.application.e_wallet.transaction.entity.TransactionType;
import com.application.e_wallet.transaction.repository.TransactionRepository;
import com.application.e_wallet.wallet.entity.WalletEntity;
import com.application.e_wallet.wallet.entity.WalletStatus;
import com.application.e_wallet.wallet.entity.WalletType;
import com.application.e_wallet.wallet.repository.WalletRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock private WalletRepository walletRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private TariffService tariffService;
    @Mock private StripeGatewayClient stripeGatewayClient;
    @InjectMocks private PaymentService paymentService;

    @Test
    void createCheckoutSessionBuildsPendingTransactionAgainstClearingAndCustomerWallets() {
        UUID userId = UUID.randomUUID();
        WalletEntity customerWallet = WalletEntity.builder()
                .id(UUID.randomUUID()).walletType(WalletType.CUSTOMER_MAIN)
                .status(WalletStatus.ACTIVE).currency("TZS").build();
        WalletEntity clearingWallet = WalletEntity.builder()
                .id(UUID.randomUUID()).walletType(WalletType.PAYMENT_GATEWAY_CLEARING).build();
        when(walletRepository.findByUserIdAndWalletType(userId, WalletType.CUSTOMER_MAIN))
                .thenReturn(Optional.of(customerWallet));
        when(walletRepository.findByWalletType(WalletType.PAYMENT_GATEWAY_CLEARING))
                .thenReturn(Optional.of(clearingWallet));
        when(tariffService.findApplicableTariff("TOPUP_STRIPE", new BigDecimal("5000.00")))
                .thenReturn(TariffEntity.builder().fee(new BigDecimal("0.00")).agentCommission(BigDecimal.ZERO).build());
        when(transactionRepository.existsByReferenceNo(any())).thenReturn(false);
        when(stripeGatewayClient.createCheckoutSession(eq(new BigDecimal("5000.00")), eq("usd"), any()))
                .thenReturn(new CheckoutSessionResult("cs_test_123", "https://checkout.stripe.com/pay/cs_test_123"));
        when(transactionRepository.save(any(TransactionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        CheckoutSessionResponse response = paymentService.createCheckoutSession(
                new CreateCheckoutSessionRequest(new BigDecimal("5000.00")), userId);

        assertThat(response.checkoutUrl()).isEqualTo("https://checkout.stripe.com/pay/cs_test_123");
        verify(transactionRepository).save(argThat(t ->
                t.getStatus().equals(TransactionStatus.PENDING.name())
                && t.getTransactionType().equals(TransactionType.TOPUP_STRIPE.name())
                && t.getSenderWallet().equals(clearingWallet)
                && t.getReceiverWallet().equals(customerWallet)
                && t.getAmount().compareTo(new BigDecimal("5000.00")) == 0
                && t.getExternalReference().equals("cs_test_123")
        ));
    }

    @Test
    void createCheckoutSessionRejectsWhenCustomerWalletNotActive() {
        UUID userId = UUID.randomUUID();
        WalletEntity customerWallet = WalletEntity.builder()
                .walletType(WalletType.CUSTOMER_MAIN).status(WalletStatus.SUSPENDED).currency("TZS").build();
        when(walletRepository.findByUserIdAndWalletType(userId, WalletType.CUSTOMER_MAIN))
                .thenReturn(Optional.of(customerWallet));

        assertThatThrownBy(() -> paymentService.createCheckoutSession(
                new CreateCheckoutSessionRequest(new BigDecimal("5000.00")), userId))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(stripeGatewayClient);
    }

    @Test
    void createCheckoutSessionPropagatesTariffFailureWithoutCallingStripe() {
        UUID userId = UUID.randomUUID();
        WalletEntity customerWallet = WalletEntity.builder()
                .walletType(WalletType.CUSTOMER_MAIN).status(WalletStatus.ACTIVE).currency("TZS").build();
        when(walletRepository.findByUserIdAndWalletType(userId, WalletType.CUSTOMER_MAIN))
                .thenReturn(Optional.of(customerWallet));
        when(walletRepository.findByWalletType(WalletType.PAYMENT_GATEWAY_CLEARING))
                .thenReturn(Optional.of(WalletEntity.builder().walletType(WalletType.PAYMENT_GATEWAY_CLEARING).build()));
        when(tariffService.findApplicableTariff("TOPUP_STRIPE", new BigDecimal("500.00")))
                .thenThrow(new BadRequestException("No applicable tariff configured for this amount"));

        assertThatThrownBy(() -> paymentService.createCheckoutSession(
                new CreateCheckoutSessionRequest(new BigDecimal("500.00")), userId))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(stripeGatewayClient);
    }
}
