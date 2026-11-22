package com.application.e_wallet.payment.gateway;

import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.payment.config.StripeProperties;
import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.StripeObject;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Thin adapter around the stripe-java SDK. Callers (e.g. PaymentService) depend only on
 * {@link StripeGatewayClient}; no Stripe SDK type leaks through that interface.
 */
@Component
@RequiredArgsConstructor
public class StripeGatewayClientImpl implements StripeGatewayClient {

    private static final int MINOR_UNIT_MULTIPLIER = 100;

    private final StripeProperties stripeProperties;

    @PostConstruct
    void init() {
        Stripe.apiKey = stripeProperties.getSecretKey();
    }

    @Override
    public CheckoutSessionResult createCheckoutSession(BigDecimal totalAmount, String currency, String referenceNo) {
        long unitAmount = totalAmount
                .multiply(BigDecimal.valueOf(MINOR_UNIT_MULTIPLIER))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();

        SessionCreateParams params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setQuantity(1L)
                        .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                                .setCurrency(currency)
                                .setUnitAmount(unitAmount)
                                .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                        .setName("E-Wallet Top-up")
                                        .build())
                                .build())
                        .build())
                .setClientReferenceId(referenceNo)
                .setSuccessUrl(stripeProperties.getSuccessUrl())
                .setCancelUrl(stripeProperties.getCancelUrl())
                .build();

        try {
            Session session = Session.create(params);
            return new CheckoutSessionResult(session.getId(), session.getUrl());
        } catch (StripeException e) {
            throw new RuntimeException("Failed to create Stripe checkout session", e);
        }
    }

    @Override
    public WebhookEventResult parseEvent(String payload, String signatureHeader) {
        Event event;
        try {
            event = Webhook.constructEvent(payload, signatureHeader, stripeProperties.getWebhookSecret());
        } catch (SignatureVerificationException e) {
            throw new BadRequestException("Invalid Stripe webhook signature");
        }

        Optional<StripeObject> dataObject = event.getDataObjectDeserializer().getObject();
        if (dataObject.isPresent() && dataObject.get() instanceof Session session) {
            return new WebhookEventResult(event.getType(), session.getId(), session.getPaymentIntent());
        }

        return new WebhookEventResult(event.getType(), null, null);
    }
}
