# Flagship e-Wallet Program (Web now, Mobile next) — Design

Status: **draft for review** (builder-checked; your answers of 23 Nov recorded). One confirmation is left: approach C (see last section). Everything marked *(my call)* is a proposal you can overturn.

All technical design (architecture, database, diagrams, Docker, Git, CI) lives in this one document; diagrams are Mermaid and render on GitHub and in IntelliJ. Companion: the visual plan (personas, roadmap, data map, user stories, journeys, code audit) lives on the Design canvas "e-Wallet Product Plan". This document is the written design that implementation plans are built from; the canvas is the picture of it.

## Intent and why

Turn the existing e-wallet (Spring Boot backend, React frontend, PostgreSQL `e_walletDB`) into a flagship web product for three audiences (customers, agents, back-office staff) that feels instant and certain to use and is provably correct with money. The same backend then serves a mobile app with no rewrite.

**Why:** a mind-blowing e-wallet that works as a **portfolio project and a pitch demo first**, and later handles real money *(your answer, 23 Nov)*. So the near-term bar is: every flow can be shown end to end, convincingly, with nothing faked on screen, while the money core is already built to a standard that real money won't force a rewrite of. Outside services come from **Plaid in Sandbox** for now (KYC and a bank top-up demo).

## Where we start (audit of GitHub `main`, 23 Nov 2026)

Backend `E-wallet-backend@84f2bc1` (20 Nov), frontend `E-wallet-frontend@c861197` (5 Nov).

| Area | State | Notes |
|---|---|---|
| Onboarding & auth | Built | Customer self-registration (NIDA, DOB, address), OTP 6 digits / 5 min / 5 attempts, set/change/forgot/reset PIN, JWT 15 min + 7-day refresh, sessions, logout |
| Roles, staff, permissions | Built | One role per user, permissions grouped into `modules`, staff CRUD, agents with business/TIN/region/district |
| Wallets & ledger | Built | 7 `WalletType`s; `PESSIMISTIC_WRITE` locks; `LedgerService.postJournal` rejects unbalanced journals |
| Treasury & float | Built | Mint, commission top-up, agent float requests with approve/reject; 27 tests |
| Agent cash-in / cash-out | Partial | Correct journals and `transactions` rows; **customer PIN passes through the agent's request**; no tests; no idempotency |
| Tariffs | Partial | Bands + applicable lookup; no management endpoints/UI |
| Customer money features | Missing | No P2P transfer, no customer history/receipts, no notifications |
| Frontend | Partial | Auth screens call the API. All 10 staff dashboard pages and both customer pages read hard-coded `data.ts`. Frontend calls `/auth/customer-registration` (backend serves `/customers/register`) and uses role `CASHIER` (backend renamed to `AGENT` in V16) |

## Decisions already made (carried forward from earlier specs, not re-opened)

1. PIN is the only credential; no passwords anywhere.
2. One role per user.
3. The dev database may be dropped and rebuilt; migrations are forward-only Flyway files.
4. `transactions` is the customer-facing record; `transaction_journal`/`_line` is the ledger of truth. Every money movement writes both, in one DB transaction.
5. Business timezone is `Africa/Dar_es_Salaam`; currency TZS.
6. Live dashboards poll; no WebSocket infrastructure unless a feature needs push.

**Decided on 23 Nov (your answers)**

7. Audience: portfolio and pitch demo first, real money later.
8. Outside provider: Plaid, in Sandbox. KYC uses Plaid Identity Verification.
9. Withdrawals are customer-initiated: the customer confirms on their own phone with their own PIN, and the agent never sees it.
10. Limits are tiered by KYC result: unverified and verified customers get different limits (demo amounts in Phase 1).

## Goals

- A customer can register, get funded at an agent (cash-in), send, withdraw and prove a payment without staff help, in under 3 minutes for first use and 3 taps + PIN for a send.
- Every screen a user sees shows real data from the API.
- Money cannot be created, lost or double-posted: journals balance, retries are safe, issuance needs two people, reconciliation runs daily.
- Pitch-ready: a scripted demo (register → verify with Plaid → add money → send → withdraw at agent → staff sees it live) runs end to end on seeded data.
- One versioned API (`/api/v1`) documented well enough that a mobile client needs no backend changes beyond device/push endpoints.

## Non-goals

- Loans (the frontend page has no backend and no design; hide it until it has a spec of its own).
- Real bank transfers, card or mobile-money top-up, merchant payouts, multi-currency. Funding is agent cash-in, plus a Plaid Sandbox bank top-up that is clearly labelled as a demo.
- Licensing, regulatory approval and production KYC/payment contracts for real money in Tanzania. Planned for later; the design keeps providers swappable.
- Replacing the existing ledger, wallet model, or auth model.

## Anti-goals (would be failure even if it "works")

- Any flow where one party can see or enter another party's PIN.
- A dashboard that looks live but shows sample data.
- A feature that moves money without a balanced journal, a `transactions` row, and a test proving both.
- A web-only shortcut that the mobile app would have to undo.
- A demo flow that could be mistaken for real money movement (Sandbox flows are labelled on screen and on receipts).

## Constraints

- Stack stays: Spring Boot 4.2 / JPA / Flyway / PostgreSQL; React 19 / Vite / TypeScript / Tailwind 4 / React Router 7 / Formik + Yup.
- Money columns stay `NUMERIC`; balances change only inside the posting path that also writes the journal.
- Work lands as small, separately reviewable specs + plans per phase (this document is the umbrella; each phase gets its own spec in this folder before it is built).
- Test-first for anything that moves money (superpowers TDD).

## Approaches

Three orderings were considered. All end in the same place; they differ in what is true after the first few weeks.

**A. Harden first.** Fix the cash-out PIN flow, idempotency, tests and frontend drift; then build customer features; then polish.
*Trade-off:* safest; little that is new to show for the first stretch.

**B. Show first.** Wire dashboards to real data and build send-money with a polished customer UI; harden later.
*Trade-off:* fastest to impress; builds new money flows on top of known gaps (untested postings, no idempotency, PIN exposure) and risks demoing something unsafe.

**C. Secure core, then visible wins (recommended, my call).** A short Phase 0 that fixes only what touches money safety and drift (PIN flow, idempotency, tests, role/path drift), immediately followed by the most visible customer win (send money + history + receipts), with dashboards wired in parallel by page.
*Why:* the hardening items are small and every later money feature reuses them (idempotency, lock order, test fixtures), so doing them first is cheaper than retrofitting. Dashboard wiring is independent per page, so it runs alongside without blocking.

**In plain words:** all three reach the same finished app. They only differ in what gets built first. A fixes the hidden safety problems before anything new. B builds the impressive screens first and fixes safety later. C does a short safety fix (about a week and a half), then goes straight to the impressive parts.

**Picked:** C, my recommendation for a portfolio and pitch. A reviewer or investor who tries to break the demo (double-tapping *Send*, asking where the customer PIN goes) gets a good answer, and the visible features still arrive early. *Awaiting your confirmation.*

### Delivery timeline (approach C, indicative)

Assumes a start on Monday 30 Nov 2026 and a small team. Dates move once the approach and team size are confirmed.

```mermaid
gantt
    title Flagship e-wallet: indicative timeline
    dateFormat YYYY-MM-DD
    axisFormat %d %b
    section Phase 0 Connect and harden
    Withdrawal without PIN sharing   :p0a, 2026-11-30, 5d
    Idempotency and lock order       :p0b, 2026-11-30, 5d
    TransactionService tests         :p0c, after p0a, 4d
    Drift fixes and API client       :p0d, 2026-11-30, 4d
    Dashboards on real data          :p0e, after p0d, 10d
    Docker, CI, OpenAPI              :p0f, 2026-12-07, 5d
    section Phase 1 Customer money
    Lookup, quote, transfer          :p1a, after p0c, 8d
    History, receipts, beneficiaries :p1b, after p1a, 7d
    Plaid KYC and tiered limits      :p1d, after p1a, 6d
    Plaid bank top-up demo           :p1e, after p1d, 4d
    Customer web screens             :p1c, after p1a, 12d
    section Phase 2 Agent experience
    Agent portal                     :p2, after p1b, 15d
    section Phase 3 Back office trust
    Maker-checker, tariffs, audit    :p3a, after p2, 12d
    Reconciliation, customer 360     :p3b, after p2, 12d
    section Phase 4-5
    Delight features                 :p4, after p3a, 20d
    Mobile app                       :p5, after p4, 30d
```

## Design

### Phase 0 — Connect and harden

**0.1 Withdrawal without PIN sharing.** Replace `customerPin` in the agent's cash-out request.
Decided model *(your answer, 23 Nov)*: **customer-initiated withdrawal** — the customer enters the agent's float account number and the amount in their own app, sees the fee, and confirms with their own PIN. The agent's screen shows the confirmed withdrawal (polling) and the agent pays cash. Posting legs stay exactly as today's `cashOut`. Alternative: agent-initiated request that the customer approves in-app within 2 minutes (better at the counter, needs a pending state and expiry).

Proposed customer-initiated withdrawal:

```mermaid
sequenceDiagram
    autonumber
    actor C as Customer (own phone)
    actor A as Agent
    participant API as Backend
    participant L as LedgerService
    A->>C: Tells till number (agent float account)
    C->>API: GET /tariffs/quote?type=CASH_OUT&amount=
    API-->>C: fee and total
    C->>API: POST /transactions/withdraw (agent account, amount, own PIN, Idempotency-Key)
    API->>API: lock wallets in id order, check status, balance, limits
    API->>L: postJournal(CASH_OUT)
    L-->>API: balanced journal saved
    API-->>C: receipt TXN-…
    loop every 5 s
        A->>API: GET /agent/withdrawals?since=
    end
    API-->>A: confirmed withdrawal with reference
    A->>C: Pays cash, both see the same reference
```

