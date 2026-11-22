package com.application.e_wallet.payment.service;

import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.ledger.entity.EntryType;
import com.application.e_wallet.ledger.entity.LedgerTransactionType;
import com.application.e_wallet.ledger.service.LedgerService;
import com.application.e_wallet.payment.dto.CheckoutSessionResponse;
import com.application.e_wallet.payment.dto.CreateCheckoutSessionRequest;
import com.application.e_wallet.payment.gateway.CheckoutSessionResult;
import com.application.e_wallet.payment.gateway.StripeGatewayClient;
import com.application.e_wallet.payment.gateway.WebhookEventResult;
import com.application.e_wallet.tariff.entity.TariffEntity;
import com.application.e_wallet.tariff.service.TariffService;
import com.application.e_wallet.transaction.entity.TransactionEntity;
import com.application.e_wallet.transaction.entity.TransactionStatus;
import com.application.e_wallet.transaction.entity.TransactionType;
import com.application.e_wallet.transaction.repository.TransactionRepository;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.user.repository.UserRepository;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock private WalletRepository walletRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private TariffService tariffService;
    @Mock private StripeGatewayClient stripeGatewayClient;
    @Mock private LedgerService ledgerService;
    @Mock private UserRepository userRepository;
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
                && t.getTransactionType().equals(TransactionType.CASH_IN.name())
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

    @Test
    void handleWebhookEventIgnoresUnknownEventType() {
        when(stripeGatewayClient.parseEvent("payload", "sig"))
                .thenReturn(new WebhookEventResult("payment_intent.created", "cs_test_1", null));

        paymentService.handleWebhookEvent("payload", "sig");

        verifyNoInteractions(transactionRepository, ledgerService);
    }

    @Test
    void handleWebhookEventIgnoresUnknownSessionId() {
        when(stripeGatewayClient.parseEvent("payload", "sig"))
                .thenReturn(new WebhookEventResult("checkout.session.completed", "cs_test_404", "pi_1"));
        when(transactionRepository.findByExternalReferenceForUpdate("cs_test_404")).thenReturn(Optional.empty());

        paymentService.handleWebhookEvent("payload", "sig");

        verifyNoInteractions(ledgerService);
    }

    @Test
    void handleWebhookEventIsIdempotentOnAlreadyCompletedTransaction() {
        TransactionEntity alreadyDone = TransactionEntity.builder()
                .status(TransactionStatus.COMPLETED.name()).externalReference("cs_test_1").build();
        when(stripeGatewayClient.parseEvent("payload", "sig"))
                .thenReturn(new WebhookEventResult("checkout.session.completed", "cs_test_1", "pi_1"));
        when(transactionRepository.findByExternalReferenceForUpdate("cs_test_1")).thenReturn(Optional.of(alreadyDone));

        paymentService.handleWebhookEvent("payload", "sig");

        verifyNoInteractions(ledgerService);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void handleWebhookEventOnExpiryMarksTransactionFailedWithNoWalletMovement() {
        WalletEntity customerWallet = WalletEntity.builder()
                .id(UUID.randomUUID()).walletType(WalletType.CUSTOMER_MAIN)
                .balance(new BigDecimal("1000.00")).user(UserEntity.builder().id(UUID.randomUUID()).build()).build();
        TransactionEntity pending = TransactionEntity.builder()
                .status(TransactionStatus.PENDING.name()).externalReference("cs_test_2")
                .receiverWallet(customerWallet).amount(new BigDecimal("5000.00")).fee(BigDecimal.ZERO).build();
        when(stripeGatewayClient.parseEvent("payload", "sig"))
                .thenReturn(new WebhookEventResult("checkout.session.expired", "cs_test_2", null));
        when(transactionRepository.findByExternalReferenceForUpdate("cs_test_2")).thenReturn(Optional.of(pending));

        paymentService.handleWebhookEvent("payload", "sig");

        assertThat(pending.getStatus()).isEqualTo(TransactionStatus.FAILED.name());
        assertThat(customerWallet.getBalance()).isEqualTo(new BigDecimal("1000.00"));
        verifyNoInteractions(ledgerService);
    }

    @Test
    void handleWebhookEventOnCompletionCreditsCustomerAndClearingWalletsAndPostsJournal() {
        UUID customerUserId = UUID.randomUUID();
        WalletEntity customerWallet = WalletEntity.builder()
                .id(UUID.randomUUID()).walletType(WalletType.CUSTOMER_MAIN).balance(BigDecimal.ZERO)
                .user(UserEntity.builder().id(customerUserId).build()).build();
        WalletEntity clearingWallet = WalletEntity.builder()
                .id(UUID.randomUUID()).walletType(WalletType.PAYMENT_GATEWAY_CLEARING).balance(BigDecimal.ZERO).build();
        TransactionEntity pending = TransactionEntity.builder()
                .status(TransactionStatus.PENDING.name()).externalReference("cs_test_3")
                .receiverWallet(customerWallet).amount(new BigDecimal("5000.00")).fee(BigDecimal.ZERO).build();
        UserEntity systemUser = UserEntity.builder().id(UUID.fromString("11111111-1111-1111-1111-111111111111")).build();

        when(stripeGatewayClient.parseEvent("payload", "sig"))
                .thenReturn(new WebhookEventResult("checkout.session.completed", "cs_test_3", "pi_1"));
        when(transactionRepository.findByExternalReferenceForUpdate("cs_test_3")).thenReturn(Optional.of(pending));
        when(walletRepository.findByUserIdAndWalletTypeForUpdate(customerUserId, WalletType.CUSTOMER_MAIN))
                .thenReturn(Optional.of(customerWallet));
        when(walletRepository.findByWalletTypeForUpdate(WalletType.PAYMENT_GATEWAY_CLEARING))
                .thenReturn(Optional.of(clearingWallet));
        when(userRepository.findById(systemUser.getId())).thenReturn(Optional.of(systemUser));

        paymentService.handleWebhookEvent("payload", "sig");

        assertThat(customerWallet.getBalance()).isEqualByComparingTo(new BigDecimal("5000.00"));
        assertThat(clearingWallet.getBalance()).isEqualByComparingTo(new BigDecimal("5000.00"));
        assertThat(pending.getStatus()).isEqualTo(TransactionStatus.COMPLETED.name());
        assertThat(pending.getExternalReference()).isEqualTo("pi_1");
        verify(ledgerService).postJournal(eq(LedgerTransactionType.TOPUP_STRIPE), any(), eq(systemUser), argThat(lines ->
                lines.size() == 2
                && lines.stream().anyMatch(l -> l.walletId().equals(clearingWallet.getId()) && l.entryType() == EntryType.DEBIT && l.amount().compareTo(new BigDecimal("5000.00")) == 0)
                && lines.stream().anyMatch(l -> l.walletId().equals(customerWallet.getId()) && l.entryType() == EntryType.CREDIT && l.amount().compareTo(new BigDecimal("5000.00")) == 0)
        ));
    }

    @Test
    void handleWebhookEventKeepsExistingExternalReferenceWhenPaymentIntentIdIsMissing() {
        UUID customerUserId = UUID.randomUUID();
        WalletEntity customerWallet = WalletEntity.builder()
                .id(UUID.randomUUID()).walletType(WalletType.CUSTOMER_MAIN).balance(BigDecimal.ZERO)
                .user(UserEntity.builder().id(customerUserId).build()).build();
        WalletEntity clearingWallet = WalletEntity.builder()
                .id(UUID.randomUUID()).walletType(WalletType.PAYMENT_GATEWAY_CLEARING).balance(BigDecimal.ZERO).build();
        TransactionEntity pending = TransactionEntity.builder()
                .status(TransactionStatus.PENDING.name()).externalReference("cs_test_no_pi")
                .receiverWallet(customerWallet).amount(new BigDecimal("5000.00")).fee(BigDecimal.ZERO).build();
        UserEntity systemUser = UserEntity.builder().id(UUID.fromString("11111111-1111-1111-1111-111111111111")).build();

        // paymentIntentId is null here - the fix must not let this null overwrite the stored
        // session id (externalReference) on the transaction.
        when(stripeGatewayClient.parseEvent("payload", "sig"))
                .thenReturn(new WebhookEventResult("checkout.session.completed", "cs_test_no_pi", null));
        when(transactionRepository.findByExternalReferenceForUpdate("cs_test_no_pi")).thenReturn(Optional.of(pending));
        when(walletRepository.findByUserIdAndWalletTypeForUpdate(customerUserId, WalletType.CUSTOMER_MAIN))
                .thenReturn(Optional.of(customerWallet));
        when(walletRepository.findByWalletTypeForUpdate(WalletType.PAYMENT_GATEWAY_CLEARING))
                .thenReturn(Optional.of(clearingWallet));
        when(userRepository.findById(systemUser.getId())).thenReturn(Optional.of(systemUser));

        paymentService.handleWebhookEvent("payload", "sig");

        assertThat(pending.getStatus()).isEqualTo(TransactionStatus.COMPLETED.name());
        assertThat(pending.getExternalReference()).isEqualTo("cs_test_no_pi");
    }

    @Test
    void handleWebhookEventLooksUpTransactionWithPessimisticLockNotPlainRead() {
        UUID customerUserId = UUID.randomUUID();
        WalletEntity customerWallet = WalletEntity.builder()
                .id(UUID.randomUUID()).walletType(WalletType.CUSTOMER_MAIN).balance(BigDecimal.ZERO)
                .user(UserEntity.builder().id(customerUserId).build()).build();
        WalletEntity clearingWallet = WalletEntity.builder()
                .id(UUID.randomUUID()).walletType(WalletType.PAYMENT_GATEWAY_CLEARING).balance(BigDecimal.ZERO).build();
        TransactionEntity pending = TransactionEntity.builder()
                .status(TransactionStatus.PENDING.name()).externalReference("cs_test_lock")
                .receiverWallet(customerWallet).amount(new BigDecimal("5000.00")).fee(BigDecimal.ZERO).build();
        UserEntity systemUser = UserEntity.builder().id(UUID.fromString("11111111-1111-1111-1111-111111111111")).build();

        when(stripeGatewayClient.parseEvent("payload", "sig"))
                .thenReturn(new WebhookEventResult("checkout.session.completed", "cs_test_lock", "pi_1"));
        when(transactionRepository.findByExternalReferenceForUpdate("cs_test_lock")).thenReturn(Optional.of(pending));
        when(walletRepository.findByUserIdAndWalletTypeForUpdate(customerUserId, WalletType.CUSTOMER_MAIN))
                .thenReturn(Optional.of(customerWallet));
        when(walletRepository.findByWalletTypeForUpdate(WalletType.PAYMENT_GATEWAY_CLEARING))
                .thenReturn(Optional.of(clearingWallet));
        when(userRepository.findById(systemUser.getId())).thenReturn(Optional.of(systemUser));

        paymentService.handleWebhookEvent("payload", "sig");

        // The idempotency-check lookup must use the pessimistic-lock variant so two concurrent
        // deliveries of the same webhook event serialize on this row instead of both reading it
        // as PENDING and both crediting the wallets (the race this fix closes). The plain,
        // unlocked finder must never be used for this lookup.
        verify(transactionRepository).findByExternalReferenceForUpdate("cs_test_lock");
        verify(transactionRepository, never()).findByExternalReference(any());
    }
}
