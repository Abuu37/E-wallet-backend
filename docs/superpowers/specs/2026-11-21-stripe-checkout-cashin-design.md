    # Stripe Checkout Cash-In Integration — Design

## Intent and why

For a presentation, WalletPesa needs to demonstrate connecting to a real
payment gateway. Stripe (test mode) stands in for a card/bank payment
rail on the **deposit (cash-in)** side only. The existing agent-based
cash-in/cash-out system is the production reality for this market and is
left untouched; Stripe is a second, parallel way for a customer to top up
their own wallet directly, without an agent.

A React frontend already exists but is not yet wired to any backend API.
The plan is: build the endpoints, verify them end-to-end with Postman and
the Stripe CLI (checkout session creation, test-card payment, webhook
delivery, wallet balance change) with no frontend involved, and only
then connect the existing React UI to the same endpoints.

## Decisions made in discussion (yours, recorded so they aren't re-litigated)

1. **Stripe touches cash-in only.** Cash-out stays exactly as it is today
   (agent float → customer, agent-fronted cash) and is not touched by
   Stripe. A real "withdraw via Stripe" would require onboarding each
   customer as a Stripe Connect account (KYC, bank linking) and Stripe's
   payout rails don't cover this market well — not worth building for a
   demo, and not how real cash-out works here anyway.
2. **Customer-to-customer wallet transfer is out of scope for this
   document.** It doesn't touch Stripe or any wallet type added here;
   design it separately later. (Note for that future work: a
   `WALLET_TO_WALLET_TRANSFER` permission already exists, seeded to the
   `CUSTOMER` role since V6, currently unused by any endpoint.)
3. **New singleton wallet `PAYMENT_GATEWAY_CLEARING`**, rather than
   crediting `COMPANY_MASTER_ACCOUNT` directly. Stripe-sourced funds are
   kept in their own internal bookkeeping wallet so they can be
   reconciled against Stripe's own reports independently of
   agent-minted float or other company funds.
4. **Stripe Checkout (hosted page), not raw PaymentIntents + Elements.**
   The backend creates a Checkout Session and returns its URL; no
   client-side Stripe integration code is needed. The React UI, once
   wired up, just redirects the browser to that URL — same contract
   whether the caller is Postman or the real frontend.
5. **The customer initiates this themselves — no agent is involved.** No
   `AGENT_FLOAT` or `AGENT_COMMISSION` movement, no commission entries,
   for this transaction type.
6. **Reuse the existing `WALLET_TO_BANK_TRANSFER` permission** to guard
   the "create checkout session" endpoint, instead of adding a new one.
   It's already seeded to the `CUSTOMER` role (V6) and unused by any
   endpoint today, and "money moving between the customer's wallet and
   an external bank/card rail" is exactly what this is — my call, to
   avoid a redundant permission for the same concept.
7. **New `external_reference` column on `TransactionEntity`**, storing
   the Stripe Checkout Session id (and later overwritten with the
   PaymentIntent id once paid). This closes the gap found while
   reviewing the ledger: there was previously no way to tie an internal
   transaction back to the external gateway event, which is required
   both for webhook idempotency (Stripe can redeliver the same event)
   and for reconciliation.
8. **This path is asynchronous; existing cash-in/cash-out are not.**
   Creating the checkout session writes the `TransactionEntity` as
   `PENDING` immediately. Nothing moves in the ledger or any wallet at
   that point. Only the webhook, once it confirms payment, posts the
   ledger journal, moves wallet balances, and flips the transaction to
   `COMPLETED`. A failure/expiry event flips it to `FAILED` with no
   movement. `TransactionStatus.PENDING` already exists in the enum
   today but nothing currently writes it — this is its first real use.
9. **The webhook endpoint is public** (no JWT) — Stripe calls it
   directly, with no user session. It's added to `SecurityConfig`'s
   `permitAll()` list; its security comes from verifying the
   `Stripe-Signature` header against the raw request body inside the
   handler, not from Spring Security authentication.
10. **New `TOPUP_STRIPE` value on both `TransactionType` and
    `LedgerTransactionType`**, not a reuse of `CASH_IN`. The existing
    `CASH_IN` tariff row bakes in a 200 TZS `agent_commission`, which
    doesn't apply here since there's no agent — a distinct transaction
    type and its own tariff row are needed so the amount-band lookup
    doesn't silently charge a phantom agent commission.
