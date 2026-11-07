# Company Master Wallet — Design

## Intent

The platform needs a company-owned "trust" account that backs the float
given out to agents, plus an auditable record of every movement of that
float. Today, agents already get a `FinancialAccountEntity`
(`AGENT_FLOAT`) when onboarded (see `AgentService.java:105`), but nothing
represents the company's own pool of float, and nothing records how float
moves between the company and its agents.

This was confirmed against the existing schema, not invented: `V6` already
seeds permissions `COMPANY_WALLET_VIEW`, `FLOAT_MANAGE`, `LEDGER_VIEW`,
`RECONCILIATION_VIEW`/`MANAGE`, and `FinancialAccountType` already has
unused `AGENT_FLOAT`, `FEE_REVENUE`, `SYSTEM`, `SUSPENSE` values. The
schema was designed with this feature in mind; it was never built.

## Goals

- A single company master wallet holding `unallocatedFloat` (not yet
  given to any agent) and `totalDistributedFloat` (the live outstanding
  balance currently held by agents — incremented on allocate, decremented
  on withdraw — equal to the sum of all agents' current float balances
  at any point in time, not an immutable lifetime counter).
- Three operations, each fully auditable:
  - **Mint** — record that real money landed in the company's escrow bank
    account, increasing `unallocatedFloat`.
  - **Allocate** (a.k.a. agent top-up — confirmed as the same operation)
    — move float from the unallocated pool to a specific agent's existing
    `AGENT_FLOAT` financial account.
  - **Withdraw** — an agent returns float to the unallocated pool (reverse
    of allocate).
- A real double-entry ledger: every operation posts a journal with
  balanced debit/credit lines, not just a single audit-log row.
- Stay uniform with the existing codebase's conventions (package-per-domain
  layout, Lombok builders, UUID ids via `GenerationType.UUID`,
  `OffsetDateTime` timestamps, `@PreAuthorize("hasAuthority(...)")`,
  `ApiResponse` wrapper, Flyway migrations, DB-level invariants like
  `chk_wallets_exactly_one_owner`).

## Non-goals (explicitly out of scope for this work)

- Customer cash-in/cash-out logic. It doesn't exist yet in this codebase.
  The design accounts for it (see "Future: customer wallet" below) but
  does not build it.
- Fee revenue, loan receivable, or suspense accounting — `FinancialAccountType`
  has unused values for these, but nothing in this feature touches them.
- Maker-checker / dual-approval on any operation (mint, allocate, or
  withdraw). Explicitly decided: single `ADMIN`, direct execution. A real
  approval workflow (pending state + separate approve endpoint + a
  different approver than requester) is a distinct, larger feature and is
  not part of this one. Do not add an `ADMIN_APPROVES`-style permission as
  a stand-in — a permission alone does not enforce segregation of duties
  and would be a half-measure.

## Anti-goals

- Do not model the agent's float balance twice. Reuse the agent's existing
  `FinancialAccountEntity` (`AGENT_FLOAT`) as the one source of truth for
  an agent's float — do not create a parallel `agent_float_accounts`
  table (an earlier reference sample did this; it was rejected as
  duplication).
- Do not let the ledger become something only `company_wallet` can post
  to. See "Package split" below for why.

## Architecture

### Package split: `ledger` and `company_wallet`

Two new top-level packages, siblings to `wallet`, `financial_account`,
`agent`, etc.

**`ledger`** — neutral, shared bookkeeping. Owns:
- `TransactionJournalEntity` (header): `id` (UUID), `referenceNumber`
  (unique), `transactionType`, `description`, `createdBy` (FK →
  `UserEntity`), `createdAt`.
- `TransactionJournalLineEntity` (one row per leg): `id`, `journal` (FK),
  `ledgerAccountType`, `accountRefId` (UUID — points at the company
  wallet's id for company-side legs, or at the agent's
  `financial_accounts.id` for agent-side legs), `entryType`, `amount`.
- `TransactionType` enum: `MINT_FLOAT`, `ALLOCATE_FLOAT`, `AGENT_WITHDRAW`.
- `LedgerAccountType` enum: `ESCROW_BANK`, `COMPANY_UNALLOCATED`,
  `COMPANY_DISTRIBUTED`, `AGENT_FLOAT`, and `CUSTOMER_WALLET` (reserved,
  unused until customer cash-in/out exists — see "Future" below).
- `EntryType` enum: `DEBIT`, `CREDIT`.
- `LedgerService.postJournal(type, description, createdBy, lines)` —
  validates `sum(debits) == sum(credits)` for the given lines, then
  persists the header and lines atomically. This is the only write path
  into the journal tables.

This package does not know what a "company wallet" or "agent" is beyond
the generic `ledgerAccountType` + `accountRefId` pair. That's deliberate:
it's reused, unmodified, when agent-to-customer transactions get built
later (see "Future" below) — those would be a new client of
`LedgerService`, not a reason to touch this package.

**`company_wallet`** — owns only the company's own balances:
- `CompanyMasterWalletEntity`: `id` (UUID), `walletName`, `currency`,
  `unallocatedFloat`, `totalDistributedFloat`, `escrowBankAccountNo`,
  `createdAt`/`updatedAt`. Singleton, enforced at the DB level by a
  `singleton_key SMALLINT NOT NULL DEFAULT 1` column with a unique
  constraint — same philosophy as `chk_wallets_exactly_one_owner` on
  `wallets`: the invariant is guaranteed by Postgres, not just by
  application discipline.
- `CompanyMasterWalletRepository` — includes a method that loads the
  singleton row with `@Lock(LockModeType.PESSIMISTIC_WRITE)`, so
  concurrent admin actions (two mints at once, etc.) serialize instead of
  racing.
