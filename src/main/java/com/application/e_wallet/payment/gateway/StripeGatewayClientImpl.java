package com.application.e_wallet.payment.gateway;

import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.payment.config.StripeProperties;
import com.stripe.Stripe;
import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
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
                // Restrict to card only. Without this, Stripe's Dashboard-configured default
                // payment methods can apply (e.g. US bank account/ACH for a USD session), which
                // are asynchronous: checkout.session.completed fires on session completion, not
                // on settlement, so an async method's session can complete while payment_status
                // is still "unpaid" and the real outcome arrives later via an event this
                // integration doesn't listen for. Card payments settle synchronously, which is
                // what the rest of this integration (and handleWebhookEvent) assumes.
                .addAllowedPaymentMethodType(SessionCreateParams.AllowedPaymentMethodType.CARD)
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
        if (signatureHeader == null) {
            throw new BadRequestException("Missing Stripe-Signature header");
        }

        Event event;
        try {
            event = Webhook.constructEvent(payload, signatureHeader, stripeProperties.getWebhookSecret());
        } catch (SignatureVerificationException e) {
            throw new BadRequestException("Invalid Stripe webhook signature");
        }

        String eventType = event.getType();
        boolean isCheckoutSessionEvent = "checkout.session.completed".equals(eventType)
                || "checkout.session.expired".equals(eventType);

        if (!isCheckoutSessionEvent) {
            return new WebhookEventResult(eventType, null, null);
        }

        Session session = resolveCheckoutSession(event);
        return new WebhookEventResult(eventType, session.getId(), session.getPaymentIntent());
    }

    // The pinned stripe-java 34.2.0 release is bound to com.stripe.ApiVersion.CURRENT. Typed
    // deserialization (getObject()) silently returns Optional.empty() whenever the webhook
    // event's own api_version doesn't match that pinned version - e.g. an older Stripe
    // account/webhook endpoint sending events in an API version older than this brand-new SDK
    // expects. If we let that fall through to a null sessionId, PaymentService.handleWebhookEvent
    // treats it as "nothing to do", returns 200, and Stripe never retries - the customer paid but
    // is never credited. So: fall back to the SDK's untyped/raw deserialization, which bypasses
    // the api-version gate and extracts the stable primitive fields (id, payment_intent) that
    // don't change shape across API versions. If even that fails, throw so the controller
    // returns a non-2xx response and Stripe retries the delivery instead of considering it
    // handled.
    private Session resolveCheckoutSession(Event event) {
        EventDataObjectDeserializer deserializer = event.getDataObjectDeserializer();

        Optional<StripeObject> dataObject = deserializer.getObject();
        if (dataObject.isPresent() && dataObject.get() instanceof Session session) {
            return session;
        }

        StripeObject rawObject;
        try {
            rawObject = deserializer.deserializeUnsafe();
        } catch (EventDataObjectDeserializationException e) {
            throw new RuntimeException(
                    "Unable to deserialize checkout session from Stripe webhook event " + event.getId(), e);
        }

        if (rawObject instanceof Session session) {
            return session;
        }

        throw new RuntimeException(
                "Stripe webhook event " + event.getId() + " of type " + event.getType()
                        + " did not contain a checkout session payload");
    }
}
