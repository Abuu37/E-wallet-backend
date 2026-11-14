# Full ERD Alignment — users, staff, customers, agents, wallets, treasury, tariffs, transactions — Design

## Intent and why

The ERD you shared describes a complete identity and money-movement model. The wallet/ledger/treasury work already landed brought `wallets` close to it. Everything else — `users`, `customers`, `agents`, the entirely-missing `staff`, `tariffs`, and `transactions` — still doesn't match. This doc lays out the full gap, entity by entity, and the target shape for all of it as one body of work, per your instruction to lay it all out as one big plan rather than phase it.

## Decisions made in discussion (yours, recorded so they aren't re-litigated)

1. **No `password_hash`, anywhere.** The ERD's `users.password_hash` is explicitly excluded — PIN is the only credential, confirmed twice now.
2. **`staff` is a new entity/module, representing admin/back-office users** — the ERD's profile type that the running system never built. Every current `UserEntity` with the ADMIN role (including the one `DataInitializer` seeds) gets a `staff` profile, the same way customers and agents already get theirs.
3. **Rearrange to follow the ERD's structure** — applies across every entity below, not just `users`.
4. **Remove `wallets.externalReference`** — it's not in the ERD; drop it.
5. **The dev database gets dropped and recreated, not migrated with real data preserved.** This is a local dev database with no production data at stake, and the scope here (stripping names off `users`, collapsing roles, adding KYC fields, a new `staff` table) is large enough that building every migration as a data-preserving backfill would multiply the work and the risk for no benefit. Every new migration below can therefore just `CREATE`/`ALTER` directly — no backfill logic, no "does any user already hold two roles" check, no nullable-then-lock-down two-step. A fresh `pg_dump` backup is taken before anything runs, same precaution as the wallet/treasury work. The *existing* V1–V31 migrations are untouched and still replay in full on the empty database to rebuild today's shape before the new migrations build on top.

## Full entity-by-entity target

### `users` — stripped to identity/credentials only

The ERD keeps `users` lean: no names. Names belong to whichever profile (`customers`/`staff`/`agents`) the user has. Current `UserEntity` puts `firstName`/`middleName`/`lastName` directly on the user, which this removes.

| Column | Change |
|---|---|
| `id`, `email`, `phone`, `pinHash`, `createdAt`, `updatedAt` | kept |
| `status` → `accountStatus` | kept, matches ERD's `account_status` in spirit (rename optional, not required) |
| `reference` | **new** — UUID, per ERD |
| `username` | **new** — ERD has it; current system has never used one, identifying by email/phone instead. Added as a real column; login continues to accept email/phone as it does today unless you want username-based login added too (not assumed here) |
| `lastLoginAt` | **new** — needs a write-point added in `AuthService`'s login method |
| `firstName`/`middleName`/`lastName` | **removed** — moves to `customers`/`staff`/`agents` |
| `roles` (`@ManyToMany` via `user_roles`) → `role` (single FK) | **changed — flagged below, this is the one with real behavioral impact** |

**Collapsing to one role per user.** The ERD's `role_reference` is singular. The running schema has a real `user_roles` many-to-many join table (since `V4`), but in practice every onboarding path (`AgentService.createAgent`, customer registration, `DataInitializer`'s seed admin) assigns exactly one role at creation — nothing in the current codebase ever gives a user two roles. Confirmed per "rearrange based on what I shared": drop `user_roles`, add a single `role` FK directly on `users`. Since the database is empty when this migration runs (decision 5), there's no existing multi-role data to check for — this is a clean `ADD COLUMN` + `DROP TABLE user_roles`, not a backfill.

### `staff` — new

