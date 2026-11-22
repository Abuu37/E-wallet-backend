# Stripe Checkout Cash-In Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a customer top up their own `CUSTOMER_MAIN` wallet via a Stripe Checkout (test mode) hosted page, independent of the existing agent cash-in/cash-out flows, verified end-to-end with Postman + the Stripe CLI before any frontend is wired up.

**Architecture:** A new `payment` module (`controller`/`service`/`gateway`/`dto`/`config`) parallel to `transaction`/`treasury`. `PaymentService` owns all business logic (tariff lookup, wallet selection, idempotency, ledger posting) and depends only on the `StripeGatewayClient` interface, never the Stripe SDK directly — this is what makes `PaymentService` unit-testable without a live Stripe connection. `StripeGatewayClientImpl` is the thin adapter that does touch the SDK; it has no business logic, so it is verified manually (Postman + Stripe CLI), not with a mocked-SDK unit test.

**Tech Stack:** Spring Boot 4.2.0-SNAPSHOT, Java 21, Maven, PostgreSQL + Flyway, JUnit 5 + Mockito + AssertJ (existing test stack — see `LedgerServiceTest`), `com.stripe:stripe-java` (new).

**Spec:** `docs/superpowers/specs/2026-11-21-stripe-checkout-cashin-design.md`

## Global Constraints

- Test mode only — `STRIPE_SECRET_KEY` is `sk_test_...`; no live key anywhere (spec decision 11).
- Stripe touches cash-in only; existing `CASH_IN`/`CASH_OUT` code, wallets, and tariffs are not modified (spec decision 1, non-goals).
- The webhook endpoint must be reachable without a JWT and is secured only by Stripe signature verification (spec decision 9).
- No new permission — the checkout-session endpoint reuses the existing `WALLET_TO_BANK_TRANSFER` permission, already granted to `CUSTOMER` (spec decision 6).
- `PAYMENT_GATEWAY_CLEARING`'s balance **increases** (`.add()`) on its DEBIT-labeled journal line — the `MINT_FLOAT` pattern, not the `CASH_IN`/`CASH_OUT` pattern (spec, "Flow" section; confirmed against `TreasuryServiceTest.mintPostsTwoDistinctWalletLegs`, which asserts both legs of a `MINT_FLOAT` journal increase).
- The Stripe Checkout Session is always created in `usd`, hardcoded — never `customerWallet.getCurrency()` (which is always `TZS`). Stripe's actual supported presentment currencies depend on the test account's configuration and couldn't be confirmed to include `TZS`; USD avoids an unsupported-currency failure during the live demo (spec decision 12). The wallet's `TZS` balance is still what's credited internally — this only affects what currency Stripe's own hosted page charges in.
- `TransactionEntity.senderWallet` (NOT NULL) is set to the `PAYMENT_GATEWAY_CLEARING` wallet at checkout-session creation time, before any money has moved — it is only an FK pointer; `status` communicates whether funds actually moved (spec, "Flow" section).

## Review Focus

