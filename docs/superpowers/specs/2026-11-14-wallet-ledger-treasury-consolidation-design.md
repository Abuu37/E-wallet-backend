# Wallet, Ledger & Treasury Consolidation — Design

## Intent and why

The DFD and ERD shared for this redesign both describe **one** money
container concept: a `wallets` table with a `wallet_type` enum
(`COMPANY_MASTER_ACCOUNT`, `REVENUE`, `AGENT_FLOAT`, `AGENT_COMMISSION`,
`CUSTOMER_MAIN`), balance sitting directly on it. The running codebase
has drifted from that into **four different, disagreeing taxonomies**
for "what kind of account is this":

| Where | Type enum | Values |
|---|---|---|
| `wallet.WalletEntity` | *(none — identity only, no balance)* | — |
| `financial_account.FinancialAccountType` | `FinancialAccountType` | `CUSTOMER_WALLET, AGENT_FLOAT, FEE_REVENUE, LOAN_RECEIVABLE, SYSTEM, SUSPENSE` |
| `company_wallet.CompanyMasterWalletEntity` | *(none — bespoke singleton, not an enum member at all)* | — |
| `ledger.LedgerAccountType` | `LedgerAccountType` | `ESCROW_BANK, COMPANY_UNALLOCATED, COMPANY_DISTRIBUTED, AGENT_FLOAT, CUSTOMER_WALLET` |

None of these match each other or the ERD. This doc collapses them into
the one shape the ERD/DFD actually specify, and gives every module a
name that says what it does.

**This supersedes the architecture in
`docs/superpowers/specs/2026-11-08-company-master-wallet-design.md`.**
That doc was written without the ERD/DFD in hand and reasonably invented
`FinancialAccountType`/`LedgerAccountType`/a singleton company entity
from the seeded permissions alone. With the real target model now
available, that invented structure is exactly the duplication being
removed here.

## Decisions made in discussion (yours, recorded so they aren't re-litigated)

1. Company master, revenue, and agent-commission accounts are **ordinary
   rows** in the same `wallets` table as customer and agent wallets — no
   separate singleton entity, uniform shape for everything.
2. The agent float-request inbox (agent asks, admin approves/rejects)
   moves out of `agent` into `treasury` — it's a treasury decision, not
   agent profile data.
3. OTP-at-registration (`PENDING` → OTP sent → verify → `ACTIVE`, via
   `UserRegistrationListener`) is already correct and is **not** touched
   by this work.

## Goals

- One wallet concept, one `wallet_type` enum, matching the ERD/DFD exactly.
- One ledger; journal lines reference a wallet directly — no second
  parallel account-type enum.
- One `treasury` module owning every admin-gated float movement: mint,
  allocate, withdraw/reclaim, and the request/approval inbox (now
  covering both top-up and withdrawal requests — see "Found gap" below).
- Lay the correct foundation for the **not-yet-built** customer-facing
  pieces (`transaction`: cash-in/cash-out/internal transfer/commission
  transfer; `tariff`: the fee table) so they get built once, correctly,
  instead of inventing a fifth taxonomy later.
- Module names that tell you what they do without opening the folder.

## Non-goals (explicitly out of scope here)

- The login/auth model (PIN serves as the credential; there is no
  separate password in the running code, unlike the ERD's
  `password_hash` column). Not raised as broken — left alone.
- Building the actual cash-in/cash-out/transfer/tariff business logic.
  This doc lays the data model and module shape; the logic is a
  follow-on plan.

## Noted, not in scope — flag for a later pass

Every sensitive action in the DFD (cash-in, cash-out, withdrawal,
internal transfer) is gated by **PIN only**. For an e-money system this
is a real exposure (SIM-swap / shoulder-surfed PIN = full account
access, no second factor). OTP already exists and works well at
registration; extending it as a step-up check on high-value or
first-time-device transactions is worth a dedicated security pass later.
Not addressed here because it wasn't asked for and deserves its own
design (thresholds, UX for re-entering an OTP mid-transaction, etc.).

## Anti-goals

- Never reintroduce a second taxonomy for "what kind of account is
  this." Any future branching on account purpose branches on
  `wallet_type`, full stop.
- `treasury` must mutate balances through `wallet`'s own service/
  repository, the same as any other caller — never reach into its
  internals directly.

