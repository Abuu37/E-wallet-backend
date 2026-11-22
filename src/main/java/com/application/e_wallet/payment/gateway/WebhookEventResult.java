package com.application.e_wallet.payment.gateway;

public record WebhookEventResult(String eventType, String sessionId, String paymentIntentId) {
}