Alternative considered and not chosen (agent-initiated, customer approves):

```mermaid
stateDiagram-v2
    [*] --> PENDING_APPROVAL: agent creates request
    PENDING_APPROVAL --> COMPLETED: customer approves with own PIN
    PENDING_APPROVAL --> REJECTED: customer declines
    PENDING_APPROVAL --> EXPIRED: no answer in 2 minutes
    COMPLETED --> [*]
    REJECTED --> [*]
    EXPIRED --> [*]
```

**0.2 Idempotency.** Every money `POST` (`cash-in`, `cash-out`/withdraw, transfer, treasury mint/top-up/approve) requires an `Idempotency-Key` header. New table `idempotency_keys(user_id, key, endpoint, request_hash, response_json, status, created_at)` with a unique `(user_id, key)`. Same key + same body → stored response; same key + different body → 409.

Request handling:

```mermaid
flowchart TD
    R[Money POST with Idempotency-Key] --> F{Key seen for this user?}
    F -- no --> I[Insert key as IN_PROGRESS] --> P[Run the posting] --> S[Store response, mark COMPLETED] --> OK[Return response]
    F -- yes --> H{Same request hash?}
    H -- no --> E409[409 Conflict]
    H -- yes --> ST{Status}
    ST -- COMPLETED --> RS[Return stored response]
    ST -- IN_PROGRESS --> E425[409 still processing, retry later]
```

**0.3 Lock ordering.** Wallets locked in one posting are fetched in ascending `id` order *(my call)* so two concurrent postings over the same wallets cannot deadlock.

**0.4 Tests.** `TransactionService` gets unit tests for every rule it enforces (statuses, PIN, balances, tariff legs, journal balance) and one integration test per flow asserting the journal sums to zero and balances moved as expected.

**0.5 Drift fixes.** Frontend registration posts to `/api/v1/customers/register`; frontend role `CASHIER` → `AGENT`; backend permission `CASHIER_VIEW` → `AGENT_VIEW` via a migration that renames it in place.

**0.6 Real data on screens.** One API client module in the frontend (base URL, bearer token, refresh-on-401 once, typed errors). Each dashboard page replaces its `data.ts` with calls to existing endpoints; where an endpoint doesn't exist yet the page shows an explicit "coming soon" state rather than sample data. Loans and any page without a backend are hidden from navigation.

**0.7 API docs.** OpenAPI generated from controllers (springdoc) and served in dev.

### Phase 1 — Customer money

- `GET /api/v1/wallets/lookup?phone=` → masked recipient name (e.g. "Amina W.") and whether they can receive. Rate-limited.
- `GET /api/v1/tariffs/quote?type=&amount=` → fee and total, used before every confirm screen.
- `POST /api/v1/transactions/transfer` (customer → customer): PIN, idempotency key, limits (below); journal debits sender amount + fee, credits receiver amount and revenue fee. New `TransactionType.TRANSFER` and `LedgerTransactionType.TRANSFER`; a tariff band seeded for it.
- `GET /api/v1/transactions/me` (paged, filter by type/date) and `GET /api/v1/transactions/{referenceNo}` (receipt; only parties to it may read it).
- Customer web: home (balance, recent activity, quick actions), send flow (recipient → amount with fee → PIN → receipt), history, receipt share, withdraw (0.1).
- **Limits, tiered by Plaid KYC** *(tiering your answer; amounts my call, demo values)*: enforced in one shared `LimitService` for transfer, withdraw and bank top-up, with values read from configuration so they can change without code.

  | Tier | When | Per transaction | Daily total | Max balance |
  |---|---|---|---|---|
  | Basic | `kyc_status` ≠ VERIFIED | 200,000 TZS | 500,000 TZS | 1,000,000 TZS |
  | Verified | `kyc_status` = VERIFIED | 3,000,000 TZS | 5,000,000 TZS | 10,000,000 TZS |

  These are demo numbers. Before real money they must be replaced with the regulator's tiers.
- **Receipts** *(my call)*: shared as an image or PDF generated in the app; no public verification link (that would be an unauthenticated endpoint and a separate security decision).
- Saved recipients (`beneficiaries`) *(my call to include here; cheap and high-delight)*.

Send-money flow:

```mermaid
sequenceDiagram
    autonumber
    actor S as Sender
    participant UI as Web app
    participant API as Backend
    participant DB as PostgreSQL
    S->>UI: Enter phone number
    UI->>API: GET /wallets/lookup?phone=
    API-->>UI: "Amina W.", can receive
    S->>UI: Enter amount
    UI->>API: GET /tariffs/quote?type=TRANSFER&amount=
    API-->>UI: fee, total
    S->>UI: Confirm with PIN
    UI->>API: POST /transactions/transfer + Idempotency-Key
    API->>DB: lock sender and receiver wallets (id order)
    API->>API: check PIN, statuses, balance, limits
    API->>DB: journal: sender DR amount+fee, receiver CR amount, revenue CR fee
    API->>DB: UPDATE wallets, INSERT transactions
    API-->>UI: receipt
    UI-->>S: Success screen, share receipt
```

### Phase 1 — Plaid integration (Sandbox)

Plaid is the outside provider for the demo *(your decision)*. Everything runs in Plaid **Sandbox**, the free test environment that uses test people and test banks. That is enough for a portfolio and a pitch, and nothing here assumes a live Plaid contract.

**What Plaid does and doesn't cover (checked against Plaid's docs, Nov 2026)**

| Plaid product | Use here | Coverage note |
|---|---|---|
| Identity Verification (IDV): ID document, selfie, data-source checks | **KYC for customers** *(your decision)* | Verifies end users in nearly 190 countries. Buying it in Production requires the company to be based in the US, Canada or UK. Tanzania isn't on Plaid's data-source list, so Tanzanian users would go through document + selfie checks. Sandbox accepts Plaid's test persona (Leslie Knope) and custom sample identities. |
| Link + Auth + Balance (bank linking) | **"Add money from bank" demo** *(my call)* | Banks in the US, Canada and Europe only. No Tanzanian banks. |
| Transfer (ACH/RTP) | Not used | US only. The demo simulates settlement through the ledger instead. |

**KYC flow (Plaid IDV)**

1. Customer taps *Verify identity*. Backend calls `/link/token/create` with `products: ["identity_verification"]`, the IDV `template_id` and `client_user_id` = the customer's `reference`.
2. Frontend opens Plaid Link (`react-plaid-link`). The customer picks country and document, photographs it and takes a selfie.
3. On `onSuccess`, the frontend sends the Link session ID to the backend. Success here does **not** mean the person passed.
4. Backend calls `/identity_verification/get` and maps Plaid's status to `customers.kyc_status`. When deployed, a `STATUS_UPDATED` webhook to `POST /api/v1/webhooks/plaid` triggers the same fetch. Locally there's no public URL for webhooks, so the backend fetches on step 3 and on a *Refresh status* tap.
5. Verified customers move to the higher limit tier straight away (below).

```mermaid
sequenceDiagram
    autonumber
    actor C as Customer
    participant UI as Web app
    participant API as Backend
    participant P as Plaid (Sandbox)
    participant DB as PostgreSQL
    C->>UI: Tap "Verify identity"
    UI->>API: POST /api/v1/kyc/link-token
    API->>P: /link/token/create (identity_verification, template_id, client_user_id)
    P-->>API: link_token
    API-->>UI: link_token
    UI->>P: Open Plaid Link (document + selfie)
    P-->>UI: onSuccess(link_session_id)
    UI->>API: POST /api/v1/kyc/complete (session id)
    API->>P: /identity_verification/get
    P-->>API: status
    API->>DB: UPDATE customers SET kyc_status, kyc_reference, kyc_verified_at
    API-->>UI: VERIFIED / PENDING / FAILED
    P-)API: STATUS_UPDATED webhook (deployed only)
    API->>P: /identity_verification/get
    API->>DB: UPDATE customers
```

```mermaid
stateDiagram-v2
    [*] --> NOT_STARTED
    NOT_STARTED --> PENDING: Link session started
    PENDING --> VERIFIED: Plaid status success
    PENDING --> FAILED: Plaid status failed
    PENDING --> NOT_STARTED: session expired or cancelled
    FAILED --> PENDING: retry allowed
    VERIFIED --> [*]
```

**Add money from bank (demo)** *(my call: gives the pitch a self-service funding moment next to agent cash-in)*

1. Customer links a Sandbox bank through Plaid Link (`products: ["auth"]`). The backend exchanges the `public_token` for an `access_token` and stores it encrypted.
2. Customer enters an amount. The backend checks the linked account's balance (`/accounts/balance/get`), then posts a journal **ESCROW_BANK → CUSTOMER_MAIN**, as if the bank deposit had settled into the company's escrow account. New `LedgerTransactionType.BANK_TOPUP` and `TransactionType.BANK_TOPUP`.
3. The screen and the receipt label it *Demo: Plaid Sandbox*, so nobody mistakes it for real money.

