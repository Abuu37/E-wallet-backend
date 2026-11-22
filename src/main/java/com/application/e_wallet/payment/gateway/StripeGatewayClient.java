package com.application.e_wallet.payment.gateway;

import java.math.BigDecimal;

/**
 * Port that isolates callers (e.g. PaymentService) from the stripe-java SDK.
 * No method signature here references any Stripe SDK type, so this interface
 * can be mocked in tests without pulling in the SDK.
 */
public interface StripeGatewayClient {

    CheckoutSessionResult createCheckoutSession(BigDecimal totalAmount, String currency, String referenceNo);

    WebhookEventResult parseEvent(String payload, String signatureHeader);
}
