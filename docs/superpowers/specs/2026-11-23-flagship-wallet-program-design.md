# Flagship e-Wallet Program (Web now, Mobile next) — Design

Status: **draft for review** (builder-checked). Four questions in the last section are still open and decide the order of work. Everything marked *(my call)* is a proposal you can overturn.

Companion: the visual plan (personas, roadmap, data map, user stories, journeys, code audit) lives on the Design canvas "e-Wallet Product Plan". This document is the written design that implementation plans are built from; the canvas is the picture of it.

## Intent and why

Turn the existing e-wallet (Spring Boot backend, React frontend, PostgreSQL `e_walletDB`) into a flagship web product for three audiences (customers, agents, back-office staff) that feels instant and certain to use and is provably correct with money. The same backend then serves a mobile app with no rewrite.

**Why:** stated so far as "a mind-blowing e-wallet web application, later a mobile app". *Who it is for first (real users with real money, a client, a pitch, or a portfolio) is not yet stated* — see Open questions. It changes the order of work, not the destination.

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

## Goals

- A customer can register, get funded at an agent (cash-in), send, withdraw and prove a payment without staff help, in under 3 minutes for first use and 3 taps + PIN for a send.
- Every screen a user sees shows real data from the API.
- Money cannot be created, lost or double-posted: journals balance, retries are safe, issuance needs two people, reconciliation runs daily.
- One versioned API (`/api/v1`) documented well enough that a mobile client needs no backend changes beyond device/push endpoints.

## Non-goals

- Loans (the frontend page has no backend and no design; hide it until it has a spec of its own).
- Bank transfers, card or mobile-money self top-up, merchant payouts, multi-currency. Funding stays agent cash-in *(my call, from builder check)*.
- Replacing the existing ledger, wallet model, or auth model.

## Anti-goals (would be failure even if it "works")

- Any flow where one party can see or enter another party's PIN.
- A dashboard that looks live but shows sample data.
- A feature that moves money without a balanced journal, a `transactions` row, and a test proving both.
- A web-only shortcut that the mobile app would have to undo.

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

**Picked:** *pending your choice* (Open question 2).

## Design

### Phase 0 — Connect and harden

**0.1 Withdrawal without PIN sharing.** Replace `customerPin` in the agent's cash-out request.
Proposed model *(needs your pick, Open question 3)*: **customer-initiated withdrawal** — the customer enters the agent's float account number and the amount in their own app, sees the fee, and confirms with their own PIN. The agent's screen shows the confirmed withdrawal (polling) and the agent pays cash. Posting legs stay exactly as today's `cashOut`. Alternative: agent-initiated request that the customer approves in-app within 2 minutes (better at the counter, needs a pending state and expiry).

**0.2 Idempotency.** Every money `POST` (`cash-in`, `cash-out`/withdraw, transfer, treasury mint/top-up/approve) requires an `Idempotency-Key` header. New table `idempotency_keys(user_id, key, endpoint, request_hash, response_json, status, created_at)` with a unique `(user_id, key)`. Same key + same body → stored response; same key + different body → 409.

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
- **Limits** *(mechanism my call; amounts yours, Open question 5)*: per-transaction maximum and daily total per customer for transfer and withdraw, read from configuration (`application.properties`), enforced in one shared `LimitService`. KYC-tiered limits come later with KYC review (Phase 3).
- **Receipts** *(my call)*: shared as an image or PDF generated in the app; no public verification link (that would be an unauthenticated endpoint and a separate security decision).
- Saved recipients (`beneficiaries`) *(my call to include here; cheap and high-delight)*.

### Phase 2 — Agent experience

Agent portal on the existing endpoints: cash-in, confirmed-withdrawal queue, float balance with low-float alert, float request with status timeline, daily summary and commission. Agent locator for customers by region → district.

### Phase 3 — Back office trust

- **Maker-checker for issuance** *(scope my call)*: `mint` and commission top-up become two-step (one staff member requests, a different one approves; new pending state). Agent float requests already have two parties (agent asks, staff decides) and stay single-approval.
- Tariff management (create/edit with effective dates, no overlapping bands, quote preview).
- `audit_logs` for every staff action and status change (who, what, before/after, when).
- Daily reconciliation job: per wallet, sum of journal lines = balance; variances surface on the dashboard. V26 seeded balances without journals; because the dev database is rebuilt (Decision 3) and all money enters via mint journals, this holds from a fresh database. Any non-rebuilt environment needs an opening-balance journal migration first.
- Customer 360 for support; lock/unlock wallet with reason.

### Phase 4 — Delight

Real-time notifications (introduce SSE here, the first feature that needs push), request money and QR, savings vaults, bills/airtime, insights and statements, Swahili, dark mode. Each gets its own spec.

### Phase 5 — Mobile

Mobile client on the same `/api/v1`; adds only `devices` (binding, push token) and biometric unlock after a PIN login on a bound device. Shared design tokens and validation rules with web.

## Cross-cutting rules

- Every money flow: one `@Transactional` method → lock wallets in id order → validate → move balances → `postJournal` → write `transactions` row → store idempotent response.
- Every endpoint has a permission mapped to a module; every staff action writes an audit entry (from Phase 3).
- Every UI flow works at phone width and by keyboard; copy is ready for English and Swahili.

## What's left to the builder

- Exact migration numbering and order after V47.
- DTO shapes beyond the fields named here, following existing `*Request`/`*Response` records.
- Frontend component breakdown and state management inside each page, following the current feature-folder layout.
- Polling intervals (suggested 5 s for withdrawal confirmation, 30 s for dashboards).

## Open questions (yours — these change what gets built)

1. **What is this for, and who uses it first?** Real customers with real money, a client, a pitch/demo, or a portfolio/academic project.
2. **Which approach: A, B or C?** (Recommended: C.)
3. **Withdrawal model:** customer-initiated in their own app (recommended; simpler, no pending state), or agent-initiated with customer approval in-app?
4. **Limit amounts:** per-transaction and daily maximums for transfer and withdraw (TZS), and whether they differ for NIDA-verified customers. A business/regulatory call I shouldn't invent.
5. **Push access:** this session can read both repos but not push yet; until the Claude GitHub App is connected, changes arrive as patches you apply in IntelliJ.