```mermaid
sequenceDiagram
    autonumber
    actor C as Customer
    participant UI as Web app
    participant API as Backend
    participant P as Plaid (Sandbox)
    participant L as LedgerService
    C->>UI: Link a bank
    UI->>API: POST /api/v1/banks/link-token
    API->>P: /link/token/create (auth)
    UI->>P: Plaid Link, Sandbox bank login
    P-->>UI: public_token
    UI->>API: POST /api/v1/banks/exchange
    API->>P: /item/public_token/exchange
    API->>API: store access_token encrypted
    C->>UI: Add 50,000 TZS from bank
    UI->>API: POST /api/v1/transactions/bank-topup + Idempotency-Key
    API->>P: /accounts/balance/get
    API->>L: postJournal(BANK_TOPUP: ESCROW_BANK DR, CUSTOMER_MAIN CR)
    API-->>UI: receipt marked "Demo: Plaid Sandbox"
```

**Data changes (new migrations)**

- `customers`: add `kyc_status varchar(20) NOT NULL DEFAULT 'NOT_STARTED'` with `CHECK (kyc_status IN ('NOT_STARTED','PENDING','VERIFIED','FAILED'))`, plus `kyc_provider varchar(20)` (`PLAID`), `kyc_reference varchar(100)` (the IDV session ID) and `kyc_verified_at timestamptz`.
- New `linked_bank_accounts` and `plaid_webhook_events` tables (DDL in *Tables to add*).

**Configuration**: `PLAID_CLIENT_ID`, `PLAID_SECRET`, `PLAID_ENV=sandbox`, `PLAID_IDV_TEMPLATE_ID`, `PLAID_WEBHOOK_URL` (deployed only) and `APP_ENCRYPTION_KEY` (for access tokens). These are secrets: they go in `.env`, never in git.

**Libraries**: `com.plaid:plaid-java` in the backend (new package `integration/plaid/`) and `react-plaid-link` in the frontend.

**Before real money** (not part of this program): Plaid can't move Tanzanian money. Production needs a Tanzanian payment or mobile-money provider for funding and payouts. KYC needs either Plaid IDV bought through a US, Canada or UK entity, or a provider that checks NIDA directly. The integration sits behind two interfaces, `KycProvider` and `FundingProvider`, so the provider can be swapped without touching wallet or ledger code *(my call)*.

### Phase 2 — Agent experience

Agent portal on the existing endpoints: cash-in, confirmed-withdrawal queue, float balance with low-float alert, float request with status timeline, daily summary and commission. Agent locator for customers by region → district.

### Phase 3 — Back office trust

- **Maker-checker for issuance** *(scope my call)*: `mint` and commission top-up become two-step (one staff member requests, a different one approves; new pending state). Agent float requests already have two parties (agent asks, staff decides) and stay single-approval.
- Tariff management (create/edit with effective dates, no overlapping bands, quote preview).
- `audit_logs` for every staff action and status change (who, what, before/after, when).
- Daily reconciliation job: per wallet, sum of journal lines = balance; variances surface on the dashboard. V26 seeded balances without journals; because the dev database is rebuilt (Decision 3) and all money enters via mint journals, this holds from a fresh database. Any non-rebuilt environment needs an opening-balance journal migration first.
- Customer 360 for support; lock/unlock wallet with reason.

Issuance with two approvers (mint and commission top-up):

```mermaid
stateDiagram-v2
    [*] --> PENDING: staff A requests mint or commission top-up
    PENDING --> APPROVED: staff B approves (B ≠ A)
    PENDING --> REJECTED: staff B rejects with reason
    APPROVED --> POSTED: journal posted, wallets credited
    POSTED --> [*]
    REJECTED --> [*]
```

### Phase 4 — Delight

Real-time notifications (introduce SSE here, the first feature that needs push), request money and QR, savings vaults, bills/airtime, insights and statements, Swahili, dark mode. Each gets its own spec.

### Phase 5 — Mobile

Mobile client on the same `/api/v1`; adds only `devices` (binding, push token) and biometric unlock after a PIN login on a bound device. Shared design tokens and validation rules with web.

## Cross-cutting rules

- Every money flow: one `@Transactional` method → lock wallets in id order → validate → move balances → `postJournal` → write `transactions` row → store idempotent response.
- Every endpoint has a permission mapped to a module; every staff action writes an audit entry (from Phase 3).
- Every UI flow works at phone width and by keyboard; copy is ready for English and Swahili.

## Engineering foundations

### System architecture

```mermaid
flowchart LR
    subgraph Clients
        CW[Customer web]
        AP[Agent portal]
        SC[Staff console]
        MA[Mobile app, Phase 5]
    end
    subgraph Edge
        NG[nginx: static files + /api proxy]
    end
    subgraph API[Spring Boot /api/v1]
        SEC[Security: JWT, rate limits, permissions]
        ID[Identity: auth, OTP, PIN, sessions, RBAC]
        MON[Money: wallets, transactions, ledger, tariffs, treasury]
        OPS[Ops: dashboards, audit, reconciliation]
    end
    DB[(PostgreSQL e_walletDB)]
    MAIL[SMTP / SMS for OTP]
    PLAID[Plaid Sandbox: IDV, Link, Auth, Balance]
    CW & AP & SC --> NG --> SEC
    MA --> SEC
    SEC --> ID & MON & OPS
    ID & MON & OPS --> DB
    ID --> MAIL
    ID & MON --> PLAID
```


### Database (`e_walletDB`)

Everything here comes from the real schema. On 23 Nov every Flyway migration from `E-wallet-backend@84f2bc1` was replayed into PostgreSQL 16 and read back from the catalog: columns, defaults, constraints and indexes.

#### At a glance

| | |
|---|---|
| Engine | PostgreSQL 16, managed by Flyway (46 files, V1–V47; there is no V12) |
| Tables | 20: 19 application tables + `flyway_schema_history` |
| Columns / foreign keys | 175 / 23 |
| Hibernate | `ddl-auto=validate`: entities must match migrations exactly |
| Market | Tanzania: TZS currency, NIDA numbers, 31 regions and 170 districts seeded |

#### Conventions

- **Primary keys:** `id uuid` on every table, except `role_permissions` (composite `role_id, permission_id`) and Flyway's own table.
- **Public identifiers:** most tables also carry `reference uuid` (default `gen_random_uuid()`), which is safe to expose instead of `id`. Money records use readable references: `TXN-yyyyMMdd-XXXXXXXX` for transactions and `<TYPE>-yyyyMMdd-XXXXXXXX` for journals.
- **Money:** `numeric(19,2)`, never floating point. Currency is on the wallet (`TZS`).
- **Time:** `timestamptz`. The business day is `Africa/Dar_es_Salaam`.
- **Enums:** stored as `varchar` holding the Java enum name. Only a few have database `CHECK`s (agent gender, treasury status and type).
- **Profiles:** `users` holds identity and credentials only. Names and KYC live on exactly one of `customers`, `agents` or `staff` (one-to-one via a unique `user_id`).
- **Company wallets** belong to the `SYSTEM` user. A partial unique index allows exactly one each of COMPANY_MASTER_ACCOUNT, REVENUE, ESCROW_BANK and COMMISSION.

#### Entity-relationship diagram (ERD)

Renders on GitHub and in IntelliJ's Markdown preview. `PK` is a primary key, `FK` a foreign key, `UK` a unique column.