## Target data model

### `wallets` (consolidated — absorbs `financial_accounts` and `company_master_wallet`)

| Column | Notes |
|---|---|
| `id` | UUID |
| `user_id` | FK → `users`, **NOT NULL for every row**, including company-level ones (owned by one seeded system/admin user) |
| `wallet_type` | enum: `COMPANY_MASTER_ACCOUNT, REVENUE, AGENT_FLOAT, AGENT_COMMISSION, CUSTOMER_MAIN, ESCROW_BANK` — the 6th value is new, decided below under "Ledger note" |
| `wallet_number` / `account_number` | existing identifier columns, kept |
| `currency` | existing, kept |
| `balance` | NUMERIC(19,2), `CHECK (balance >= 0)` — new; this is what `financial_accounts.current_balance` was |
| `status` | existing `WalletStatus`, kept |
| `version` | new — optimistic lock, matches the ERD |
| `external_reference` | new, nullable — replaces `company_master_wallet.escrow_bank_account_no`; generically reusable later for any wallet with an external counterpart |
| `created_at` / `updated_at` | kept |

**Ownership invariant, enforced at the DB level** (same philosophy as the
existing `chk_wallets_exactly_one_owner`): a **partial unique index**
ensures at most one row each for `COMPANY_MASTER_ACCOUNT`, `REVENUE`,
and `ESCROW_BANK`:

```sql
CREATE UNIQUE INDEX uk_wallets_singleton_company_types
ON wallets (wallet_type)
WHERE wallet_type IN ('COMPANY_MASTER_ACCOUNT', 'REVENUE', 'ESCROW_BANK');
```

No customer/agent-onboarding code path is ever allowed to create any of
these three types — only the seed migration does.

**Ledger note, decided:** `ESCROW_BANK` is a 6th `wallet_type`, not a
tagged pseudo-account. Reason: once journal lines are a plain `wallet_id`
FK (see below), `mint`'s old two-leg entry — `ESCROW_BANK` debit /
`COMPANY_UNALLOCATED` credit, both pointing at the *same* id because
`ESCROW_BANK` had no real row — would resolve both legs to the literal
same wallet and self-cancel. Giving it a real row (seeded at `balance =
0`, same seeded system user, never mutated by anything except `mint`'s
debit leg) makes `mint` a real two-wallet journal entry like every other
operation, and keeps the "everything is a wallet row" rule with no
exceptions.