| Column | Notes |
|---|---|
| `id`, `reference` | UUID |
| `user` | FK → `users`, one-to-one, same pattern as `customers`/`agents` |
| `staffNo` | generated, same style as `agentNumber`/`customerNumber` — identifies *who* |
| `jobTitle` | **new, added at your request** — e.g. "Branch Manager", "Compliance Officer", "System Administrator". Not redundant with `staffNo`: one is a unique identifier, the other describes the role. The bootstrap admin `DataInitializer` seeds gets `"System Administrator"` hardcoded (no new `.env` property for one row) unless you'd rather make it configurable |
| `firstName`, `middleName`, `lastName`, `email`, `phoneNumber` | per ERD — the profile's own copy, not read off `users` |
| `createdBy` | FK → `users` — who onboarded this staff member. The ERD shows `users` "creates" both `staff` and `agents`; today nothing records who created an agent either (see below) |
| `status`, `createdAt`, `updatedAt` | kept |

A `StaffService`/`StaffController` analogous to `AgentService`/`AgentController` is needed: onboard, list, get, update, deactivate. `DataInitializer`'s seeded admin needs a matching `StaffEntity` row, not just a bare `UserEntity`.

### `customers` — expand from almost nothing

Current `CustomerEntity` has only `customerNumber` + `status`. The ERD's KYC fields don't exist on it at all today.

| Column | Notes |
|---|---|
| `id`, `reference`, `user`, `customerNumber`, `status`, `createdAt`, `updatedAt` | kept |
| `firstName`, `middleName`, `lastName`, `email`, `phoneNumber` | **new** — moves off `users`, matches the pattern already used for `agents` |
| `nidaNumber`, `dateOfBirth`, `address` | **new** — collected nowhere today. `CustomerRegistrationRequest` only asks for name/email/phone currently; this adds the three missing fields to that same request, required at registration — consistent with how agent onboarding already requires `dateOfBirth`/`gender` upfront, rather than inventing a separate deferred-KYC flow you didn't ask for |

### `agents` — add what's missing, keep what's already better than the ERD