```mermaid
erDiagram
    users {
        uuid id PK
        varchar email UK
        varchar phone UK
        varchar pin_hash
        varchar status
        timestamptz created_at
        timestamptz updated_at
        uuid reference UK
        varchar username UK
        timestamptz last_login_at
        uuid role_id FK
    }
    roles {
        uuid id PK
        varchar name UK
        varchar description
        timestamptz created_at
        timestamptz updated_at
    }
    role_permissions {
        uuid role_id PK,FK
        uuid permission_id PK,FK
    }
    permissions {
        uuid id PK
        varchar name UK
        varchar description
        timestamptz created_at
        timestamptz updated_at
        uuid module_reference FK
    }
    modules {
        uuid id PK
        uuid reference
        varchar name UK
        varchar description
        timestamptz created_at
    }
    user_sessions {
        uuid id PK
        uuid user_id FK
        varchar refresh_token_hash
        timestamptz expires_at
        bool revoked
        timestamptz created_at
        timestamptz revoked_at
    }
    otp_verification {
        uuid id PK
        uuid user_id FK
        varchar otp_hash
        timestamptz expires_at
        int attempts
        int max_attempts
        bool verified
        timestamptz verified_at
        timestamptz created_at
    }
    pin_reset_tokens {
        uuid id PK
        uuid user_id FK
        varchar otp_hash
        timestamptz expires_at
        int attempts
        int max_attempts
        bool used
        timestamptz used_at
        timestamptz created_at
    }
    customers {
        uuid id PK
        uuid user_id FK
        varchar status
        timestamptz created_at
        timestamptz updated_at
        uuid reference
        varchar first_name
        varchar middle_name
        varchar last_name
        varchar email
        varchar phone_number
        varchar nida_number
        date date_of_birth
        varchar address
    }
    agents {
        uuid id PK
        uuid user_id FK
        varchar agent_number UK
        timestamptz created_at
        timestamptz update_at
        varchar gender
        date date_of_birth
        uuid region_id FK
        uuid district_id FK
        uuid reference
        varchar first_name
        varchar middle_name
        varchar last_name
        varchar email
        varchar phone_number
        varchar business_name
        varchar tin_number
        varchar agent_status
        uuid created_by FK
    }
    staff {
        uuid id PK
        uuid reference
        uuid user_id FK
        varchar staff_no UK
        varchar job_title
        varchar first_name
        varchar middle_name
        varchar last_name
        varchar email
        varchar phone_number
        uuid created_by FK
        varchar status
        timestamptz created_at
        timestamptz updated_at
    }
    regions {
        uuid id PK
        varchar external_id
        varchar name
        varchar slug
    }
    districts {
        uuid id PK
        varchar external_id
        varchar name
        varchar slug
        uuid region_id FK
    }
    wallets {
        uuid id PK
        varchar currency
        varchar status
        timestamptz created_at
        timestamptz updated_at
        uuid user_id FK
        varchar wallet_type
        varchar account_number UK
        numeric balance
        bigint version
        uuid reference
        varchar account_name
    }
    transactions {
        uuid id PK
        varchar reference_no UK
        uuid sender_wallet_id FK
        uuid receiver_wallet_id FK
        numeric amount
        numeric fee
        numeric commission
        varchar transaction_type
        varchar status
        timestamptz created_at
    }
    transaction_journal {
        uuid id PK
        varchar reference_number UK
        varchar transaction_type
        varchar description
        uuid created_by FK
        timestamptz created_at
    }
    transaction_journal_line {
        uuid id PK
        uuid journal_id FK
        varchar entry_type
        numeric amount
        uuid wallet_id FK
    }
    tariffs {
        uuid id PK
        uuid reference
        varchar transaction_type
        numeric min_amount
        numeric max_amount
        numeric fee
        numeric agent_commission
        bool active
        timestamptz created_at
    }
    treasury_requests {
        uuid id PK
        uuid agent_id FK
        numeric amount
        varchar reason
        varchar status
        uuid decided_by FK
        varchar decision_note
        timestamptz decided_at
        timestamptz created_at
        timestamptz updated_at
        varchar request_ref UK
        varchar request_type
        varchar external_reference
    }
    roles ||--o{ users : "grants role to"
    roles ||--o{ role_permissions : "has"
    permissions ||--o{ role_permissions : "granted via"
    modules ||--o{ permissions : "groups"
    users ||--o{ user_sessions : "signs in with"
    users ||--o| otp_verification : "verifies with"
    users ||--o| pin_reset_tokens : "resets PIN with"
    users ||--o| customers : "is profile of"
    users ||--o| agents : "is profile of"
    users ||--o| staff : "is profile of"
    users ||--o{ agents : "created_by"
    users ||--o{ staff : "created_by"
    regions ||--o{ districts : "contains"
    regions ||--o{ agents : "locates"
    districts ||--o{ agents : "locates"
    users ||--o{ wallets : "owns"
    wallets ||--o{ transactions : "sender"
    wallets ||--o{ transactions : "receiver"
    users ||--o{ transaction_journal : "created_by"
    transaction_journal ||--|{ transaction_journal_line : "has 2+ lines"
    wallets ||--o{ transaction_journal_line : "moved by"
    agents ||--o{ treasury_requests : "raises"
    users ||--o{ treasury_requests : "decided_by"
```

#### Domain model (UML class diagram)

The JPA entities as the code sees them: key attributes, the enums they use, and how they associate. Columns already listed in the ERD are left out unless they carry behaviour.

```mermaid
classDiagram
    direction LR
    class UserEntity {
        UUID id
        String email
        String phone
        String pinHash
        UserStatus status
        Instant lastLoginAt
    }
    class RoleEntity {
        String name
    }
    class PermissionEntity {
        String name
    }
    class ModuleEntity {
        String name
    }
    class CustomerEntity {
        String nidaNumber
        CustomerStatus status
    }
    class AgentEntity {
        String agentNumber
        String businessName
        String tinNumber
        AgentStatus agentStatus
    }
    class StaffEntity {
        String staffNo
        String jobTitle
        StaffStatus status
    }
    class WalletEntity {
        WalletType walletType
        String accountNumber
        BigDecimal balance
        String currency
        WalletStatus status
        Long version
    }
    class TransactionEntity {
        String referenceNo
        BigDecimal amount
        BigDecimal fee
        BigDecimal commission
        String transactionType
        String status
    }
    class TransactionJournalEntity {
        String referenceNumber
        LedgerTransactionType transactionType
    }
    class TransactionJournalLineEntity {
        UUID walletId
        EntryType entryType
        BigDecimal amount
    }
    class TariffEntity {
        String transactionType
        BigDecimal minAmount
        BigDecimal maxAmount
        BigDecimal fee
        BigDecimal agentCommission
        Boolean active
    }
    class TreasuryRequestEntity {
        String requestRef
        BigDecimal amount
        RequestType requestType
        RequestStatus status
    }
    class RegionEntity
    class DistrictEntity

    UserEntity "*" --> "1" RoleEntity : role
    RoleEntity "*" --> "*" PermissionEntity : permissions
    PermissionEntity "*" --> "1" ModuleEntity : module
    CustomerEntity "0..1" --> "1" UserEntity : user
    AgentEntity "0..1" --> "1" UserEntity : user
    StaffEntity "0..1" --> "1" UserEntity : user
    AgentEntity "*" --> "1" RegionEntity : region
    AgentEntity "*" --> "1" DistrictEntity : district
    DistrictEntity "*" --> "1" RegionEntity : region
    WalletEntity "*" --> "1" UserEntity : owner
    TransactionEntity "*" --> "1" WalletEntity : senderWallet
    TransactionEntity "*" --> "1" WalletEntity : receiverWallet
    TransactionJournalEntity "1" *-- "2..*" TransactionJournalLineEntity : lines
    TransactionJournalEntity "*" --> "1" UserEntity : createdBy
    TreasuryRequestEntity "*" --> "1" AgentEntity : agent
    TreasuryRequestEntity "*" --> "0..1" UserEntity : decidedBy

    class WalletType {
        <<enumeration>>
        COMPANY_MASTER_ACCOUNT
        REVENUE
        ESCROW_BANK
        COMMISSION
        AGENT_FLOAT
        AGENT_COMMISSION
        CUSTOMER_MAIN
    }
    class LedgerTransactionType {
        <<enumeration>>
        MINT_FLOAT
        TOPUP_FLOAT
        AGENT_WITHDRAW
        TOPUP_COMMISSION
        CASH_IN
        CASH_OUT
    }
    class EntryType {
        <<enumeration>>
        DEBIT
        CREDIT
    }
    WalletEntity ..> WalletType
    TransactionJournalEntity ..> LedgerTransactionType
    TransactionJournalLineEntity ..> EntryType
```

`TransactionJournalLineEntity.walletId` is a plain UUID in Java but a real foreign key in the database. The journal owns its lines (composition): they are saved and never edited with it.

#### How a posting touches the tables (UML sequence: agent cash-in today)

```mermaid
sequenceDiagram
    autonumber
    actor Agent
    participant TC as TransactionController
    participant TS as TransactionService
    participant DB as PostgreSQL
    participant LS as LedgerService
    Agent->>TC: POST /api/v1/transactions/cash-in (account, phone, amount, agent PIN)
    TC->>TS: cashIn(request, agentUserId)
    TS->>DB: SELECT agents, wallets (AGENT_FLOAT) FOR UPDATE
    TS->>TS: check agent ACTIVE, account matches, PIN matches
    TS->>DB: SELECT wallets (customer) FOR UPDATE, customers
    TS->>DB: SELECT tariffs (CASH_IN band for amount)
    TS->>DB: SELECT wallets (COMMISSION, AGENT_COMMISSION) FOR UPDATE
    TS->>TS: check balances, move 4 balances in memory
    TS->>LS: postJournal(CASH_IN, 4 lines)
    LS->>LS: reject if debits ≠ credits
    LS->>DB: INSERT transaction_journal + 4 transaction_journal_line
    TS->>DB: UPDATE 4 wallets (version + 1)
    TS->>DB: INSERT transactions (COMPLETED)
    TS-->>Agent: 200 CashInResponse (reference, balances)
    Note over TS,DB: All of it is one @Transactional unit, so any failure rolls everything back
```

| Flow | Journal lines (debit → credit) |
|---|---|
| Cash-in | Agent float → Customer main (amount); Commission pool → Agent commission (commission) |
| Cash-out | Customer main (amount + fee) → Agent float (amount) + Revenue (fee); Commission pool → Agent commission (commission) |
| Transfer *(Phase 1)* | Customer A (amount + fee) → Customer B (amount) + Revenue (fee) |
| Bank top-up *(Phase 1, Plaid Sandbox demo)* | Escrow bank → Customer main (amount) |

#### Data dictionary

Null "yes" means the column is nullable. Key: `PK` primary key, `FK` foreign key, `UQ` unique. Enum columns name their Java enum; values are in the next table.

#### Identity & access

##### `users`

Login identity for every person and the SYSTEM account. Holds credentials (PIN hash) and one role; names live on the profile tables.

| Column | Type | Null | Default | Key | Description |
|---|---|---|---|---|---|
| `id` | uuid | no |  | PK | Primary key (UUID) |
| `email` | varchar(255) | no |  | UQ | Email; unique; used for login and OTP emails |
| `phone` | varchar(30) | no |  | UQ | Phone; unique; used for login |
| `pin_hash` | varchar(255) | yes |  |  | BCrypt hash of the 4-digit PIN; null until the PIN is set |
| `status` | varchar(30) | no |  |  | `UserStatus`: login state of the account |
| `created_at` | timestamptz | no |  |  | When the row was created |
| `updated_at` | timestamptz | no |  |  | When the row was last changed |
| `reference` | uuid | no | `gen_random_uuid()` | UQ | Public UUID safe to expose in APIs instead of `id` |
| `username` | varchar(100) | no |  | UQ | Unique username (ERD alignment; login still uses email/phone) |
| `last_login_at` | timestamptz | yes |  |  | Last successful login |
| `role_id` | uuid | no |  | FK → `roles` | The user’s single role |