- **Stripe redelivers the same webhook event** (its own retry behavior, not a bug) — a second delivery for an already-`COMPLETED` (or `FAILED`) transaction must not move money twice. Covered in Task 5.
- **The `Stripe-Signature` header doesn't verify** (forged or tampered request) — must be rejected before any transaction lookup or wallet mutation, not processed as if genuine. Covered in Task 5.
- **The customer never pays and the session expires** (`checkout.session.expired`) — the transaction must end up `FAILED` with zero wallet/ledger movement, not stay `PENDING` forever or accidentally credit anything. Covered in Task 5.
- **A customer whose wallet is `SUSPENDED`/`CLOSED` requests a checkout session** — must be rejected before a Stripe session is even created (don't let a non-active wallet receive funds, and don't waste a Stripe API call on a request that can't complete). Covered in Task 4.
- **The requested amount falls outside every configured `TOPUP_STRIPE` tariff band** (e.g. below the 1,000 TZS floor) — must fail with the existing tariff-lookup error before calling Stripe, not after. Covered in Task 4.

---

### Task 1: Fix pre-existing test compilation failure

The project currently fails `mvn test-compile` on `main`, unrelated to this feature: `LedgerTransactionType` was renamed from `TransactionType` in `com.application.e_wallet.ledger.entity`, but two test files still import and reference the old name. Every later task in this plan needs `mvn test` to be a working command, so this is fixed first.

**Files:**
- Modify: `src/test/java/com/application/e_wallet/ledger/service/LedgerServiceTest.java`
- Modify: `src/test/java/com/application/e_wallet/treasury/service/TreasuryServiceTest.java`

**Interfaces:**
- Consumes: `com.application.e_wallet.ledger.entity.LedgerTransactionType` (existing enum: `MINT_FLOAT, TOPUP_FLOAT, AGENT_WITHDRAW, TOPUP_COMMISSION, CASH_IN, CASH_OUT`).

- [ ] **Step 1: Confirm the current failure**

Run: `./mvnw -q -o test-compile`
Expected: FAIL — `cannot find symbol: class TransactionType, location: package com.application.e_wallet.ledger.entity`, in both files listed above.

- [ ] **Step 2: Fix the two files**

In both `LedgerServiceTest.java` and `TreasuryServiceTest.java`: change the import `com.application.e_wallet.ledger.entity.TransactionType` to `com.application.e_wallet.ledger.entity.LedgerTransactionType`, and every bare `TransactionType.XXX` reference (e.g. `TransactionType.MINT_FLOAT`, `TransactionType.TOPUP_FLOAT`, `TransactionType.AGENT_WITHDRAW`) to `LedgerTransactionType.XXX`. Do not touch any other import or assertion.

- [ ] **Step 3: Run the full test suite to confirm the baseline is green**

Run: `./mvnw -q -o test`
Expected: PASS (all existing tests, including `LedgerServiceTest` and `TreasuryServiceTest`).

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/application/e_wallet/ledger/service/LedgerServiceTest.java src/test/java/com/application/e_wallet/treasury/service/TreasuryServiceTest.java
git commit -m "fix: update stale TransactionType references to LedgerTransactionType in tests"
```

---

### Task 2: Schema and enum additions

**Files:**
- Create: `src/main/resources/db/migration/V48__add_payment_gateway_clearing_and_stripe_topup.sql`
- Modify: `src/main/java/com/application/e_wallet/wallet/entity/WalletType.java`
- Modify: `src/main/java/com/application/e_wallet/transaction/entity/TransactionType.java`
- Modify: `src/main/java/com/application/e_wallet/ledger/entity/LedgerTransactionType.java`
- Modify: `src/main/java/com/application/e_wallet/transaction/entity/TransactionEntity.java`
- Modify: `src/main/java/com/application/e_wallet/transaction/repository/TransactionRepository.java`
- Test: `src/test/java/com/application/e_wallet/transaction/repository/TransactionRepositoryIT.java` (only if an existing repository-level IT pattern already exists in the project; if none exists anywhere in `src/test`, skip a dedicated test here — this is schema/enum plumbing with no branching logic, exercised indirectly by Task 4/5's tests through the mocked repository, and directly the first time the app boots against the real migration in Task 6's manual verification)

**Interfaces:**
- Produces: `WalletType.PAYMENT_GATEWAY_CLEARING`, `TransactionType.TOPUP_STRIPE`, `LedgerTransactionType.TOPUP_STRIPE`, `TransactionEntity.getExternalReference(): String` / `.setExternalReference(String)`, `TransactionRepository.findByExternalReference(String externalReference): Optional<TransactionEntity>`.

- [ ] **Step 1: Write the migration**

```sql
-- Extend the singleton-wallet-type constraint (same pattern as V45).
DROP INDEX uk_wallets_singleton_company_types;
CREATE UNIQUE INDEX uk_wallets_singleton_company_types
    ON wallets (wallet_type)
    WHERE wallet_type IN ('COMPANY_MASTER_ACCOUNT', 'REVENUE', 'ESCROW_BANK', 'COMMISSION', 'PAYMENT_GATEWAY_CLEARING');

-- Seed the singleton wallet, owned by the same system user as the other
-- company-level wallets (seeded in V26).
INSERT INTO wallets (id, user_id, wallet_type, account_number, currency, balance, status, version, account_name, created_at, updated_at)
VALUES (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'PAYMENT_GATEWAY_CLEARING',
        'COMPANY-CLEARING-ACCOUNT', 'TZS', 0, 'ACTIVE', 0, 'Payment Gateway Clearing', NOW(), NOW());

-- External gateway reference, for webhook idempotency and reconciliation.
ALTER TABLE transactions ADD COLUMN external_reference VARCHAR(100);
CREATE UNIQUE INDEX uk_transactions_external_reference
    ON transactions (external_reference)
    WHERE external_reference IS NOT NULL;

-- New tariff band: no agent commission, since no agent is involved.
INSERT INTO tariffs (id, transaction_type, min_amount, max_amount, fee, agent_commission, active)
VALUES (gen_random_uuid(), 'TOPUP_STRIPE', 1000.00, 100000000.00, 0.00, 0.00, true);
```

- [ ] **Step 2: Add the enum constants**

Add `PAYMENT_GATEWAY_CLEARING` to `WalletType`; add `TOPUP_STRIPE` to `TransactionType`; add `TOPUP_STRIPE` to `LedgerTransactionType`. Each is a plain new enum constant, same style as the existing ones.

- [ ] **Step 3: Add the column to `TransactionEntity`**

Add, alongside the existing `@Column` fields:

```java
@Column(name = "external_reference", length = 100)
private String externalReference;
```

- [ ] **Step 4: Add the repository method**

```java
Optional<TransactionEntity> findByExternalReference(String externalReference);
```

- [ ] **Step 5: Run the app against the real migration**

Run: `./mvnw -q -o spring-boot:run` (against your dev database), then Ctrl-C once it finishes starting with no Flyway errors.
Expected: Flyway applies `V48` cleanly; no `chk_wallets_balance_non_negative` or unique-constraint errors on startup.

- [ ] **Step 6: Run the full test suite**

Run: `./mvnw -q -o test`
Expected: PASS — this task has no branching logic, so no new test is added; this just confirms nothing existing broke (e.g. no code elsewhere exhaustively switches over `WalletType`/`TransactionType` and would now fail to compile on a missing `case`).

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration/V48__add_payment_gateway_clearing_and_stripe_topup.sql src/main/java/com/application/e_wallet/wallet/entity/WalletType.java src/main/java/com/application/e_wallet/transaction/entity/TransactionType.java src/main/java/com/application/e_wallet/ledger/entity/LedgerTransactionType.java src/main/java/com/application/e_wallet/transaction/entity/TransactionEntity.java src/main/java/com/application/e_wallet/transaction/repository/TransactionRepository.java
git commit -m "feat: add PAYMENT_GATEWAY_CLEARING wallet and TOPUP_STRIPE transaction type"
```

---

### Task 3: Stripe SDK dependency, config, and the gateway port

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.properties`
- Create: `src/main/java/com/application/e_wallet/payment/config/StripeProperties.java`
- Create: `src/main/java/com/application/e_wallet/payment/gateway/CheckoutSessionResult.java`
- Create: `src/main/java/com/application/e_wallet/payment/gateway/WebhookEventResult.java`
- Create: `src/main/java/com/application/e_wallet/payment/gateway/StripeGatewayClient.java`
- Create: `src/main/java/com/application/e_wallet/payment/gateway/StripeGatewayClientImpl.java`

**Interfaces:**
- Produces: `StripeGatewayClient` (interface, consumed by `PaymentService` in Task 4/5 — this is the seam `PaymentService`'s tests mock):

```java
public interface StripeGatewayClient {
    CheckoutSessionResult createCheckoutSession(BigDecimal totalAmount, String currency, String referenceNo);
    WebhookEventResult parseEvent(String payload, String signatureHeader);
}
```

```java
public record CheckoutSessionResult(String sessionId, String checkoutUrl) {}
public record WebhookEventResult(String eventType, String sessionId, String paymentIntentId) {}
```

`eventType` is Stripe's raw event type string (`"checkout.session.completed"`, `"checkout.session.expired"`, or anything else Stripe sends — `PaymentService` in Task 5 ignores types it doesn't recognize). `StripeGatewayClientImpl.parseEvent` throws `com.application.e_wallet.common.exception.BadRequestException` (existing class, already mapped to HTTP 400 by `GlobalExceptionHandler`) when `Webhook.constructEvent(...)` throws `SignatureVerificationException`.

This task has no business logic to unit test — `StripeGatewayClientImpl` is a thin wrapper around the `stripe-java` SDK's static calls, and its correctness is verified against the real Stripe test-mode sandbox in Task 6, not with a mocked-SDK unit test.

- [ ] **Step 1: Add the dependency**

Add to `pom.xml`, inside `<dependencies>`:

```xml
<dependency>
    <groupId>com.stripe</groupId>
    <artifactId>stripe-java</artifactId>
    <version>29.0.0</version>
</dependency>
```

(Use whatever the latest stable `stripe-java` release is at implementation time if `29.0.0` is no longer current.)

- [ ] **Step 2: Add config properties**

Add to `application.properties`, following the existing `app.jwt.*` pattern:

```properties
app.stripe.secret-key=${STRIPE_SECRET_KEY}
app.stripe.webhook-secret=${STRIPE_WEBHOOK_SECRET}
app.stripe.success-url=${STRIPE_SUCCESS_URL}
app.stripe.cancel-url=${STRIPE_CANCEL_URL}
```

Add the matching four keys to your local `.env` (not committed) with the values from Stripe's dashboard / Stripe CLI, per the spec's decision 11.

- [ ] **Step 3: Write `StripeProperties`**

```java
@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "app.stripe")
public class StripeProperties {
    private String secretKey;
    private String webhookSecret;
    private String successUrl;
    private String cancelUrl;
}
```

- [ ] **Step 4: Write the two result records and the `StripeGatewayClient` interface**

As specified in the Interfaces block above.

- [ ] **Step 5: Implement `StripeGatewayClientImpl`**

```java
@Component
@RequiredArgsConstructor
public class StripeGatewayClientImpl implements StripeGatewayClient {
    private final StripeProperties stripeProperties;