- `CompanyMasterWalletService` — one method per operation. Each:
  1. Loads the singleton row under the pessimistic lock.
  2. Validates the operation (see "Debit/credit pairs" below for the
     balance checks).
  3. Updates its own balances.
  4. Calls `LedgerService.postJournal(...)` with the matching lines.
  5. Saves. All inside one `@Transactional` boundary.

  **Invariant**: `LedgerService.postJournal(...)` must use the default
  `REQUIRED` propagation (join the caller's transaction), never
  `REQUIRES_NEW`. The whole point of the single `@Transactional` boundary
  is that if any step throws an unchecked exception (e.g.
  `BadRequestException`, which already extends `RuntimeException` in this
  codebase), the balance update and the journal post roll back together —
  never a journal entry with no matching balance change, or vice versa.
  `REQUIRES_NEW` on the ledger call would silently break this by letting
  the journal commit independently of the wallet balance.
- `CompanyMasterWalletController` + request/response DTOs, following
  `AgentController`'s shape (`ApiResponse<T>` wrapper, `@PreAuthorize`).

### Debit/credit pairs per operation

| Operation | Debit | Credit | Balance effect | Validation |
|---|---|---|---|---|
| `MINT_FLOAT` | `ESCROW_BANK` | `COMPANY_UNALLOCATED` | `unallocatedFloat += amount` | amount > 0 |
| `ALLOCATE_FLOAT` | `COMPANY_UNALLOCATED` | `AGENT_FLOAT` (ref = agent's financial account id) | `unallocatedFloat -= amount`, `totalDistributedFloat += amount`, agent `financial_account.currentBalance += amount` | reject if `unallocatedFloat < amount`; reject if agent's `FinancialAccountEntity.status != ACTIVE` |
| `AGENT_WITHDRAW` | `AGENT_FLOAT` | `COMPANY_UNALLOCATED` | reverse of allocate | reject if agent `currentBalance < amount`; reject if agent's `FinancialAccountEntity.status != ACTIVE` |

Both checks use `BadRequestException`, matching the existing pattern for
business-rule violations. A suspended or inactive agent account cannot
receive or return float.

`ESCROW_BANK` has no backing row anywhere in this design — it exists only
as a `ledgerAccountType` value on journal lines, representing "real money
in the company's bank," so that `MINT_FLOAT` isn't a one-sided fabrication
of value in the ledger. `accountRefId` for `ESCROW_BANK` and
`COMPANY_UNALLOCATED`/`COMPANY_DISTRIBUTED` lines is the company wallet's
own id.

### Security

- `mint`, `allocate`, `withdraw` endpoints: `@PreAuthorize("hasAuthority('FLOAT_MANAGE')")`.
  `FLOAT_MANAGE` is already assigned to `ADMIN` in `V6` — no seed change
  needed for this part.
- `view` endpoints (balance, journal history): `@PreAuthorize("hasAuthority('COMPANY_WALLET_VIEW')")`
  and `@PreAuthorize("hasAuthority('LEDGER_VIEW')")` respectively.
  **Found gap**: both permissions exist in the `permissions` table (seeded
  in `V6`) but are never assigned to any role — they're currently
  unreachable by any user. The migration for this feature must add them
  to `ADMIN`'s `role_permissions`, or these endpoints would be dead on
  arrival.

### Migration (new Flyway file)

- `CREATE TABLE company_master_wallet (...)` with the `singleton_key`
  unique constraint.
- `CREATE TABLE transaction_journal (...)`.
- `CREATE TABLE transaction_journal_line (...)`.
- Seed the one `company_master_wallet` row.
- `INSERT INTO role_permissions ...` granting `COMPANY_WALLET_VIEW` and
  `LEDGER_VIEW` to `ADMIN`.

## Future (not built now, but why the design doesn't block it)

When customer cash-in/cash-out gets built, an agent selling float to a
customer for cash should post `Dr AGENT_FLOAT / Cr CUSTOMER_WALLET`
through the same `LedgerService.postJournal(...)` — which is why
`CUSTOMER_WALLET` is already a reserved `LedgerAccountType` value. The
real invariant a mature e-money system checks is: money in the escrow
bank account == `unallocatedFloat` + sum of all agent float balances +
sum of all customer wallet balances. That reconciliation report is not
part of this work, but the ledger structure here is what makes it
possible later without a second, competing ledger.

## What's left to the builder

- There is a pre-existing, untracked, never-wired-up scratch file at
  `src/main/java/com/application/e_wallet/companyWallet/entity/companyMasterWallet.java`
  (wrong package casing, two `@Id` fields, `GenerationType.IDENTITY`,
  `LocalDateTime`, a malformed `@Column` annotation, no singleton
  safeguard). Confirmed disposable — delete it and build the real
  `company_wallet` package fresh per this doc.
- There's no existing repository path from "an agent" straight to their
  `FinancialAccountEntity` — only the lazy `AgentEntity` → `WalletEntity`
  → `FinancialAccountEntity` `@OneToOne` chain. A new query method (e.g.
  `FinancialAccountRepository.findByWallet_Agent_Id`) is needed.
- Exact DTO field names/shapes for request/response bodies.
- Reference number generation scheme for `TransactionJournalEntity.referenceNumber`
  (needs to be unique; no existing generator pattern in the codebase to
  match — builder's call, e.g. `{TYPE}-{yyyyMMdd}-{shortUuid}`).
- Exact REST paths under `/api/v1/company-wallet` (`/mint`, `/allocate`,
  `/withdraw`, `/`, `/journal` is the expected shape, following
  `AgentController`'s `/api/v1/agents/onboard` pattern).
- Error type used for validation failures (`BadRequestException` exists
  and matches the pattern used elsewhere for business-rule violations).