Constraints & indexes: indexes: `idx_users_phone` (phone), `idx_users_email` (email)

##### `roles`

The four roles a user can have. One role per user.

| Column | Type | Null | Default | Key | Description |
|---|---|---|---|---|---|
| `id` | uuid | no |  | PK | Primary key (UUID) |
| `name` | varchar(50) | no |  | UQ | `ADMIN`, `CUSTOMER`, `AGENT`, `SYSTEM` |
| `description` | varchar(255) | yes |  |  | Human-readable description |
| `created_at` | timestamptz | no |  |  | When the row was created |
| `updated_at` | timestamptz | no |  |  | When the row was last changed |

Constraints & indexes: indexes: `idx_roles_name` (name)

##### `permissions`

Fine-grained authorities checked by `@PreAuthorize` (e.g. `FLOAT_MANAGE`). Each belongs to one module.

| Column | Type | Null | Default | Key | Description |
|---|---|---|---|---|---|
| `id` | uuid | no |  | PK | Primary key (UUID) |
| `name` | varchar(255) | no |  | UQ | Authority string, e.g. `TOPUP_CREATE` |
| `description` | varchar(255) | yes |  |  | What the permission allows |
| `created_at` | timestamptz | no |  |  | When the row was created |
| `updated_at` | timestamptz | no |  |  | When the row was last changed |
| `module_reference` | uuid | no |  | FK → `modules` | Module this permission belongs to |

Constraints & indexes: indexes: `idx_permissions_name` (name)

##### `role_permissions`

Which permissions each role grants (many-to-many).

| Column | Type | Null | Default | Key | Description |
|---|---|---|---|---|---|
| `role_id` | uuid | no |  | PK, FK → `roles` | Role |
| `permission_id` | uuid | no |  | PK, FK → `permissions` | Permission granted to the role |

Constraints & indexes: indexes: `idx_role_permissions_permission_id` (permission_id)

##### `modules`

Functional areas that group permissions (AGENT, WALLET, TREASURY…), used for menus and the role builder.

| Column | Type | Null | Default | Key | Description |
|---|---|---|---|---|---|
| `id` | uuid | no |  | PK | Primary key (UUID) |
| `reference` | uuid | no | `gen_random_uuid()` | UQ | Public UUID safe to expose in APIs instead of `id` |
| `name` | varchar(50) | no |  | UQ | Module code, e.g. `WALLET` |
| `description` | varchar(255) | no |  |  | What the module covers |
| `created_at` | timestamptz | no | `now()` |  | When the row was created |

##### `user_sessions`

One row per issued refresh token, so sessions can be listed and revoked.

| Column | Type | Null | Default | Key | Description |
|---|---|---|---|---|---|
| `id` | uuid | no |  | PK | Primary key (UUID) |
| `user_id` | uuid | no |  | FK → `users` | Owning user |
| `refresh_token_hash` | varchar(255) | no |  | UQ | Hash of the refresh token (never the token itself) |
| `expires_at` | timestamptz | no |  |  | Refresh token expiry (7 days) |
| `revoked` | boolean | no | `false` |  | True after logout, rotation or PIN reset |
| `created_at` | timestamptz | no |  |  | When the row was created |
| `revoked_at` | timestamptz | yes |  |  | When it was revoked |

Constraints & indexes: indexes: `idx_user_sessions_expires_at` (expires_at), `idx_user_sessions_user_id` (user_id)

##### `otp_verification`

The current registration/login OTP for a user (one active row per user).

| Column | Type | Null | Default | Key | Description |
|---|---|---|---|---|---|
| `id` | uuid | no |  | PK | Primary key (UUID) |
| `user_id` | uuid | no |  | FK → `users`, UQ | Owning user |
| `otp_hash` | varchar(255) | no |  |  | Hash of the 6-digit OTP |
| `expires_at` | timestamptz | no |  |  | Expiry (5 minutes) |
| `attempts` | integer | no | `0` |  | Wrong guesses so far |
| `max_attempts` | integer | no |  |  | Allowed guesses (5) |
| `verified` | boolean | no | `false` |  | True once verified |
| `verified_at` | timestamptz | yes |  |  | When verified |
| `created_at` | timestamptz | no |  |  | When the row was created |

Constraints & indexes: indexes: `idx_otp_expires_at` (expires_at), `idx_otp_user_id` (user_id)

##### `pin_reset_tokens`

The current forgot-PIN code for a user (one active row per user).

| Column | Type | Null | Default | Key | Description |
|---|---|---|---|---|---|
| `id` | uuid | no |  | PK | Primary key (UUID) |
| `user_id` | uuid | no |  | FK → `users`, UQ | Owning user |
| `otp_hash` | varchar(255) | no |  |  | Hash of the 6-digit reset code |
| `expires_at` | timestamptz | no |  |  | Expiry (5 minutes) |
| `attempts` | integer | no | `0` |  | Wrong guesses so far |
| `max_attempts` | integer | no |  |  | Allowed guesses (5) |
| `used` | boolean | no | `false` |  | True once consumed |
| `used_at` | timestamptz | yes |  |  | When consumed |
| `created_at` | timestamptz | no |  |  | When the row was created |

Constraints & indexes: indexes: `idx_pin_reset_tokens_user_id` (user_id), `idx_pin_reset_tokens_expires_at` (expires_at)

#### Parties

##### `customers`

Customer profile and KYC data for a CUSTOMER user.

| Column | Type | Null | Default | Key | Description |
|---|---|---|---|---|---|
| `id` | uuid | no |  | PK | Primary key (UUID) |
| `user_id` | uuid | no |  | FK → `users`, UQ | Owning user |
| `status` | varchar(20) | no |  |  | `CustomerStatus` |
| `created_at` | timestamptz | no |  |  | When the row was created |
| `updated_at` | timestamptz | no |  |  | When the row was last changed |
| `reference` | uuid | no | `gen_random_uuid()` | UQ | Public UUID safe to expose in APIs instead of `id` |
| `first_name` | varchar(100) | no |  |  | First name |
| `middle_name` | varchar(100) | yes |  |  | Middle name |
| `last_name` | varchar(100) | no |  |  | Last name |
| `email` | varchar(255) | no |  |  | Email address |
| `phone_number` | varchar(30) | no |  |  | Phone number in normalised international form |
| `nida_number` | varchar(30) | no |  |  | Tanzanian national ID (NIDA) number |
| `date_of_birth` | date | no |  |  | Date of birth |
| `address` | varchar(255) | no |  |  | Residential address |

##### `agents`

Agent business profile, KYC and location for an AGENT user.

| Column | Type | Null | Default | Key | Description |
|---|---|---|---|---|---|
| `id` | uuid | no |  | PK | Primary key (UUID) |
| `user_id` | uuid | no |  | FK → `users`, UQ | Owning user |
| `agent_number` | varchar(30) | no |  | UQ | Unique agent code shown to customers |
| `created_at` | timestamptz | no |  |  | When the row was created |
| `update_at` | timestamptz | no |  |  | When the row was last changed (column name lacks the "d"; see findings) |
| `gender` | varchar(10) | no |  |  | `Gender` (CHECK constrained) |
| `date_of_birth` | date | no |  |  | Date of birth |
| `region_id` | uuid | no |  | FK → `regions` | Region the agent operates in |
| `district_id` | uuid | no |  | FK → `districts` | District the agent operates in |
| `reference` | uuid | no | `gen_random_uuid()` | UQ | Public UUID safe to expose in APIs instead of `id` |
| `first_name` | varchar(100) | no |  |  | First name |
| `middle_name` | varchar(100) | yes |  |  | Middle name |
| `last_name` | varchar(100) | no |  |  | Last name |
| `email` | varchar(255) | no |  |  | Email address |
| `phone_number` | varchar(30) | no |  |  | Phone number in normalised international form |
| `business_name` | varchar(150) | no |  |  | Registered business name |
| `tin_number` | varchar(30) | no |  |  | Tax identification number (TIN) |
| `agent_status` | varchar(20) | no | `'ACTIVE'` |  | `AgentStatus`: operational standing, separate from login status |
| `created_by` | uuid | yes |  | FK → `users` | User who created this row |

Constraints & indexes: `CHECK (((gender) = ANY (['MALE', 'FEMALE', 'OTHER'][]))`

##### `staff`

Back-office profile for ADMIN/finance/support users.

| Column | Type | Null | Default | Key | Description |
|---|---|---|---|---|---|
| `id` | uuid | no |  | PK | Primary key (UUID) |
| `reference` | uuid | no | `gen_random_uuid()` | UQ | Public UUID safe to expose in APIs instead of `id` |
| `user_id` | uuid | no |  | FK → `users`, UQ | Owning user |
| `staff_no` | varchar(30) | no |  | UQ | Unique staff number |
| `job_title` | varchar(100) | no |  |  | e.g. “System Administrator” |
| `first_name` | varchar(100) | no |  |  | First name |
| `middle_name` | varchar(100) | yes |  |  | Middle name |
| `last_name` | varchar(100) | no |  |  | Last name |
| `email` | varchar(255) | no |  |  | Email address |
| `phone_number` | varchar(30) | no |  |  | Phone number in normalised international form |
| `created_by` | uuid | yes |  | FK → `users` | User who created this row |
| `status` | varchar(20) | no | `'ACTIVE'` |  | `StaffStatus` |
| `created_at` | timestamptz | no | `now()` |  | When the row was created |
| `updated_at` | timestamptz | no | `now()` |  | When the row was last changed |