11. **Test mode only, end to end.** Stripe secret key as
    `STRIPE_SECRET_KEY` (`sk_test_...`) via the same `.env` import
    pattern already used for `DB_URL`/`JWT_SECRET`; Stripe CLI
    (`stripe listen --forward-to localhost:8080/api/v1/payments/stripe/webhook`)
    for local webhook delivery during development and the live demo; no
    live key anywhere in this work.
12. **Stripe always charges in USD, hardcoded — not the wallet's `TZS`
    currency.** Stripe's actual supported presentment currencies depend
    on the Stripe account's country/configuration, and this couldn't be
    confirmed to include `TZS` without the live account in hand. USD is
    accepted by every Stripe account in test mode, with no risk of an
    unsupported-currency failure during the live demo. The wallet's
    `currency` field stays `TZS`, unchanged — this is a nominal 1:1
    amount shown to the customer on Stripe's page, not a real FX
    conversion, and is an explicit demo simplification, not a claim of
    production-correct multi-currency handling.

## Flow, end to end

1. Authenticated customer calls
   `POST /api/v1/payments/stripe/checkout-session` with `{ amount }`.
2. `PaymentService`:
   - Looks up the `TOPUP_STRIPE` tariff for that amount (fee only —
     `agentCommission` is always 0 for this type).
   - Creates a `TransactionEntity`: status `PENDING`, type
     `TOPUP_STRIPE`, receiver = the customer's `CUSTOMER_MAIN` wallet,
     amount, fee. `senderWallet` is `@ManyToOne(optional = false)` /
     `NOT NULL` on this entity, so it must be set even though no money
     has moved yet — set it to the `PAYMENT_GATEWAY_CLEARING` wallet at
     creation time. This is just the FK identifying the eventual
     source, not a claim that funds already moved; `status` (`PENDING`
     → `COMPLETED`/`FAILED`) is what actually communicates whether the
     payment landed. No schema change needed — the column stays
     `NOT NULL`.
   - Calls the Stripe SDK to create a Checkout Session for
     `amount + fee` in `usd` (hardcoded — decision 12, not the wallet's
     `TZS`), with `client_reference_id` set to the new transaction's
     `referenceNo` (so the webhook can find it again) and configured
     success/cancel URLs.
   - Stores the session id on the transaction's `external_reference`.
3. Returns `{ checkoutUrl, transactionReference }`. Caller (Postman, or
   later the React "Top Up" button) opens/redirects to `checkoutUrl`.
4. Customer pays on Stripe's hosted page with a test card
   (`4242 4242 4242 4242`, any future expiry/CVC).
5. Stripe redirects the browser to the configured `success_url` — this
   is cosmetic only, not the source of truth.
6. Stripe fires `checkout.session.completed` to
   `POST /api/v1/payments/stripe/webhook` (also listen for
   `checkout.session.expired` to mark failures).
7. Webhook handler:
   - Verifies the signature against the raw body using
     `STRIPE_WEBHOOK_SECRET`; rejects anything that doesn't verify.
   - Looks up the `TransactionEntity` by `external_reference`. If not
     found, or already `COMPLETED`/`FAILED`, respond 200 and do nothing
     further — this is the idempotency guard against redelivery.
   - On `checkout.session.completed`, post one journal with these lines
     (amounts always balance: `amount + fee` on each side):

     | Wallet | Entry type | Balance code |
     |---|---|---|
     | `PAYMENT_GATEWAY_CLEARING` | DEBIT | `.add(amount + fee)` |
     | `CUSTOMER_MAIN` | CREDIT | `.add(amount)` |
     | `REVENUE` (only if `fee > 0`) | CREDIT | `.add(fee)` |

     **This is deliberately the `MINT_FLOAT` pattern, not the
     `CASH_IN`/`CASH_OUT` pattern.** `PAYMENT_GATEWAY_CLEARING` is
     seeded at balance `0` with the same `CHECK (balance >= 0)`
     constraint as every wallet (V27); money is entering the system
     from outside (a card), not moving between two already-funded
     internal wallets. So — exactly like `TreasuryService.mint()`
     calls `.add()` on both `ESCROW_BANK` (DEBIT) and
     `COMPANY_MASTER_ACCOUNT` (CREDIT) — the DEBIT line here is a label
     chosen only so `LedgerService.postJournal`'s debit-total ==
     credit-total check passes; the wallet's stored balance still
     **increases**. If the webhook handler instead calls `.subtract()`
     on `PAYMENT_GATEWAY_CLEARING` (the `CASH_IN`/`CASH_OUT` reading of
     "debit"), the very first successful payment drives it from `0` to
     negative, the CHECK constraint rejects it, and the wallet credit
     silently fails after the customer has already paid on Stripe. Do
     not infer the arithmetic from the DEBIT/CREDIT English words alone
     — this codebase uses those words inconsistently with respect to
     actual balance mutation between the `CASH_IN`/`CASH_OUT` precedent
     and the `MINT_FLOAT` precedent, and this feature follows the
     latter.

     Then: flip the transaction to `COMPLETED`; overwrite
     `external_reference` with the PaymentIntent id from the event
     payload.
   - On `checkout.session.expired`: flip the transaction to `FAILED`, no
     wallet or ledger movement.