**Ownership columns, settled:** today's `wallets.customer_id`/`agent_id`
(each `UNIQUE`, plus `chk_wallets_exactly_one_owner` from `V15`) are
**dropped**, not kept alongside `user_id`. They can't coexist with "an
agent has two wallets" (`AGENT_FLOAT` + `AGENT_COMMISSION`) — `UNIQUE
(agent_id)` makes a second row for the same agent impossible. Ownership
becomes `user_id` + `wallet_type` only, with:

```sql
CREATE UNIQUE INDEX uk_wallets_user_type ON wallets (user_id, wallet_type);
```

`WalletEntity`'s `@OneToOne` mappings to `CustomerEntity`/`AgentEntity`
go away with the columns. Every current single-wallet-per-owner lookup
(`WalletRepository.findByAgentId`, `findByCustomerId`, used in
`WalletService`, `AgentService`, `AgentFloatRequestService`) becomes
`findByUserIdAndWalletType`. `FinancialAccountRepository.findByWallet_Agent_Id`
(used by `treasury`'s allocate/withdraw to find an agent's float
balance) becomes `WalletRepository.findByUser_IdAndWalletType(agentUserId,
AGENT_FLOAT)`.

**`version`, settled:** it's there for ERD parity and future use, not
load-bearing yet. Concurrency control stays exactly as it works today —
pessimistic row locking (`findSingletonForUpdate`'s pattern, generalized
to lock the specific wallet row being mutated) — not JPA optimistic-lock
retries. Revisit if/when `transaction` (cash-in/cash-out) needs
higher-concurrency, lock-free updates.

**Found gap, carried into this design rather than hidden:** `AGENT_COMMISSION`
has no balance-holder anywhere in the current code (not in
`FinancialAccountType`, not in `LedgerAccountType`). It's net-new, not a
migration of existing data — every agent gets a second wallet row
(`AGENT_COMMISSION`, balance `0`) created alongside their existing
`AGENT_FLOAT` wallet. Same for `REVENUE` — no revenue has ever been
tracked; it's seeded at `0`.

### `transaction_journal_line` (simplified)

Drop `ledger_account_type` and the untyped `account_ref_id`. Replace
with a single real foreign key:

| Column | Notes |
|---|---|
| `wallet_id` | FK → `wallets(id)`, NOT NULL — replaces both old columns |
| `entry_type`, `amount` | unchanged |

The wallet's own `wallet_type` already says what kind of account a line
touched — no need to say it twice.

### `treasury_requests` (renamed + extended from `agent_float_requests`)

Moves from the `agent` package into `treasury`. Renamed because it no
longer belongs conceptually to "agent" — it's a treasury decision queue.

**Found gap:** `V23` added a `type` column (`TOPUP`/`WITHDRAWAL`) to
unify direct admin top-ups/withdrawals into this same audit trail, then
`V24` reverted it. As a result, **this table today only ever represents
agent-initiated top-up requests** — there is no agent-submitted
withdrawal-request flow at all (the DFD's "5.0 Agent Withdrawal
Settlement" / D1 store isn't built). `AgentService.withdrawFloat` exists
only as a direct admin action with no request behind it, called straight
through `CompanyMasterWalletService.withdraw`.

This design reintroduces the type column, done properly this time:

| Column | Notes |
|---|---|
| `id`, `request_ref`, `agent_id`, `amount`, `reason`, `status`, `decided_by`, `decision_note`, `decided_at`, `created_at`, `updated_at` | unchanged from `agent_float_requests` |
| `request_type` | new: `TOPUP` \| `WITHDRAWAL`. Existing rows backfill to `TOPUP` (they already are, today) |

`approveRequest` dispatches on `request_type`: `TOPUP` → `treasury.allocate`,
`WITHDRAWAL` → `treasury.withdraw`. One approval code path, two outcomes,
instead of a second table later.

### `transactions` and `tariffs` (new — not built yet, foundation only)

Sketched so the eventual `transaction`/`tariff` modules build on the
corrected model instead of inventing a fifth taxonomy:

- `transactions`: `id`, `reference_no`, `sender_wallet_id` (FK `wallets`),
  `receiver_wallet_id` (FK `wallets`), `amount`, `fee`, `commission`,
  `transaction_type` (`CASH_IN, CASH_OUT, INTERNAL_TRANSFER, COMMISSION_TRANSFER`),
  `status`, `created_at`. Uses real wallet FKs rather than the ERD's
  plain varchar account numbers, for the same reason the ledger now does.
- `tariffs`: `id`, `transaction_type`, `min_amount`, `max_amount`, `fee`,
  `agent_commission`, `active`, `created_at` — matches the ERD directly.

## Target module map

| Module | Owns | Change |
|---|---|---|
| `wallet` | `WalletEntity` (now with `wallet_type`, `balance`, `version`), `WalletType`, `WalletStatus`, repo/service/controller/dto | Absorbs the balance and type that `financial_account` held |
| `ledger` | `TransactionJournalEntity`, `TransactionJournalLineEntity` (now `wallet_id`-keyed), `TransactionType`, `EntryType` | Drops `LedgerAccountType` |
| `treasury` | *(renamed from `company_wallet`)* mint/allocate/withdraw, **plus** `TreasuryRequestEntity` (renamed from `AgentFloatRequestEntity`), `RequestType`, `RequestStatus`, repo/service/controller/dto | Absorbs the float-request inbox from `agent`; name no longer collides with "wallet" |
| `transaction` *(new)* | cash-in, cash-out, internal transfer, commission-to-float transfer | Not built yet — this doc is its foundation |
| `tariff` *(new)* | fee/commission lookup by transaction type | Not built yet |
| `agent` | `AgentEntity` profile only | Loses `AgentFloatRequestEntity` and everything float-related |
| `financial_account` | — | **Deleted** |
| `company_wallet` | — | **Deleted** (renamed to `treasury`, not kept alongside it) |

**API surface:**

- `/api/v1/company-wallet/*` → `/api/v1/treasury/*`
- `/api/v1/float-requests/*` → `/api/v1/treasury/requests/*`

## Decided after builder-check

1. **`ESCROW_BANK` is a 6th `wallet_type`** — see "Ledger note" above.
2. **Both admin bypass paths close.** `CompanyMasterWalletController`'s
   `/allocate`/`/withdraw` *and* `AgentController`'s `/float/topup`/
   `/float/withdraw` are removed entirely. `AgentService.topupFloat`/
   `withdrawFloat` become internal-only methods, called solely from
   `TreasuryRequestService.approveRequest` (never from a controller
   directly) — the only way float moves to or from an agent is through
   an approved `treasury_requests` row. `mint` stays admin-direct (no
   agent involved, nothing to request against).

## Convention, added at your request: no shared request/response DTOs across actions

You flagged a mistake already in the codebase: `agent.dto.AgentResponse`
is one record reused as the response for every `AgentController`
endpoint — onboard, list, get-one, update, and (until the decision
above removed them) both float endpoints. The tell that it's the wrong
shape for several of its callers is in the record itself — two fields
are literally commented as fake:

```java
// No commission model yet; placeholder until that feature exists.
BigDecimal commission,
// No transaction history wired to agents yet; placeholder until that feature exists.
long transactionCount,
```

They exist only because *some* consumer of the shared type might one day
want them — not because the action actually responding produces them. A
response DTO should contain exactly what its one action returns, nothing
contributed by a different action's needs.

**Fix for `agent`:** split `AgentResponse` into four, one per remaining
endpoint:

| Endpoint | New response | Shape |
|---|---|---|
| `POST /onboard` | `AgentOnboardResponse` | identity/profile fields + the freshly-provisioned wallet/account numbers + status. No balance, commission, or transaction count — none of that exists yet for a brand-new agent. |
| `GET /{agentId}` | `AgentDetailResponse` | full profile + wallet/account numbers + balance + float health. Omits `commission`/`transactionCount` entirely until `transaction`/`tariff` exist — no placeholder fields. |
| `GET /` (list) | `AgentSummaryResponse` | the lighter row a table view needs: id, agentNumber, name, phone, region, district, status, balance — not the full detail `AgentDetailResponse` carries. |
| `PUT /{agentId}` | `AgentUpdateResponse` | just the profile fields that were updated, confirmed back. Not balance/wallet state — update doesn't touch those. |

**The rule, generalized** — applies to `wallet`, `treasury`,
`transaction`, and `tariff` going forward: every controller method gets
its own named request and response type, scoped to exactly what that
action needs. A field that exists "because another endpoint happens to
want it," or a placeholder comment like the two above, is the signal
that two actions are sharing a type that shouldn't be shared.

**One legitimate exception**, so the rule isn't over-applied: a single
resource's own representation can be reused across the endpoints that
just report *that resource's current state*. `TreasuryRequestResponse`
returned by create/list/get/approve/reject on a `treasury_requests` row
is fine — all five really are "here is this one request, as it stands
now," not unrelated actions forced into one shape. The anti-pattern is
sharing a DTO across actions that aren't the same resource (an agent's
profile update and an agent's float movement, say) just because both
happen to touch "an agent."

## Migration plan (Flyway, in order)

1. **Add columns to `wallets`**: `user_id` (nullable), `wallet_type`
   (nullable), `balance` (nullable), `version`, `external_reference`.
2. **Backfill**, by wallet type:
   - Customer wallets: `user_id` = `customers.user_id` (via existing
     `wallets.customer_id`); `wallet_type = 'CUSTOMER_MAIN'`;
     `balance` = the matching `financial_accounts.current_balance`.
   - Agent wallets (existing row = float): `user_id` = `agents.user_id`;
     `wallet_type = 'AGENT_FLOAT'`; `balance` from `financial_accounts`.
   - **Insert new rows**: one `AGENT_COMMISSION` wallet per agent,
     `balance = 0` (net-new, per "Found gap" above).
   - **Insert the `COMPANY_MASTER_ACCOUNT` row reusing the existing
     `company_master_wallet.id` as its primary key** — this is what makes
     step 4 a simple backfill instead of a remap: existing journal lines
     already use that id as `account_ref_id`. `balance` =
     `company_master_wallet.unallocated_float`; `external_reference`
     = `escrow_bank_account_no`; `user_id` = one seeded system/admin
     user. (`total_distributed_float` isn't migrated as a column — it's
     a derived metric, recomputed as `SUM(balance) WHERE wallet_type = 'AGENT_FLOAT'`.)
   - **Insert one `REVENUE` row**, `balance = 0`, same seeded system user
     (net-new — nothing tracked revenue before).
3. **Lock it down**: `NOT NULL` on the backfilled columns, the
   `CHECK (balance >= 0)`, and the partial unique index from above. Drop
   `customer_id`, `agent_id`, their two `UNIQUE` constraints, and
   `chk_wallets_exactly_one_owner`; add
   `CREATE UNIQUE INDEX uk_wallets_user_type ON wallets (user_id, wallet_type)`.
4. **Migrate the ledger**: add `wallet_id` to `transaction_journal_line`.
   Backfill: rows tagged `AGENT_FLOAT` → join `account_ref_id` against
   the *old* `financial_accounts.id` to get `financial_accounts.wallet_id`.
   Rows tagged `ESCROW_BANK`/`COMPANY_UNALLOCATED`/`COMPANY_DISTRIBUTED`
   → `account_ref_id` already *is* the right `wallets.id` (see step 2's
   id-reuse trick — zero-transform). Then drop `ledger_account_type` and
   `account_ref_id`, make `wallet_id` `NOT NULL`.
5. **Drop** `financial_accounts` and `company_master_wallet` tables.
6. **Rename** `agent_float_requests` → `treasury_requests`; add
   `request_type VARCHAR(20) NOT NULL DEFAULT 'TOPUP'` with a check
   constraint, matching the existing V23/V24 history (this time, keep it).

Application code follows the same order: consolidate `wallet`, simplify
`ledger`, rename `company_wallet` → `treasury` and move the request
classes into it, update `AgentController`/`AgentService` to drop the
float-request pieces, update all `FinancialAccountRepository` call sites
to use `WalletRepository` instead, delete `financial_account` and the old
`company_wallet` package.

## My calls, flagged for your review

1. `treasury_requests` / `treasury` naming (not `agent_float_requests` / `agent`).
2. Singleton enforcement for `COMPANY_MASTER_ACCOUNT`/`REVENUE` via a
   partial unique index rather than a dedicated entity.
3. Creating a real `AGENT_COMMISSION` wallet per agent now, even though
   nothing populates it yet (no commission-earning logic exists until
   `transaction`/`tariff` are built) — so the wallet model doesn't need a
   second migration when that logic lands.
4. Reusing `company_master_wallet.id` as the new wallet's id specifically
   to make the ledger backfill a no-op for that side.
5. Dropping `wallets.customer_id`/`agent_id` and their constraints in
   favor of `user_id` + `UNIQUE (user_id, wallet_type)` — required by
   "an agent has two wallets," not optional.
6. `wallets.version` is ERD-parity only for now; pessimistic row locking
   stays the real concurrency mechanism, unchanged from today.

## What's left to the builder

- Exact entity/class renames and package moves (mechanical, per the
  module map above).
- New/updated DTOs: `TreasuryRequestResponse` gains `requestType`;
  request-creation DTO needs a `requestType` field for the withdrawal
  case.
- Splitting `AgentResponse` into `AgentOnboardResponse`,
  `AgentDetailResponse`, `AgentSummaryResponse`, `AgentUpdateResponse`
  per the DTO convention above, and updating `AgentController`/
  `AgentService` call sites accordingly.
- Deleting `AgentController`'s `/{agentId}/float/topup` and
  `/{agentId}/float/withdraw` endpoints (and their now-unused
  `FloatTopupRequest`/`FloatWithdrawRequest` DTOs, unless reused as the
  internal parameter types for the now-internal-only service methods).
- Seed migration specifics: the system/admin user used as `user_id` for
  company-level wallets — reuse an existing seeded admin user if one
  exists, or create a dedicated `SYSTEM` user; builder's call, not
  decided here.
- Wiring `treasury`'s withdrawal request type to call `withdraw` the same
  way `TOPUP` already calls `allocate`.
- The `transaction` and `tariff` modules themselves — separate follow-up
  plan, not part of this doc.
- Tests covering the migration backfill (balances must sum identically
  before/after) and the new partial unique index.