#### Money & ledger

##### `wallets`

Every balance in the system: customer, agent float and commission, and four singleton company wallets. Balance changes only with journal lines.

| Column | Type | Null | Default | Key | Description |
|---|---|---|---|---|---|
| `id` | uuid | no |  | PK | Primary key (UUID) |
| `currency` | varchar(3) | no |  |  | ISO currency code (TZS) |
| `status` | varchar(20) | no |  |  | `WalletStatus` |
| `created_at` | timestamptz | no |  |  | When the row was created |
| `updated_at` | timestamptz | no |  |  | When the row was last changed |
| `user_id` | uuid | no |  | FK → `users` | Owning user |
| `wallet_type` | varchar(30) | no |  |  | `WalletType` |
| `account_number` | varchar(30) | no |  | UQ | Unique account number (customer: phone number; agent: generated code) |
| `balance` | numeric(19,2) | no |  |  | Current balance; CHECK ≥ 0 |
| `version` | bigint | no | `0` |  | Optimistic-lock version (JPA `@Version`) |
| `reference` | uuid | no | `gen_random_uuid()` | UQ | Public UUID safe to expose in APIs instead of `id` |
| `account_name` | varchar(150) | no |  |  | Display name, e.g. customer full name or “Company Revenue” |

Constraints & indexes: `CHECK ((balance >= (0)))`; indexes: `idx_wallets_wallet_type` (wallet_type); partial unique index: one wallet each of COMPANY_MASTER_ACCOUNT, REVENUE, ESCROW_BANK, COMMISSION

##### `transactions`

Customer/agent-facing record of a money movement (what a receipt or history shows).

| Column | Type | Null | Default | Key | Description |
|---|---|---|---|---|---|
| `id` | uuid | no |  | PK | Primary key (UUID) |
| `reference_no` | varchar(60) | no |  | UQ | Receipt reference `TXN-yyyyMMdd-XXXXXXXX` |
| `sender_wallet_id` | uuid | no |  | FK → `wallets` | Wallet debited |
| `receiver_wallet_id` | uuid | no |  | FK → `wallets` | Wallet credited |
| `amount` | numeric(19,2) | no |  |  | Principal amount |
| `fee` | numeric(19,2) | no | `0` |  | Fee charged to the customer |
| `commission` | numeric(19,2) | no | `0` |  | Commission paid to the agent |
| `transaction_type` | varchar(30) | no |  |  | `TransactionType` stored as text |
| `status` | varchar(20) | no |  |  | `TransactionStatus` stored as text |
| `created_at` | timestamptz | no | `now()` |  | When the row was created |

Constraints & indexes: indexes: `idx_transactions_status_created_at` (status, created_at), `idx_transactions_receiver_wallet` (receiver_wallet_id), `idx_transactions_sender_wallet` (sender_wallet_id)

##### `transaction_journal`

Double-entry journal header: one per posting. The ledger of truth.

| Column | Type | Null | Default | Key | Description |
|---|---|---|---|---|---|
| `id` | uuid | no |  | PK | Primary key (UUID) |
| `reference_number` | varchar(60) | no |  | UQ | Journal reference `<TYPE>-yyyyMMdd-XXXXXXXX` |
| `transaction_type` | varchar(30) | no |  |  | `LedgerTransactionType` |
| `description` | varchar(255) | no |  |  | Human-readable description of the posting |
| `created_by` | uuid | no |  | FK → `users` | User who triggered the posting |
| `created_at` | timestamptz | no |  |  | When the row was created |

##### `transaction_journal_line`

Debit/credit lines of a journal; debits equal credits per journal.

| Column | Type | Null | Default | Key | Description |
|---|---|---|---|---|---|
| `id` | uuid | no |  | PK | Primary key (UUID) |
| `journal_id` | uuid | no |  | FK → `transaction_journal` | Parent journal |
| `entry_type` | varchar(10) | no |  |  | `EntryType`: DEBIT or CREDIT |
| `amount` | numeric(19,2) | no |  |  | Line amount (positive) |
| `wallet_id` | uuid | no |  | FK → `wallets` | Wallet this line moves |

##### `tariffs`

Fee and agent-commission bands per transaction type and amount range.

| Column | Type | Null | Default | Key | Description |
|---|---|---|---|---|---|
| `id` | uuid | no |  | PK | Primary key (UUID) |
| `reference` | uuid | no | `gen_random_uuid()` | UQ | Public UUID safe to expose in APIs instead of `id` |
| `transaction_type` | varchar(30) | no |  |  | Transaction type the band applies to |
| `min_amount` | numeric(19,2) | no |  |  | Band lower bound (inclusive) |
| `max_amount` | numeric(19,2) | no |  |  | Band upper bound; CHECK ≥ min_amount |
| `fee` | numeric(19,2) | no |  |  | Fee charged in this band |
| `agent_commission` | numeric(19,2) | no |  |  | Commission paid to the agent in this band |
| `active` | boolean | no | `true` |  | Only active bands are used |
| `created_at` | timestamptz | no | `now()` |  | When the row was created |

Constraints & indexes: `CHECK ((max_amount >= min_amount))`

##### `treasury_requests`

Agent float top-up or withdrawal requests and the staff decision on them.

| Column | Type | Null | Default | Key | Description |
|---|---|---|---|---|---|
| `id` | uuid | no |  | PK | Primary key (UUID) |
| `agent_id` | uuid | no |  | FK → `agents` | Requesting agent |
| `amount` | numeric(19,2) | no |  |  | Requested amount; CHECK > 0 |
| `reason` | varchar(500) | no |  |  | Why the agent is asking |
| `status` | varchar(20) | no |  |  | `RequestStatus` (CHECK constrained) |
| `decided_by` | uuid | yes |  | FK → `users` | Staff user who approved or rejected |
| `decision_note` | varchar(500) | yes |  |  | Reason given with the decision |
| `decided_at` | timestamptz | yes |  |  | When decided |
| `created_at` | timestamptz | no |  |  | When the row was created |
| `updated_at` | timestamptz | no |  |  | When the row was last changed |
| `request_ref` | varchar(20) | no |  | UQ | Short public reference |
| `request_type` | varchar(20) | no |  |  | `RequestType` (CHECK constrained) |
| `external_reference` | varchar(100) | yes |  |  | Outside evidence, e.g. bank deposit slip number |

Constraints & indexes: `CHECK ((amount > (0)))`; `CHECK (((status) = ANY (['PENDING', 'APPROVED', 'REJECTED'][]))`; `CHECK (((request_type) = ANY (['TOPUP', 'WITHDRAWAL'][]))`; indexes: `idx_treasury_requests_agent_id` (agent_id), `idx_treasury_requests_status` (status)

#### Geography

##### `regions`

Tanzanian regions (seeded).

| Column | Type | Null | Default | Key | Description |
|---|---|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` | PK | Primary key (UUID) |
| `external_id` | varchar(100) | no |  | UQ | ID from the source dataset |
| `name` | varchar(100) | no |  |  | Region name |
| `slug` | varchar(100) | no |  | UQ | URL-safe name |

##### `districts`

Districts within a region (seeded). Agents are placed in one.

| Column | Type | Null | Default | Key | Description |
|---|---|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` | PK | Primary key (UUID) |
| `external_id` | varchar(100) | no |  | UQ | ID from the source dataset |
| `name` | varchar(100) | no |  |  | District name (unique within region) |
| `slug` | varchar(100) | no |  |  | URL-safe name |
| `region_id` | uuid | no |  | FK → `regions` | Region it belongs to |

Constraints & indexes: unique (region_id, name); indexes: `idx_district_region_id` (region_id)

#### Enumerations

| Enum | Values | Stored in |
|---|---|---|
| `UserStatus` | AWAITING_PIN, PIN_CHANGE_REQUIRED, ACTIVE, SUSPENDED, DISABLED, BLOCKED, CLOSED | `users.status` |
| `CustomerStatus` | PENDING, ACTIVE, SUSPENDED | `customers.status` |
| `AgentStatus` | ACTIVE, SUSPENDED, DEACTIVATED | `agents.agent_status` |
| `StaffStatus` | ACTIVE, SUSPENDED, DISABLED | `staff.status` |
| `Gender` | MALE, FEMALE, OTHER | `agents.gender` |
| `WalletType` | COMPANY_MASTER_ACCOUNT, REVENUE, ESCROW_BANK, COMMISSION, AGENT_FLOAT, AGENT_COMMISSION, CUSTOMER_MAIN | `wallets.wallet_type` |
| `WalletStatus` | ACTIVE, SUSPENDED, BLOCKED, CLOSED | `wallets.status` |
| `TransactionType` | CASH_IN, CASH_OUT, SERVICE_FEE, SERVICE_COMMISSION (+ TRANSFER, BANK_TOPUP, Phase 1) | `transactions.transaction_type`, `tariffs.transaction_type` |
| `TransactionStatus` | PENDING, COMPLETED, FAILED | `transactions.status` |
| `LedgerTransactionType` | MINT_FLOAT, TOPUP_FLOAT, AGENT_WITHDRAW, TOPUP_COMMISSION, CASH_IN, CASH_OUT (+ TRANSFER, BANK_TOPUP, Phase 1) | `transaction_journal.transaction_type` |
| `EntryType` | DEBIT, CREDIT | `transaction_journal_line.entry_type` |
| `RequestType` | TOPUP, WITHDRAWAL | `treasury_requests.request_type` |
| `RequestStatus` | PENDING, APPROVED, REJECTED | `treasury_requests.status` |
| `KycStatus` *(new, Phase 1)* | NOT_STARTED, PENDING, VERIFIED, FAILED | `customers.kyc_status` |