    @PostConstruct
    void init() {
        Stripe.apiKey = stripeProperties.getSecretKey();
    }

    @Override
    public CheckoutSessionResult createCheckoutSession(BigDecimal totalAmount, String currency, String referenceNo) { ... }

    @Override
    public WebhookEventResult parseEvent(String payload, String signatureHeader) { ... }
}
```

`createCheckoutSession`: build `SessionCreateParams` with mode `PAYMENT`, one line item for `totalAmount` converted to the currency's minor unit (multiply by 100 for a zero-decimal-exempt currency like TZS/USD — `stripe-java`'s `PriceData.builder().setUnitAmount(...)` takes a `long` in minor units), `client_reference_id(referenceNo)`, `success_url(stripeProperties.getSuccessUrl())`, `cancel_url(stripeProperties.getCancelUrl())`; call `Session.create(params)`; return `new CheckoutSessionResult(session.getId(), session.getUrl())`.

`parseEvent`: call `Webhook.constructEvent(payload, signatureHeader, stripeProperties.getWebhookSecret())`, catching `SignatureVerificationException` and rethrowing as `new BadRequestException("Invalid Stripe webhook signature")`. Deserialize the event's data object as a `Session` (for `checkout.session.*` events) to read `getId()` and, once completed, `getPaymentIntent()`; return `new WebhookEventResult(event.getType(), session.getId(), session.getPaymentIntent())`. For any event whose data object isn't a `Session`, return `new WebhookEventResult(event.getType(), null, null)` — `PaymentService` (Task 5) treats a null `sessionId` as nothing-to-do.

- [ ] **Step 6: Compile**

Run: `./mvnw -q -o compile`
Expected: PASS (no test to run yet — this task adds a collaborator with no caller until Task 4/5).

- [ ] **Step 7: Commit**

```bash
git add pom.xml src/main/resources/application.properties src/main/java/com/application/e_wallet/payment/config/StripeProperties.java src/main/java/com/application/e_wallet/payment/gateway/
git commit -m "feat: add Stripe SDK dependency and StripeGatewayClient adapter"
```

---

### Task 4: `PaymentService.createCheckoutSession`

**Files:**
- Create: `src/main/java/com/application/e_wallet/payment/dto/CreateCheckoutSessionRequest.java`
- Create: `src/main/java/com/application/e_wallet/payment/dto/CheckoutSessionResponse.java`
- Create: `src/main/java/com/application/e_wallet/payment/service/PaymentService.java`
- Test: `src/test/java/com/application/e_wallet/payment/service/PaymentServiceTest.java`

**Interfaces:**
- Consumes: `StripeGatewayClient` (Task 3), `TariffService.findApplicableTariff(String, BigDecimal): TariffEntity` (existing), `WalletRepository.findByUserIdAndWalletType`/`findByWalletType` (existing), `TransactionRepository.save` / `existsByReferenceNo` (existing).
- Produces: `PaymentService.createCheckoutSession(CreateCheckoutSessionRequest request, UUID userId): CheckoutSessionResponse` — consumed by `PaymentController` in Task 6.

```java
public record CreateCheckoutSessionRequest(
    @NotNull @DecimalMin(value = "1000.00", message = "Minimum top-up amount is 1,000 TZS") BigDecimal amount
) {}

