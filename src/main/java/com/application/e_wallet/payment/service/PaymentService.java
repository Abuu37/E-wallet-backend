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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final DateTimeFormatter REFERENCE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final TariffService tariffService;
    private final StripeGatewayClient stripeGatewayClient;

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
                .transactionType(TransactionType.TOPUP_STRIPE.name())
                .status(TransactionStatus.PENDING.name())
                .externalReference(result.sessionId())
                .build();

        transactionRepository.save(transaction);

        return new CheckoutSessionResponse(result.checkoutUrl(), referenceNo);
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
