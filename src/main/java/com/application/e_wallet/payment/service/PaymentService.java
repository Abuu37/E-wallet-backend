package com.application.e_wallet.payment.service;

import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.ledger.dto.JournalLineRequest;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final DateTimeFormatter REFERENCE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final UUID SYSTEM_USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final TariffService tariffService;
    private final StripeGatewayClient stripeGatewayClient;
    private final LedgerService ledgerService;
    private final UserRepository userRepository;

    @Transactional
    public CheckoutSessionResponse createCheckoutSession(CreateCheckoutSessionRequest request, UUID userId) {

        WalletEntity customerWallet = walletRepository.findByUserIdAndWalletType(userId, WalletType.CUSTOMER_MAIN)
                .orElseThrow(() -> new BadRequestException("Customer wallet not found"));

        if (customerWallet.getStatus() != WalletStatus.ACTIVE) {
            throw new BadRequestException("Customer wallet is not active");
        }

        WalletEntity clearingWallet = walletRepository.findByWalletType(WalletType.PAYMENT_GATEWAY_CLEARING)
                .orElseThrow(() -> new IllegalStateException("Payment gateway clearing wallet not initialized"));

        TariffEntity tariff = tariffService.findApplicableTariff(TransactionType.TOPUP_STRIPE.name(), request.amount());
        BigDecimal fee = tariff.getFee();
        BigDecimal totalAmount = request.amount().add(fee);

        String referenceNo = generateReferenceNo();

        CheckoutSessionResult result = stripeGatewayClient.createCheckoutSession(totalAmount, "usd", referenceNo);

        TransactionEntity transaction = TransactionEntity.builder()
                .referenceNo(referenceNo)
                .senderWallet(clearingWallet)
                .receiverWallet(customerWallet)
                .amount(request.amount())
                .fee(fee)
                .commission(BigDecimal.ZERO)
                // Visible/reported as CASH_IN - a Stripe top-up is a cash-in from the customer's
                // perspective, and dashboards/summaries only ever bucket by CASH_IN/CASH_OUT.
                // TOPUP_STRIPE stays the tariff lookup key (line below) and the ledger journal
                // type, since those have different fee/commission bands and arithmetic (no agent
                // commission, MINT_FLOW-style wallet debit) that CASH_IN doesn't represent.
                .transactionType(TransactionType.CASH_IN.name())
                .status(TransactionStatus.PENDING.name())
                .externalReference(result.sessionId())
                .build();

        transactionRepository.save(transaction);

        return new CheckoutSessionResponse(result.checkoutUrl(), referenceNo);
    }

    @Transactional
    public void handleWebhookEvent(String payload, String signatureHeader) {

        WebhookEventResult event = stripeGatewayClient.parseEvent(payload, signatureHeader);

        if (!event.eventType().equals("checkout.session.completed")
                && !event.eventType().equals("checkout.session.expired")) {
            return;
        }

        if (event.sessionId() == null) {
            return;
        }

        Optional<TransactionEntity> maybeTransaction = transactionRepository.findByExternalReferenceForUpdate(event.sessionId());
        if (maybeTransaction.isEmpty()) {
            return;
        }

        TransactionEntity transaction = maybeTransaction.get();
        if (!transaction.getStatus().equals(TransactionStatus.PENDING.name())) {
            return;
        }

        if (event.eventType().equals("checkout.session.expired")) {
            transaction.setStatus(TransactionStatus.FAILED.name());
            transactionRepository.save(transaction);
            return;
        }

        WalletEntity customerWallet = walletRepository.findByUserIdAndWalletTypeForUpdate(
                        transaction.getReceiverWallet().getUser().getId(), WalletType.CUSTOMER_MAIN)
                .orElseThrow(() -> new IllegalStateException("Customer wallet not found"));

        WalletEntity clearingWallet = walletRepository.findByWalletTypeForUpdate(WalletType.PAYMENT_GATEWAY_CLEARING)
                .orElseThrow(() -> new IllegalStateException("Payment gateway clearing wallet not initialized"));

        BigDecimal amount = transaction.getAmount();
        BigDecimal fee = transaction.getFee();
        BigDecimal total = amount.add(fee);

        clearingWallet.setBalance(clearingWallet.getBalance().add(total));
        customerWallet.setBalance(customerWallet.getBalance().add(amount));

        List<JournalLineRequest> lines = new ArrayList<>(List.of(
                new JournalLineRequest(clearingWallet.getId(), EntryType.DEBIT, total),
                new JournalLineRequest(customerWallet.getId(), EntryType.CREDIT, amount)
        ));

        WalletEntity revenueWallet = null;
        if (fee.compareTo(BigDecimal.ZERO) > 0) {
            revenueWallet = walletRepository.findByWalletTypeForUpdate(WalletType.REVENUE)
                    .orElseThrow(() -> new IllegalStateException("Revenue wallet not initialized"));
            revenueWallet.setBalance(revenueWallet.getBalance().add(fee));
            lines.add(new JournalLineRequest(revenueWallet.getId(), EntryType.CREDIT, fee));
        }

        UserEntity systemUser = userRepository.findById(SYSTEM_USER_ID)
                .orElseThrow(() -> new IllegalStateException("System user not found"));

        ledgerService.postJournal(LedgerTransactionType.TOPUP_STRIPE,
                "Stripe top-up for " + transaction.getReferenceNo(), systemUser, lines);

        walletRepository.save(customerWallet);
        walletRepository.save(clearingWallet);
        if (revenueWallet != null) {
            walletRepository.save(revenueWallet);
        }

        transaction.setStatus(TransactionStatus.COMPLETED.name());
        // Guard against wiping out the stored session id: if the payment intent id couldn't be
        // read off the event, keep the existing externalReference rather than overwriting it
        // with null.
        if (event.paymentIntentId() != null) {
            transaction.setExternalReference(event.paymentIntentId());
        }
        transactionRepository.save(transaction);
    }

    private String generateReferenceNo() {
        String referenceNo;
        do {
            String datePart = OffsetDateTime.now().format(REFERENCE_DATE_FORMAT);
            String randomPart = UUID.randomUUID()
                    .toString()
                    .replace("-", "")
                    .substring(0, 8)
                    .toUpperCase();
            referenceNo = "TXN-" + datePart + "-" + randomPart;
        } while (transactionRepository.existsByReferenceNo(referenceNo));

        return referenceNo;
    }
}