#### Reference and seed data

| Table | Seeded by migrations |
|---|---|
| `roles` | 4: ADMIN, CUSTOMER, AGENT, SYSTEM |
| `modules` → `permissions` | 10 modules, 36 permissions: AGENT 4, CUSTOMER 3, FINANCE 6, LOAN 4, ROLE 2, SYSTEM 1, TARIFF 3, TRANSACTION 6, USER 3, WALLET 4 |
| `regions` / `districts` | 31 / 170 |
| `tariffs` | 2 bands, 1,000 – 100,000,000 TZS: CASH_IN fee 0, commission 200; CASH_OUT fee 300, commission 200 |
| `wallets` | 4 company wallets owned by the SYSTEM user: Company Master Account, Company Revenue, Company Escrow Bank, Commission pool |
| `users` / `staff` | First admin created at startup by `DataInitializer` when `APP_INITIAL_ADMIN_ENABLED=true` |

The LOAN module's 4 permissions exist with no loan tables or endpoints. They are kept for now and hidden in the UI (Loans is a non-goal).

#### Schema findings (fix in Phase 0 unless noted)

| # | Finding | Why it matters | Proposed fix |
|---|---|---|---|
| 1 | `transaction_journal_line` has no index on `wallet_id` or `journal_id` | Reconciliation and wallet statements scan the whole table | `V48`: index both columns |
| 2 | Duplicate indexes: `users.phone`, `users.email`, `roles.name`, `permissions.name`, `otp_verification.user_id`, `pin_reset_tokens.user_id` each have a plain index next to a unique one | Extra write cost, no read benefit | Drop the plain duplicates |
| 3 | No `CHECK (amount > 0)` on `transaction_journal_line` or `transactions`; no `CHECK (fee >= 0, commission >= 0)` | A bug could post a zero or negative line that still balances | Add the checks |
| 4 | Most status/type columns have no `CHECK` (users, customers, wallets, transactions, journal) | Any string can be stored if code paths bypass the enum | Add `CHECK … IN (…)` matching each enum |
| 5 | `agents.update_at` (missing "d") while every other table uses `updated_at` | Inconsistent; easy to get wrong in queries | Rename the column and the entity mapping together |
| 6 | `customers.phone_number` and `email` are not unique, though `users.phone`/`email` are | Profile copy can drift from login identity | Keep `users` as the source of truth; add unique on `customers.phone_number` or derive it |
| 7 | `tariffs` allows overlapping bands for the same type | `findApplicableTariff` result becomes ambiguous | Enforced in service now; exclusion constraint with tariff management (Phase 3) |
| 8 | Seed balances from V26 have no journal lines | Reconciliation would show variances on an un-rebuilt database | Rebuild dev DB (decision 3); opening-balance journal for any other environment |

#### Tables to add

```sql
-- Phase 0: safe retries for money POSTs
CREATE TABLE idempotency_keys (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       uuid NOT NULL REFERENCES users(id),
    idem_key      varchar(80) NOT NULL,
    endpoint      varchar(120) NOT NULL,
    request_hash  varchar(64) NOT NULL,          -- SHA-256 of the request body
    status        varchar(20) NOT NULL CHECK (status IN ('IN_PROGRESS','COMPLETED','FAILED')),
    http_status   integer,
    response_json jsonb,
    created_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, idem_key)
);

-- Phase 1: saved recipients
CREATE TABLE beneficiaries (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_user_id      uuid NOT NULL REFERENCES users(id),
    wallet_id          uuid NOT NULL REFERENCES wallets(id),
    nickname           varchar(60),
    last_used_at       timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    UNIQUE (owner_user_id, wallet_id)
);

-- Phase 3: who did what
CREATE TABLE audit_logs (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id     uuid REFERENCES users(id),
    action       varchar(60) NOT NULL,           -- e.g. WALLET_LOCK, TARIFF_UPDATE
    entity_type  varchar(60) NOT NULL,
    entity_id    uuid,
    before_json  jsonb,
    after_json   jsonb,
    reason       varchar(500),
    ip_address   varchar(45),
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_logs_entity ON audit_logs (entity_type, entity_id);
CREATE INDEX idx_audit_logs_actor_created ON audit_logs (actor_id, created_at);

-- Phase 1: Plaid bank linking (Sandbox demo)
CREATE TABLE linked_bank_accounts (
    id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                uuid NOT NULL REFERENCES users(id),
    provider               varchar(20) NOT NULL DEFAULT 'PLAID',
    provider_item_id       varchar(100) NOT NULL,
    access_token_encrypted text NOT NULL,          -- AES-GCM with APP_ENCRYPTION_KEY
    provider_account_id    varchar(100) NOT NULL,
    institution_name       varchar(150),
    account_mask           varchar(10),            -- last 4 digits only
    status                 varchar(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REVOKED')),
    created_at             timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, provider_account_id)
);
CREATE INDEX idx_linked_bank_accounts_user ON linked_bank_accounts (user_id);

-- Phase 1: Plaid webhooks, stored once so a retry is never processed twice
CREATE TABLE plaid_webhook_events (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    webhook_type  varchar(50) NOT NULL,
    webhook_code  varchar(50) NOT NULL,
    object_id     varchar(100) NOT NULL,          -- e.g. identity_verification_id
    payload       jsonb NOT NULL,
    received_at   timestamptz NOT NULL DEFAULT now(),
    processed_at  timestamptz,
    UNIQUE (webhook_type, webhook_code, object_id, received_at)
);
```

Later: `devices` (binding, push token; Phase 5), `notifications` (Phase 4), `savings_vaults`, `payment_requests` and `billers` (Phase 4, each with its own spec).

#### Schema rules

- Never edit or rename a merged migration. The next file is `V48`, and each file makes one change.
- A PR that adds a migration also updates the matching entity (`ddl-auto=validate` fails otherwise).
- Balances change only in the same DB transaction as their journal lines. Reversals are new opposite journals; nothing is updated or deleted.
- New status/type columns get a `CHECK` matching their enum. New money columns get sign checks.
- Every new foreign key gets an index.

### Project structure

Both repos are organised by feature. Lines marked `+` are proposed by this design; `-` marks what to remove.

**Backend**: `Abuu37/E-wallet-backend` · Spring Boot 4.2 · Java 21 · Maven

```
e-wallet-backend/
├─ pom.xml                       JPA, Security, Flyway, Mail, JJWT, Bucket4j, libphonenumber
├─ .env.example                  DB, admin seed, SMTP, JWT_SECRET
├─ + Dockerfile
├─ + .github/workflows/ci.yml
├─ docs/superpowers/{specs,plans}/
└─ src/main/java/com/application/e_wallet/
   ├─ auth/  otp/  security/          login, PIN, refresh, OTP, SecurityConfig, JWT, DataInitializer
   ├─ user/  role/  permission/  module/
   ├─ customer/  agent/  staff/
   ├─ wallet/                         7 wallet types, PESSIMISTIC_WRITE locks
   ├─ ledger/                         LedgerService.postJournal (rejects unbalanced)
   ├─ transaction/                    cash-in, cash-out (+ transfer, history, receipts)
   ├─ treasury/                       mint, commission top-up, float requests
   ├─ tariff/  location/  dashboard/{admin,agent}/
   ├─ common/                         exceptions, validation, events
   ├─ + idempotency/                  Idempotency-Key filter + idempotency_keys
   ├─ + limit/                        LimitService (per-transaction and daily)
   ├─ + audit/                        audit_logs (Phase 3)
   ├─ + integration/plaid/            KycProvider + FundingProvider via plaid-java, webhook controller
   └─ + config/OpenApiConfig.java     springdoc
   src/main/resources/db/migration/   V1…V47, next V48
   src/test/java/…                    11 classes, 74 tests (+ TransactionServiceTest)
```

Inside each feature: `controller/ dto/ entity/ repository/ service/`. DTOs are records named `*Request`/`*Response`, errors go through `common/exception/GlobalExceptionHandler`, and every money flow posts through `LedgerService`.

**Frontend**: `Abuu37/E-wallet-frontend` · React 19 · Vite 8 · TypeScript · Tailwind 4

```
e-wallet-frontend/
├─ .env  .env.production         VITE_API_BASE_URL only (no secrets)
├─ + Dockerfile  nginx.conf
├─ + .github/workflows/ci.yml
└─ src/
   ├─ App.tsx                    routes: public, /dashboard (staff/agent), /account (customer)
   ├─ + lib/api/client.ts        base URL, bearer token, refresh-on-401 once, typed errors
   ├─ components/                Guard, context, layout, ui
   ├─ pages/                     Home, services, Fees, FAQ, Contact
   └─ features/
      ├─ auth/                   register, login, forgot PIN (live API)
      ├─ account/                customer overview, withdraw
      ├─ overview/ transactions/ customers/ ledger/ reconciliation/ fees/ financial/ wallets/ settings/
      ├─ cashiers/  → rename to agents/
      ├─ - loans/                hide until it has a backend and spec
      ├─ - */data.ts             mock data, replaced by per-feature api.ts
      ├─ + send-money/  + history/  + agent/
      ├─ + kyc/  + banks/            Plaid Link (react-plaid-link): verify identity, link bank, add money
```

