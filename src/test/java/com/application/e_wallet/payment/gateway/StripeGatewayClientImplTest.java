package com.application.e_wallet.payment.gateway;

import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.payment.config.StripeProperties;
import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.StripeObject;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for StripeGatewayClientImpl, exercising it against mocked stripe-java SDK static
 * calls (Session.create, Webhook.constructEvent) so no real network call is made.
 */
class StripeGatewayClientImplTest {

    private final StripeProperties stripeProperties = new StripeProperties();
    private final StripeGatewayClientImpl client = new StripeGatewayClientImpl(stripeProperties);

    // ---- Fix 2: card-only checkout sessions ----

    @Test
    void createCheckoutSessionRestrictsPaymentMethodTypeToCardOnly() {
        stripeProperties.setSuccessUrl("https://example.com/success");
        stripeProperties.setCancelUrl("https://example.com/cancel");

        Session fakeSession = mock(Session.class);
        when(fakeSession.getId()).thenReturn("cs_test_1");
        when(fakeSession.getUrl()).thenReturn("https://checkout.stripe.com/pay/cs_test_1");

        ArgumentCaptor<SessionCreateParams> paramsCaptor = ArgumentCaptor.forClass(SessionCreateParams.class);

        try (MockedStatic<Session> sessionStatic = mockStatic(Session.class)) {
            sessionStatic.when(() -> Session.create(paramsCaptor.capture())).thenReturn(fakeSession);

            CheckoutSessionResult result =
                    client.createCheckoutSession(new BigDecimal("1000.00"), "usd", "TXN-1");

            assertThat(result.sessionId()).isEqualTo("cs_test_1");
            assertThat(paramsCaptor.getValue().getAllowedPaymentMethodTypes())
                    .containsExactly(SessionCreateParams.AllowedPaymentMethodType.CARD);
        }
    }

    // ---- Fix 1: webhook deserialization fallback on API-version mismatch ----

    @Test
    void parseEventFallsBackToRawDeserializationWhenTypedDeserializationIsEmpty()
            throws EventDataObjectDeserializationException {
        Event event = mock(Event.class);
        when(event.getId()).thenReturn("evt_1");
        when(event.getType()).thenReturn("checkout.session.completed");

        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        // Simulates an API-version mismatch: typed deserialization comes back empty.
        when(deserializer.getObject()).thenReturn(Optional.empty());

        Session rawSession = mock(Session.class);
        when(rawSession.getId()).thenReturn("cs_test_fallback");
        when(rawSession.getPaymentIntent()).thenReturn("pi_fallback");
        when(deserializer.deserializeUnsafe()).thenReturn(rawSession);

        try (MockedStatic<Webhook> webhookStatic = mockStatic(Webhook.class)) {
            webhookStatic.when(() -> Webhook.constructEvent(any(), any(), any())).thenReturn(event);

            WebhookEventResult result = client.parseEvent("payload", "sig");

            assertThat(result.eventType()).isEqualTo("checkout.session.completed");
            assertThat(result.sessionId()).isEqualTo("cs_test_fallback");
            assertThat(result.paymentIntentId()).isEqualTo("pi_fallback");
        }
    }

    @Test
    void parseEventThrowsInsteadOfSilentlyDroppingTheEventWhenRawDeserializationAlsoFails()
            throws EventDataObjectDeserializationException {
        Event event = mock(Event.class);
        when(event.getId()).thenReturn("evt_2");
        when(event.getType()).thenReturn("checkout.session.completed");

        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        when(deserializer.getObject()).thenReturn(Optional.empty());
        when(deserializer.deserializeUnsafe())
                .thenThrow(new EventDataObjectDeserializationException("boom", "{}"));

        try (MockedStatic<Webhook> webhookStatic = mockStatic(Webhook.class)) {
            webhookStatic.when(() -> Webhook.constructEvent(any(), any(), any())).thenReturn(event);

            // Must throw (non-2xx at the controller) rather than return a WebhookEventResult
            // with a null sessionId - a null here would make PaymentService treat a paid
            // checkout as "nothing to do" and Stripe would never retry.
            assertThatThrownBy(() -> client.parseEvent("payload", "sig"))
                    .isInstanceOf(RuntimeException.class)
                    .isNotInstanceOf(BadRequestException.class);
        }
    }

    @Test
    void parseEventThrowsWhenRawDeserializationYieldsSomethingOtherThanASession()
            throws EventDataObjectDeserializationException {
        Event event = mock(Event.class);
        when(event.getId()).thenReturn("evt_3");
        when(event.getType()).thenReturn("checkout.session.completed");

        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        when(deserializer.getObject()).thenReturn(Optional.empty());
        when(deserializer.deserializeUnsafe()).thenReturn(mock(StripeObject.class));

        try (MockedStatic<Webhook> webhookStatic = mockStatic(Webhook.class)) {
            webhookStatic.when(() -> Webhook.constructEvent(any(), any(), any())).thenReturn(event);

            assertThatThrownBy(() -> client.parseEvent("payload", "sig"))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void parseEventSkipsDeserializationForNonCheckoutSessionEventTypes() {
        Event event = mock(Event.class);
        when(event.getType()).thenReturn("payment_intent.created");

        try (MockedStatic<Webhook> webhookStatic = mockStatic(Webhook.class)) {
            webhookStatic.when(() -> Webhook.constructEvent(any(), any(), any())).thenReturn(event);

            WebhookEventResult result = client.parseEvent("payload", "sig");

            assertThat(result.eventType()).isEqualTo("payment_intent.created");
            assertThat(result.sessionId()).isNull();
            assertThat(result.paymentIntentId()).isNull();
        }
    }

    // ---- Fix 4: missing signature header rejected cleanly ----

    @Test
    void parseEventRejectsMissingSignatureAsBadRequestInsteadOfReachingTheSdk() {
        assertThatThrownBy(() -> client.parseEvent("payload", null))
                .isInstanceOf(BadRequestException.class);
    }
}