8. Caller re-fetches the existing wallet/dashboard GET endpoint to show
   the updated balance — no new read endpoint needed.

## Data model changes

New Flyway migration (next available: V48):

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
-- Partial unique index so existing/future NULL rows (every non-Stripe
-- transaction) don't collide.
ALTER TABLE transactions ADD COLUMN external_reference VARCHAR(100);
CREATE UNIQUE INDEX uk_transactions_external_reference
    ON transactions (external_reference)
    WHERE external_reference IS NOT NULL;

-- New tariff band: no agent commission, since no agent is involved.
INSERT INTO tariffs (id, transaction_type, min_amount, max_amount, fee, agent_commission, active)
VALUES (gen_random_uuid(), 'TOPUP_STRIPE', 1000.00, 100000000.00, 0.00, 0.00, true);
```

Java enum additions:
- `WalletType`: add `PAYMENT_GATEWAY_CLEARING`.
- `TransactionType`: add `TOPUP_STRIPE`.
- `LedgerTransactionType`: add `TOPUP_STRIPE`.

No new permission migration — decision 6 reuses `WALLET_TO_BANK_TRANSFER`,
already granted to `CUSTOMER`.

## Module layout

New `com.application.e_wallet.payment` package, parallel to `transaction`
and `treasury` — this owns external-integration concerns (SDK config,
webhook signature verification, Stripe-specific DTOs) that don't belong
inside the `transaction` module:

- `PaymentController`:
  - `POST /api/v1/payments/stripe/checkout-session`,
    `@PreAuthorize("hasAuthority('WALLET_TO_BANK_TRANSFER')")`, wrapped
    in the existing `ApiResponse<T>` pattern.
  - `POST /api/v1/payments/stripe/webhook`, no `@PreAuthorize` — added to
    `SecurityConfig`'s `permitAll()` list alongside the existing public
    routes.
- `PaymentService` — `createCheckoutSession(...)` and
  `handleWebhookEvent(...)`, following the same structure as
  `TransactionService.cashIn`/`cashOut` (repository lookups, tariff
  lookup, `LedgerService.postJournal`, `TransactionRepository.save`).
- `StripeConfig` — a `@PostConstruct` or `@Bean` setting
  `Stripe.apiKey` from `${STRIPE_SECRET_KEY}`, plus a properties holder
  for `${STRIPE_WEBHOOK_SECRET}`.
- `dto` — `CreateCheckoutSessionRequest(amount)`,
  `CheckoutSessionResponse(checkoutUrl, transactionReference)`.

New `pom.xml` dependency: `com.stripe:stripe-java` (latest stable at
implementation time).

New `.env` entries, following the existing import pattern
(`spring.config.import=optional:file:.env[.properties]`):
`STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`, `STRIPE_SUCCESS_URL`,
`STRIPE_CANCEL_URL`.

## Non-goals

- No Stripe Connect / payout simulation for cash-out — decision 1.
- No customer-to-customer transfer work — decision 2.
- No raw PaymentIntents/Elements client-side integration — decision 4.
- No live/production Stripe keys at any point — decision 11.
- No change to the existing agent `CASH_IN`/`CASH_OUT` flows, their
  wallets, or their tariff rows.
- No fix to the broader (pre-existing, unrelated) gap that
  `TransactionEntity` and `TransactionJournalEntity` aren't linked by a
  foreign key — only the narrower `external_reference` column needed for
  this feature is added here.
- No currency column or per-line purpose tag on the ledger — noted as
  possible future improvements during earlier review, not needed for
  this feature.

## What's left to the builder

- Exact `stripe-java` SDK call shapes (`Session.create(...)` params) —
  depends on the pinned SDK version at implementation time.
- Exact DTO record field ordering/naming beyond what's specified above.
- Migration version number if V48 is taken by other work landing first.
- Whether to also listen for other Checkout/PaymentIntent failure event
  types beyond `checkout.session.expired`.