Frontend conventions to adopt: all HTTP goes through `src/lib/api`, never `fetch` inside components. Each feature owns `api.ts`, `types.ts` and `pages/`. Convert `.jsx` to `.tsx` as files are touched. Role names match the backend (`ADMIN`, `AGENT`, `CUSTOMER`).

**Mobile (Phase 5)**: a third repo, `E-wallet-mobile`, on the same `/api/v1`, sharing design tokens and validation rules.

### Docker

Neither repo has Docker today. The proposal is one Compose stack for local and staging, and the same images for production.

| Service | Image | Port | Role |
|---|---|---|---|
| `frontend` | nginx:alpine (built from node:22-alpine) | 80 | Serves `dist/` and proxies `/api/` to the backend, so there's no CORS in production |
| `backend` | eclipse-temurin:21-jre (built with maven:3.9-eclipse-temurin-21) | 8080 | Runs Flyway on start, then validates entities |
| `db` | postgres:16-alpine | 5432, volume `pgdata` | `e_walletDB`, health-checked before the backend starts |
| `mail` (dev only) | axllent/mailpit | SMTP 1025, UI 8025 | Catches OTP emails locally |

How the containers connect:

```mermaid
flowchart LR
    B[Browser] -->|:80| FE[frontend<br/>nginx:alpine]
    FE -->|/api/ → :8080| BE[backend<br/>eclipse-temurin:21-jre]
    BE -->|:5432| DB[(db<br/>postgres:16-alpine<br/>volume pgdata)]
    BE -->|SMTP :1025| MP[mail<br/>mailpit, dev only]
    DEV[Developer] -->|:8025| MP
    DB -. healthcheck .-> BE
```

`docker-compose.yml`:

```yaml
services:
  db:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: e_walletDB
      POSTGRES_USER: ${DB_USERNAME}
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    volumes: [pgdata:/var/lib/postgresql/data]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${DB_USERNAME}"]
      interval: 5s
      retries: 10
  mail:
    image: axllent/mailpit
    ports: ["8025:8025"]
  backend:
    build: ./e-wallet-backend
    env_file: .env
    environment:
      DB_URL: jdbc:postgresql://db:5432/e_walletDB
      APP_MAIL_HOST: mail
      APP_MAIL_PORT: 1025
    depends_on:
      db: { condition: service_healthy }
    ports: ["8080:8080"]
  frontend:
    build: ./e-wallet-frontend
    depends_on: [backend]
    ports: ["80:80"]
volumes:
  pgdata:
```

`e-wallet-backend/Dockerfile`:

```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml mvnw ./
COPY .mvn .mvn
RUN ./mvnw -q dependency:go-offline
COPY src src
RUN ./mvnw -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd -r wallet
USER wallet
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

`e-wallet-frontend/Dockerfile` and `nginx.conf`:

```dockerfile
FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
ARG VITE_API_BASE_URL=/api/v1
RUN npm run build

FROM nginx:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
```

```nginx
server {
  listen 80;
  location /api/ { proxy_pass http://backend:8080; }
  location / {
    root /usr/share/nginx/html;
    try_files $uri /index.html;
  }
}
```

Where the Compose file lives is left to the builder. It can sit in the backend root with the frontend as a sibling folder, or in a small third repo.

### Environments and configuration

| Environment | How it runs |
|---|---|
| Local | `docker compose up`, or IntelliJ + `npm run dev` against the `db` container. The database can be dropped and rebuilt at any time. |
| Staging | Same images, deployed from `main` after CI passes. Seeded test agents and customers. Used for demos. |
| Production | Same images, tagged releases only. Managed PostgreSQL with daily backups, a real SMTP or SMS gateway, secrets from the host's secret store, HTTPS at the edge. |

| Setting | Notes |
|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | Per environment |
| `JWT_SECRET` | 256-bit random, different in every environment |
| `APP_MAIL_HOST/PORT/USER/PASS` | SMTP for OTP; Mailpit locally |
| `APP_INITIAL_ADMIN_*` | Seeds the first admin; enable only on a fresh database |
| `VITE_API_BASE_URL` | Frontend build-time; `/api/v1` behind nginx |
| `PLAID_CLIENT_ID`, `PLAID_SECRET`, `PLAID_ENV` | Plaid keys; `sandbox` for the demo |
| `PLAID_IDV_TEMPLATE_ID` | Identity Verification template from the Plaid Dashboard |
| `PLAID_WEBHOOK_URL` | Public HTTPS URL for Plaid webhooks (deployed only) |
| `APP_ENCRYPTION_KEY` | Encrypts stored Plaid access tokens |

The backend ignores `.env` and commits `.env.example`. The frontend commits `.env` and `.env.production`, which hold only the public API URL. No secrets go in either repo.

### Git workflow

Today both repos commit straight to `main` with free-form messages and no CI.

- **`main` is protected and always deployable.** Merges require a pull request and passing CI.
- **Short-lived branches**, one story or fix each, named `feature/US-202-send-money`, `fix/…`, `docs/…` or `chore/…`. There is no long-lived `develop` branch *(my call; small team)*.
- **The flow:** branch from `main` → failing test, then code → push and open a PR that links the story and spec → CI green → one review (a person or Claude code review) → squash merge → delete the branch.
- **Conventional Commits:** `feat(transfer): …`, `fix(cash-out): …`, `test(…)`, `docs: …`, `refactor(…)`, `chore(ci): …`.
- **Releases:** tag `main` as `v0.x.y` when a phase ships. Docker images get the same tag, and release notes are generated from commit types.
- **This design** is committed on `docs/flagship-program-design`. Pushing needs the Claude GitHub App on both repos.

What the history looks like:

```mermaid
gitGraph
    commit id: "v0.1.0"
    branch feature/US-202-send-money
    checkout feature/US-202-send-money
    commit id: "test: transfer limits"
    commit id: "feat: transfer endpoint"
    checkout main
    branch fix/cash-out-pin
    checkout fix/cash-out-pin
    commit id: "fix: customer-initiated withdraw"
    checkout main
    merge fix/cash-out-pin id: "squash fix"
    merge feature/US-202-send-money id: "squash feat"
    commit id: "v0.2.0" tag: "v0.2.0"
```

### CI (GitHub Actions)

```mermaid
flowchart LR
    PR[Pull request] --> BK & FK
    subgraph BK[Backend job]
        B1[Checkout] --> B2[Java 21 + Maven cache] --> B3[Postgres service] --> B4[mvnw verify: tests + Flyway on clean DB]
    end
    subgraph FK[Frontend job]
        F1[Checkout] --> F2[Node 22 + npm ci] --> F3[lint] --> F4[tsc --noEmit] --> F5[build]
    end
    B4 & F5 --> G{All green?}
    G -- yes --> M[Review, then squash merge to main]
    G -- no --> X[Fix on the branch]
```

Backend `.github/workflows/ci.yml`:

```yaml
on: [pull_request, push]
jobs:
  build:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: postgres:16-alpine
        env: { POSTGRES_DB: e_walletDB, POSTGRES_PASSWORD: test }
        ports: ["5432:5432"]
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 21, cache: maven }
      - run: ./mvnw -B verify
        env:
          DB_URL: jdbc:postgresql://localhost:5432/e_walletDB
          DB_USERNAME: postgres
          DB_PASSWORD: test
          JWT_SECRET: ci-only-not-a-real-secret
```

Frontend `.github/workflows/ci.yml`:

```yaml
on: [pull_request, push]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: 22, cache: npm }
      - run: npm ci
      - run: npm run lint
      - run: npx tsc --noEmit
      - run: npm run build
```

### Testing, observability and security baseline

- **Testing:**
  - Unit tests use JUnit 5 + Mockito; there are 74 today, and every money rule gets one.
  - Integration tests use Testcontainers PostgreSQL and assert that journals sum to zero.
  - Frontend tests use Vitest + Testing Library.
  - Playwright end-to-end tests cover register, send, cash-in and withdraw.
- **Observability:** Spring Boot Actuator `/health` for Docker and uptime checks; JSON logs to stdout in containers (today: `logs/app.log`); a request ID echoed in error responses; the daily reconciliation result shown on the admin dashboard.
- **Security:** containers run as a non-root user; HTTPS everywhere outside local; Bucket4j rate limits (already a dependency) on OTP, login and recipient lookup; Dependabot on both repos; no secrets in git.

## What's left to the builder

- Exact migration numbering and order after V47.
- DTO shapes beyond the fields named here, following existing `*Request`/`*Response` records.
- Frontend component breakdown and state management inside each page, following the current feature-folder layout.
- Polling intervals (suggested 5 s for withdrawal confirmation, 30 s for dashboards).

## Answers and remaining question

| # | Question | Answer (23 Nov) |
|---|---|---|
| 1 | What is it for? | Portfolio project and pitch demo; real money later. Plaid as the API provider for now. |
| 2 | Approach | Explained in plain words under Approaches. **C recommended, awaiting your confirmation.** |
| 3 | Withdrawal model | Customer-initiated, as recommended. |
| 4 | Limits | Tiered by Plaid KYC result; demo amounts in Phase 1. |
| 5 | Push access | You'll connect the Claude GitHub App. |

**Still needed from you:** confirm approach C (or pick A or B), and approve this document. After that, the next step is a step-by-step build plan for Phase 0.

**Setup you'll need for Plaid:** a free Plaid Dashboard account, Sandbox keys, Identity Verification enabled for Sandbox (Products page → *Enable Sandbox*; Plaid may ask for a product-access request), and an IDV template.