public record CheckoutSessionResponse(String checkoutUrl, String transactionReference) {}
```

- [ ] **Step 1: Write the failing tests**

```java
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
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q -o test -Dtest=PaymentServiceTest`
Expected: FAIL with "class PaymentService not found" (or compile error, since it doesn't exist yet).

- [ ] **Step 3: Implement `PaymentService.createCheckoutSession`**

```java
@Service
@RequiredArgsConstructor
public class PaymentService {
    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final TariffService tariffService;
    private final StripeGatewayClient stripeGatewayClient;

    @Transactional
    public CheckoutSessionResponse createCheckoutSession(CreateCheckoutSessionRequest request, UUID userId) { ... }
}
```

Order of operations (matters — do not call Stripe before validation, and do not persist a transaction row until the Stripe call has actually succeeded, so a failed Stripe call leaves no orphan `PENDING` row):

1. Look up the customer's `CUSTOMER_MAIN` wallet via `walletRepository.findByUserIdAndWalletType(userId, WalletType.CUSTOMER_MAIN)`, `orElseThrow(() -> new BadRequestException("Customer wallet not found"))`.
2. If `customerWallet.getStatus() != WalletStatus.ACTIVE`, throw `new BadRequestException("Customer wallet is not active")`.
3. Look up the `PAYMENT_GATEWAY_CLEARING` wallet via `walletRepository.findByWalletType(...)`, `orElseThrow(() -> new IllegalStateException("Payment gateway clearing wallet not initialized"))` (same style as `TransactionService`'s "Commission wallet not initialized").
4. `TariffEntity tariff = tariffService.findApplicableTariff(TransactionType.TOPUP_STRIPE.name(), request.amount())`; `fee = tariff.getFee()`; `totalAmount = request.amount().add(fee)`.
5. Generate `referenceNo` with the same `"TXN-" + yyyyMMdd + "-" + 8-char-uppercase-hex` scheme and collision retry loop as `TransactionService.generateReferenceNo()` (private method, duplicated here — this mirrors the existing duplication between `TransactionService` and `LedgerService`, not a new pattern).
6. Call `stripeGatewayClient.createCheckoutSession(totalAmount, "usd", referenceNo)` — the literal `"usd"`, never `customerWallet.getCurrency()` (spec decision 12; see Global Constraints).
7. Build and save a `TransactionEntity`: `referenceNo`, `senderWallet = clearingWallet`, `receiverWallet = customerWallet`, `amount = request.amount()`, `fee`, `commission = BigDecimal.ZERO`, `transactionType = TransactionType.TOPUP_STRIPE.name()`, `status = TransactionStatus.PENDING.name()`, `externalReference = result.sessionId()`.
8. Return `new CheckoutSessionResponse(result.checkoutUrl(), referenceNo)`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -q -o test -Dtest=PaymentServiceTest`
Expected: PASS (all three tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/application/e_wallet/payment/dto/ src/main/java/com/application/e_wallet/payment/service/PaymentService.java src/test/java/com/application/e_wallet/payment/service/PaymentServiceTest.java
git commit -m "feat: add PaymentService.createCheckoutSession"
```

---

### Task 5: `PaymentService.handleWebhookEvent`

**Files:**
- Modify: `src/main/java/com/application/e_wallet/payment/service/PaymentService.java`
- Modify: `src/test/java/com/application/e_wallet/payment/service/PaymentServiceTest.java`

**Interfaces:**
- Consumes: `LedgerService.postJournal(LedgerTransactionType, String, UserEntity, List<JournalLineRequest>): TransactionJournalEntity` (existing), `UserRepository.findById(UUID): Optional<UserEntity>` (existing), `WalletRepository.findByUserIdAndWalletTypeForUpdate`/`findByWalletTypeForUpdate` (existing), `TransactionRepository.findByExternalReference` (Task 2).
- Produces: `PaymentService.handleWebhookEvent(String payload, String signatureHeader): void` — consumed by `PaymentController` in Task 6.

There is no authenticated human actor in a webhook request, but `LedgerService.postJournal`'s `createdBy` parameter is `UserEntity`, non-null. Use the same system user that owns every singleton company wallet (id `11111111-1111-1111-1111-111111111111`, seeded in V26, already used as the owner of `PAYMENT_GATEWAY_CLEARING` in Task 2's migration): add `private static final UUID SYSTEM_USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");` to `PaymentService` and fetch it via `userRepository.findById(SYSTEM_USER_ID)`.

- [ ] **Step 1: Write the failing tests**

```java
    @Mock private LedgerService ledgerService;
    @Mock private UserRepository userRepository;
    // add both to the existing @InjectMocks PaymentService's constructor dependencies

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
        when(transactionRepository.findByExternalReference("cs_test_404")).thenReturn(Optional.empty());

        paymentService.handleWebhookEvent("payload", "sig");

        verifyNoInteractions(ledgerService);
    }

    @Test
    void handleWebhookEventIsIdempotentOnAlreadyCompletedTransaction() {
        TransactionEntity alreadyDone = TransactionEntity.builder()
                .status(TransactionStatus.COMPLETED.name()).externalReference("cs_test_1").build();
        when(stripeGatewayClient.parseEvent("payload", "sig"))
                .thenReturn(new WebhookEventResult("checkout.session.completed", "cs_test_1", "pi_1"));
        when(transactionRepository.findByExternalReference("cs_test_1")).thenReturn(Optional.of(alreadyDone));

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
        when(transactionRepository.findByExternalReference("cs_test_2")).thenReturn(Optional.of(pending));

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
        when(transactionRepository.findByExternalReference("cs_test_3")).thenReturn(Optional.of(pending));
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
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q -o test -Dtest=PaymentServiceTest`
Expected: FAIL — `handleWebhookEvent` doesn't exist yet.

- [ ] **Step 3: Implement `PaymentService.handleWebhookEvent`**

```java
@Transactional
public void handleWebhookEvent(String payload, String signatureHeader) { ... }
```

1. `WebhookEventResult event = stripeGatewayClient.parseEvent(payload, signatureHeader);`
2. If `event.eventType()` is neither `"checkout.session.completed"` nor `"checkout.session.expired"`, return.
3. If `event.sessionId() == null`, return.
4. `Optional<TransactionEntity> maybeTx = transactionRepository.findByExternalReference(event.sessionId());` — if empty, return.
5. `TransactionEntity transaction = maybeTx.get();` if `!transaction.getStatus().equals(TransactionStatus.PENDING.name())`, return (idempotency guard).
6. If `event.eventType().equals("checkout.session.expired")`: `transaction.setStatus(TransactionStatus.FAILED.name()); transactionRepository.save(transaction); return;`
7. Otherwise (`checkout.session.completed`):
   - Lock the customer wallet: `walletRepository.findByUserIdAndWalletTypeForUpdate(transaction.getReceiverWallet().getUser().getId(), WalletType.CUSTOMER_MAIN)`, `orElseThrow(IllegalStateException)`.
   - Lock the clearing wallet: `walletRepository.findByWalletTypeForUpdate(WalletType.PAYMENT_GATEWAY_CLEARING)`, `orElseThrow(IllegalStateException)`.
   - `amount = transaction.getAmount(); fee = transaction.getFee(); total = amount.add(fee);`
   - `clearingWallet.setBalance(clearingWallet.getBalance().add(total));`
   - `customerWallet.setBalance(customerWallet.getBalance().add(amount));`
   - Build `lines = [new JournalLineRequest(clearingWallet.getId(), EntryType.DEBIT, total), new JournalLineRequest(customerWallet.getId(), EntryType.CREDIT, amount)]`.
   - If `fee.compareTo(BigDecimal.ZERO) > 0`: lock and credit the `REVENUE` wallet the same way `TransactionService.cashOut` does, and add a third `CREDIT` line for `fee`.
   - `UserEntity systemUser = userRepository.findById(SYSTEM_USER_ID).orElseThrow(() -> new IllegalStateException("System user not found"));`
   - `ledgerService.postJournal(LedgerTransactionType.TOPUP_STRIPE, "Stripe top-up for " + transaction.getReferenceNo(), systemUser, lines);`
   - `walletRepository.save(customerWallet); walletRepository.save(clearingWallet);` (and the revenue wallet, if credited).
   - `transaction.setStatus(TransactionStatus.COMPLETED.name()); transaction.setExternalReference(event.paymentIntentId()); transactionRepository.save(transaction);`

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -q -o test -Dtest=PaymentServiceTest`
Expected: PASS (all tests from Task 4 and Task 5).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/application/e_wallet/payment/service/PaymentService.java src/test/java/com/application/e_wallet/payment/service/PaymentServiceTest.java
git commit -m "feat: add PaymentService.handleWebhookEvent with idempotent ledger posting"
```

---

### Task 6: Controller, security wiring, and manual end-to-end verification

**Files:**
- Create: `src/main/java/com/application/e_wallet/payment/controller/PaymentController.java`
- Modify: `src/main/java/com/application/e_wallet/security/SecurityConfig.java`

**Interfaces:**
- Consumes: `PaymentService.createCheckoutSession`/`handleWebhookEvent` (Task 4/5), `AuthenticatedUser.userId()` (existing), `ApiResponse<T>` (existing).

- [ ] **Step 1: Add the webhook path to `SecurityConfig`'s `permitAll()` list**

Add `"/api/v1/payments/stripe/webhook"` to the existing `.requestMatchers(...).permitAll()` list in `SecurityConfig`, in the same block as `/api/v1/auth/login` etc. — leave every other route under `/api/v1/payments/**` behind the default `.anyRequest().authenticated()`.

- [ ] **Step 2: Implement `PaymentController`**

```java
@RestController
@RequestMapping("/api/v1/payments/stripe")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/checkout-session")
    @PreAuthorize("hasAuthority('WALLET_TO_BANK_TRANSFER')")
    public ResponseEntity<ApiResponse<CheckoutSessionResponse>> createCheckoutSession(
            @Valid @RequestBody CreateCheckoutSessionRequest request, Authentication authentication) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        CheckoutSessionResponse response = paymentService.createCheckoutSession(request, principal.userId());
        return ResponseEntity.ok(ApiResponse.<CheckoutSessionResponse>builder()
                .timestamp(Instant.now()).status(HttpStatus.OK.value())
                .message("Checkout session created.").data(response).build());
    }

    @PostMapping("/webhook")
    public ResponseEntity<Void> webhook(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String signature) {
        paymentService.handleWebhookEvent(payload, signature);
        return ResponseEntity.ok().build();
    }
}
```

(No dedicated controller test — this codebase has no existing controller-level test for any endpoint, e.g. `TransactionController`; following that precedent, this controller is exercised by the manual Postman verification below, not an automated test.)

- [ ] **Step 3: Compile**

Run: `./mvnw -q -o compile`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/application/e_wallet/payment/controller/PaymentController.java src/main/java/com/application/e_wallet/security/SecurityConfig.java
git commit -m "feat: expose Stripe checkout-session and webhook endpoints"
```

- [ ] **Step 5: Manual end-to-end verification (Postman + Stripe CLI)**

This is the verification the spec calls for before any frontend is touched — not an automated test, a checkable manual run:

1. Start the app locally (`./mvnw spring-boot:run`) with real test-mode `.env` values from the Stripe setup steps already completed.
2. In a separate terminal: `stripe listen --forward-to localhost:8080/api/v1/payments/stripe/webhook` — note the `whsec_...` it prints and confirm it matches `STRIPE_WEBHOOK_SECRET`.
3. In Postman, log in as a `CUSTOMER` user to get a JWT, then `POST /api/v1/payments/stripe/checkout-session` with `{ "amount": 5000.00 }`. Confirm the response contains a `checkoutUrl`.
4. Open `checkoutUrl` in a browser, pay with `4242 4242 4242 4242` / any future expiry / any CVC.
5. Confirm in the terminal running `stripe listen` that a `checkout.session.completed` event was forwarded and your app returned `200`.
6. `GET` the customer's wallet balance (existing endpoint) and confirm it increased by exactly the amount paid.
7. Re-send the same event (`stripe events resend <event_id>`, or trigger `stripe trigger checkout.session.completed` is a *different* session — to test redelivery of the *same* event, use `stripe listen`'s terminal, which lets you replay the last delivery) and confirm the wallet balance does **not** change a second time, and no second journal is posted.
8. Create a second checkout session, abandon it (don't pay), and either wait for Stripe's test-mode expiry or use `stripe trigger checkout.session.expired` — confirm that transaction ends up `FAILED` with no wallet change.

Expected: all eight steps behave as described. If step 7 shows a second balance increase, the idempotency guard in Task 5 has a bug — stop and fix before proceeding.

- [ ] **Step 6 (optional): Check whether the real Stripe test account supports `tzs`**

Before the above steps, or alongside them: `curl https://api.stripe.com/v1/checkout/sessions -u <STRIPE_SECRET_KEY>: -d "mode=payment" -d "line_items[0][price_data][currency]=tzs" -d "line_items[0][price_data][product_data][name]=test" -d "line_items[0][price_data][unit_amount]=500000" -d "line_items[0][quantity]=1" -d "success_url=https://example.com/success"`. If this returns a `200` with a session object, `tzs` is accepted by this account — swap the hardcoded `"usd"` in `PaymentService`/`StripeGatewayClientImpl` for `"tzs"` and re-run Task 4/5's tests with the literal changed. If it returns an error (e.g. `currency_not_supported`), keep `"usd"` as-is — no code change needed.

---

## Self-Review Notes

- **Spec coverage:** every "Decisions made in discussion" item (1–11) and the full "Flow" section map onto Tasks 2–6; "Data model changes" is Task 2; "Module layout" is Tasks 3–6. The spec's non-goals (Connect payouts, C2C transfer, raw Elements, live keys) are deliberately absent from every task.
- **Review Focus:** all five items have an owning task and test (redelivery and signature → Task 5 tests / Task 6 manual; expiry → Task 5 test; inactive wallet and tariff-band miss → Task 4 tests).
- **Type consistency:** `StripeGatewayClient`, `CheckoutSessionResult`, `WebhookEventResult`, `CreateCheckoutSessionRequest`, `CheckoutSessionResponse` are defined once (Tasks 3–4) and referenced identically by name in every later task.
- A third implementation gap not caught by the design-doc review surfaced while drafting Task 5: `LedgerService.postJournal`'s `createdBy` is non-null, but a webhook has no authenticated human actor. Resolved the same way as the doc's other two gaps — reusing the existing system user that already owns every singleton company wallet — and recorded in Task 5 rather than re-editing the spec, since it's an implementation detail, not a product decision.