| Column | Notes |
|---|---|
| `id`, `agentNumber`, `region`, `district`, `wallet` link, `createdAt`, `updatedAt` | kept |
| `firstName`, `middleName`, `lastName`, `email`, `phoneNumber` | **new** — moves off `users`, matches `AgentCreationRequest`'s existing fields, just stored on the right entity now |
| `reference` | **new** |
| `businessName`, `tinNumber` | **new** — per ERD, collected nowhere today |
| `agentStatus` | **new** — a dedicated status, decoupled from `user.status`. **Resolved (builder-check flagged the values/lifecycle as undefined):** enum `ACTIVE, SUSPENDED, DEACTIVATED` — an operational standing, separate from whether the user can log in. `deactivateAgent` sets *both* `user.status = DISABLED` (blocks login) and `agentStatus = DEACTIVATED` (reflects standing) — they answer different questions and aren't mutually exclusive. Every agent response DTO (`AgentDetailResponse`, `AgentSummaryResponse`, `AgentOnboardResponse`) switches from reporting `user.getStatus()` to `agent.getAgentStatus()` — this is the exact bug both reviews independently flagged (today they report the user's status as if it were the agent's) |
| `createdBy` | **new — FK → `users`** (not `staff`; builder-check correctly caught "`users`/`staff`" as ambiguous in an earlier draft). Matches the existing `treasury_requests.decidedBy` precedent — every "who did this" column in this system points at the acting `users` row, never a profile table |
| `gender`, `dateOfBirth` | **kept, deliberately** — not in the ERD's `agents` table at all, but this is real KYC data already being collected; removing it would be a regression, not ERD alignment. Flagging so it's a visible choice, not an oversight |
| normalized `region`/`district` FKs (vs. the ERD's flat `location` string) | **kept, deliberately** — a normalized lookup is strictly better than a free-text field; not reverting a good design to chase ERD literalism |

### `wallets` — small adjustment plus the removal you asked for

| Column | Notes |
|---|---|
| `id`, `user`, `walletType`, `balance`, `status`, `version`, `createdAt`, `updatedAt` | kept |
| `externalReference` | **removed**, per your instruction |
| `reference` | **new** |
| `accountName` | **new** — the ERD has both a human-readable `account_name` and a bare `account_number`; today there's no name, just numbers. **Resolved (builder-check flagged the convention as unstated):** customer wallet → the customer's full name; agent's float/commission wallets → `"<agent full name> — Float"` / `"— Commission"`; the three company-level wallets → the fixed labels already chosen when they were seeded (`"Company Master Account"`, `"Company Revenue"`, `"Company Escrow Bank"`) |
| **`walletNumber` vs `accountNumber`** | **flagged decision, not yet made for you.** These two columns hold the *same value* in every current creation path (confirmed in code — a customer's wallet sets both to their phone number; an agent's float wallet sets both to the same generated code). The ERD has exactly one number field. I'd lean toward collapsing them into one `accountNumber` column, removing a real redundancy — but this renames a column referenced by a unique index and by `WalletResponse`/`AgentOnboardResponse`/etc. across several files, so I want your yes before doing it rather than assuming. |

### `treasury_requests` (the ERD calls it `agent_requests`)

| Column | Notes |
|---|---|
| `id`, `requestRef`, `agent`, `amount`, `status`, `decidedBy`, `decidedAt`, `requestType`, `createdAt`, `updatedAt` | kept |
| `externalReference` | **new** — e.g. a bank deposit slip reference backing a `TOPUP` request. The ERD has this; today there's no way to attach outside evidence to a request |
| `reason`, `decisionNote` | **kept, not in the ERD** — genuinely useful (why the agent is asking, why the admin rejected), not removed just because the ERD doesn't show them |
| `sourceWalletAccount` (ERD field) | **not added** — every current request implicitly targets the agent's own `AGENT_FLOAT` wallet; there's no scenario yet where a request needs to name a different wallet. Add it if/when that changes |

### `tariffs` — new, matching the ERD directly

`id`, `reference`, `transactionType`, `minAmount`, `maxAmount`, `fee`, `agentCommission`, `active`, `createdAt`.

### `transactions` — new, the cash-in/cash-out/transfer module deferred twice already

`id`, `referenceNo`, `senderWalletId` (FK → `wallets`), `receiverWalletId` (FK → `wallets`), `amount`, `fee`, `commission`, `transactionType`, `status`, `createdAt`. Uses real wallet FKs rather than the ERD's plain account-number strings, consistent with how `treasury`/`ledger` already do this.

**Resolved (builder-check flagged this as ambiguous): how this relates to the existing double-entry ledger.** `transactions` is the customer/agent-*facing* record — one row per cash-in/cash-out/transfer, what a receipt or a transaction-history screen shows. `transaction_journal`/`transaction_journal_line` stays the internal, universal double-entry audit trail every money movement posts to — `treasury` already does this for mint/topup/withdraw. When cash-in/cash-out gets built, each operation does both: one `transactions` row (customer-facing) and one balanced journal entry via the existing `LedgerService` (internal audit), exactly mirroring how `treasury` already works. Not two competing systems of record — `transactions` is a view-layer record, the journal is the ledger of truth.

### `modules`, `permissions.moduleReference` — new, lower priority

The ERD groups permissions under `modules` (e.g. a "WALLET" module owning `WALLET_VIEW`, a "TREASURY" module owning `FLOAT_MANAGE`, etc.). Nothing like this exists today — permissions are a flat list of ~30 strings. This is pure organization, not a security or correctness fix, so it's included for completeness but is the one piece I'd suggest doing last or dropping if the rest of this is already enough work.

## Non-goals

- Changing how login identifies a user (email/phone + PIN) — `username` is added as a column because the ERD has it, but login behavior isn't changing unless you ask for that separately.
- Building `tariffs`/`transactions`' actual business logic (fee calculation, cash-in/cash-out flows) beyond the table shape — this doc gets the data model in place; wiring it into real money movement is further work.

## What's left to the builder

- Exact migration sequencing (this touches `users`, `customers`, `agents`, adds `staff`/`tariffs`/`transactions`/`modules` — a lot of Flyway files, ordered so nothing breaks mid-sequence, same discipline as the wallet/treasury work). Since the database is empty when these run (see decision 5), no backfill logic is needed anywhere, including for `username` — it can be `NOT NULL` from the first migration that adds it.
- `StaffService`/`StaffController`'s exact endpoints, mirroring `AgentController`'s shape.
