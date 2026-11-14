# Wallet, Ledger & Treasury Consolidation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Collapse `wallet` + `financial_account` + `company_wallet` + the ledger's second account-type taxonomy into one `wallets` table and one `treasury` module, matching the ERD/DFD, and fix the DTO-sharing and admin-bypass issues found along the way.

**Architecture:** Six ordered Flyway migrations land the target schema first (new columns → backfill → lock down → ledger FK migration → drop old tables → rename+extend the request table). Application code then follows the same dependency order: `wallet` (used by everything) → `ledger` (simplified, still decoupled from `wallet`'s Java types) → `treasury` (new module, built on both) → `agent` (profile-only cleanup + DTO split) → delete the now-dead `financial_account`/`company_wallet` packages.

**Tech Stack:** Spring Boot 4.2 (snapshot), Java 21, Spring Data JPA, PostgreSQL + Flyway, JUnit 5 + Mockito + AssertJ (existing tests are pure unit tests against mocked repositories — no DB in the test suite).

**Spec:** `docs/superpowers/specs/2026-11-14-wallet-ledger-treasury-consolidation-design.md`

## Global Constraints

- Currency on every new/backfilled row is `"TZS"` (existing convention).
- Money columns are `NUMERIC(19,2)`; amounts are rejected above 2 decimal places before persisting (carry forward the existing `validateScale` pattern).
- `wallets.balance` is never negative: `CHECK (balance >= 0)`.
- `wallets.version` is a **plain `@Column`, never `@Version`** — annotating it `@Version` would make Hibernate enforce real optimistic locking automatically, which the spec explicitly says is *not* the mechanism yet. Pessimistic row locking (generalizing `findSingletonForUpdate`'s pattern) stays the real concurrency control.
- `COMPANY_MASTER_ACCOUNT`, `REVENUE`, `ESCROW_BANK` are enforced singleton via `uk_wallets_singleton_company_types`; no onboarding code path may ever create one.
- Ownership is `UNIQUE (user_id, wallet_type)` (`uk_wallets_user_type`); `wallets.customer_id`/`agent_id` and their constraints are dropped, not kept.
- No agent float movement happens outside an approved `treasury_requests` row. `mint` is the one exception (no agent involved, nothing to request against).
- **DTO convention:** one request/response record per controller action. The only exception is a single resource's own representation reused across that resource's own lifecycle endpoints (e.g. `TreasuryRequestResponse` across create/list/get/approve/reject).
- `LedgerService.postJournal` keeps `REQUIRED` propagation (joins the caller's transaction) — never `REQUIRES_NEW`. This is unchanged from today; no task should alter it.
- Permission strings (`FLOAT_MANAGE`, `FLOAT_VIEW`, `REQUEST_FLOAT`, `COMPANY_WALLET_VIEW`, `LEDGER_VIEW`) are **not renamed**, even though the module becomes `treasury` — renaming them is out of scope and not required by the spec.
- `TreasuryRequestService` calls `TreasuryService` directly (not through `AgentService`). The spec's wording ("called solely from `TreasuryRequestService.approveRequest`") is satisfied either way; routing through `AgentService` would create a `treasury` → `agent` → `treasury` package cycle for no benefit, so `AgentService.topupFloat`/`withdrawFloat` are deleted as dead code instead of kept "internal." This is the one place this plan deviates from the spec's literal wording, and it's a strict simplification of the same intent (treasury owns float movement end to end).

## Review Focus

1. **Concurrent approval of two requests against the same agent's `AGENT_FLOAT` wallet.** The old code only ever locked the company singleton row — never the agent's own row. Pins to the locked `WalletRepository` query actually being the one `topup`/`withdraw` call.
2. **Migration backfill must not create or destroy money.** Sum of all `financial_accounts.current_balance` + `company_master_wallet.unallocated_float` before migration must equal the sum of all `wallets.balance` for `CUSTOMER_MAIN` + `AGENT_FLOAT` + `COMPANY_MASTER_ACCOUNT` after (the net-new `AGENT_COMMISSION`/`REVENUE` rows are legitimately `0` and excluded from this check).
3. **Rejecting a `WITHDRAWAL` request against an agent with insufficient `AGENT_FLOAT` balance** must fail the same clean way `topup` already does for insufficient company float — not an unchecked NPE from a missing wallet lookup.
4. **A closed/suspended agent wallet must block both `topup` and `withdraw`.** This was a `FinancialAccountStatus != ACTIVE` check before; it's easy to silently drop while merging entities.
5. **`mint`'s two journal legs must resolve to two different wallet ids** (`ESCROW_BANK` debit, `COMPANY_MASTER_ACCOUNT` credit) — a copy-paste from the old pseudo-account code would point both legs at the same wallet again, reintroducing the exact self-cancelling bug the builder-check found.

---

## Task 1: Flyway migration — consolidate `wallets`, simplify the ledger, rename the request table

**Files:**
- Create: `src/main/resources/db/migration/V25__wallets_add_consolidation_columns.sql`
- Create: `src/main/resources/db/migration/V26__wallets_backfill_and_seed.sql`
- Create: `src/main/resources/db/migration/V27__wallets_lock_down_constraints.sql`
- Create: `src/main/resources/db/migration/V28__ledger_migrate_to_wallet_fk.sql`
- Create: `src/main/resources/db/migration/V29__drop_financial_accounts_and_company_master_wallet.sql`
- Create: `src/main/resources/db/migration/V30__rename_agent_float_requests_to_treasury_requests.sql`
- Create: `src/main/resources/db/migration/V31__rename_allocate_float_to_topup_float.sql`

**Interfaces:**
- Produces: the `wallets` table shape every later task's entity/repository code targets — columns `user_id, wallet_type, wallet_number, account_number, currency, balance, status, version, external_reference, created_at, updated_at`; `wallet_type` values `COMPANY_MASTER_ACCOUNT, REVENUE, AGENT_FLOAT, AGENT_COMMISSION, CUSTOMER_MAIN, ESCROW_BANK`.
- Produces: `transaction_journal_line.wallet_id` (FK → `wallets.id`, `NOT NULL`), replacing `ledger_account_type`/`account_ref_id`.
- Produces: `treasury_requests` table (renamed from `agent_float_requests`) with a new `request_type` column (`TOPUP`/`WITHDRAWAL`).
- Produces: a fixed system-user id `'11111111-1111-1111-1111-111111111111'` that owns every company-level wallet — later tasks (treasury seeding, tests) may reference this literal.
- Produces: the historical `transaction_journal.transaction_type` string `'ALLOCATE_FLOAT'` rewritten to `'TOPUP_FLOAT'` — Task 3 renames the matching Java enum constant; order doesn't matter between them since both must land before Task 4 compiles against `TransactionType.TOPUP_FLOAT`, but this migration must still exist so historical rows deserialize once the old constant is gone.

- [ ] **Step 1: Write `V25__wallets_add_consolidation_columns.sql`**

```sql
ALTER TABLE wallets
    ADD COLUMN user_id UUID,
    ADD COLUMN wallet_type VARCHAR(30),
    ADD COLUMN account_number VARCHAR(30),
    ADD COLUMN balance NUMERIC(19,2),
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN external_reference VARCHAR(100);

-- Dropped here, not in V27: V26's net-new company-level/commission wallet rows set
-- neither customer_id nor agent_id (ownership is moving to user_id alone), which this
-- V15 constraint forbids. It must be gone before V26 runs, not after.
ALTER TABLE wallets DROP CONSTRAINT chk_wallets_exactly_one_owner;
```

- [ ] **Step 2: Write `V26__wallets_backfill_and_seed.sql`**

```sql
-- 1. The system user that owns every company-level wallet. BLOCKED + null pin_hash
--    so it can never log in (pin_hash nullability already allowed by V14).
INSERT INTO users (id, first_name, middle_name, last_name, email, phone, pin_hash, status, created_at, updated_at)
VALUES ('11111111-1111-1111-1111-111111111111', 'System', '', 'Treasury',
        'system@internal.ewallet', '0000000000', NULL, 'BLOCKED', NOW(), NOW());

-- 2. Customer wallets.
UPDATE wallets w
SET user_id = c.user_id,
    wallet_type = 'CUSTOMER_MAIN',
    account_number = fa.account_number,
    balance = fa.current_balance
FROM customers c, financial_accounts fa
WHERE w.customer_id = c.id AND fa.wallet_id = w.id;

-- 3. Agent wallets — today's single wallet row per agent represents float.
UPDATE wallets w
SET user_id = a.user_id,
    wallet_type = 'AGENT_FLOAT',
    account_number = fa.account_number,
    balance = fa.current_balance
FROM agents a, financial_accounts fa
WHERE w.agent_id = a.id AND fa.wallet_id = w.id;

-- 4. New AGENT_COMMISSION wallet per agent (net-new; nothing tracked commission before).
INSERT INTO wallets (id, user_id, wallet_type, wallet_number, account_number, currency, balance, status, version, created_at, updated_at)
SELECT gen_random_uuid(), a.user_id, 'AGENT_COMMISSION', 'COM-' || a.agent_number, 'COM-' || a.agent_number,
       'TZS', 0, 'ACTIVE', 0, NOW(), NOW()
FROM agents a;

-- 5. COMPANY_MASTER_ACCOUNT — reuses company_master_wallet's existing id, so the ledger
--    migration (V28) needs zero remapping for COMPANY_UNALLOCATED/COMPANY_DISTRIBUTED legs.
INSERT INTO wallets (id, user_id, wallet_type, wallet_number, account_number, currency, balance, status, version, external_reference, created_at, updated_at)
SELECT id, '11111111-1111-1111-1111-111111111111', 'COMPANY_MASTER_ACCOUNT',
       'COMPANY-MASTER-WALLET', 'COMPANY-MASTER-ACCOUNT', currency, unallocated_float,
       'ACTIVE', 0, escrow_bank_account_no, NOW(), NOW()
FROM company_master_wallet WHERE singleton_key = 1;

-- 6. REVENUE (net-new; nothing tracked revenue before).
INSERT INTO wallets (id, user_id, wallet_type, wallet_number, account_number, currency, balance, status, version, created_at, updated_at)
VALUES (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'REVENUE',
        'COMPANY-REVENUE-WALLET', 'COMPANY-REVENUE-ACCOUNT', 'TZS', 0, 'ACTIVE', 0, NOW(), NOW());

-- 7. ESCROW_BANK — seeded to unallocated_float + total_distributed_float, i.e. everything
--    ever minted, not 0. That's the real-world invariant: money in the bank == unallocated
--    float + the sum of all agent float balances. Seeding this at 0 would silently break
--    that invariant on day one.
INSERT INTO wallets (id, user_id, wallet_type, wallet_number, account_number, currency, balance, status, version, created_at, updated_at)
SELECT gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'ESCROW_BANK',
       'COMPANY-ESCROW-WALLET', 'COMPANY-ESCROW-ACCOUNT', currency,
       (unallocated_float + total_distributed_float), 'ACTIVE', 0, NOW(), NOW()
FROM company_master_wallet WHERE singleton_key = 1;
```

- [ ] **Step 3: Verify the backfill with manual SQL before locking anything down**

Run against the migrated dev DB:

```sql
SELECT
  (SELECT COALESCE(SUM(current_balance),0) FROM financial_accounts) +
  (SELECT COALESCE(unallocated_float,0) FROM company_master_wallet WHERE singleton_key=1)
  AS pre_migration_total,
  (SELECT COALESCE(SUM(balance),0) FROM wallets
   WHERE wallet_type IN ('CUSTOMER_MAIN','AGENT_FLOAT','COMPANY_MASTER_ACCOUNT')) AS post_migration_total;
```

Expected: the two columns are equal (Review Focus #2). Also confirm `SELECT COUNT(*) FROM wallets WHERE user_id IS NULL` returns `0` before proceeding to Step 4.

- [ ] **Step 4: Write `V27__wallets_lock_down_constraints.sql`**

```sql
ALTER TABLE wallets ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE wallets ALTER COLUMN wallet_type SET NOT NULL;
ALTER TABLE wallets ALTER COLUMN account_number SET NOT NULL;
ALTER TABLE wallets ALTER COLUMN balance SET NOT NULL;

ALTER TABLE wallets ADD CONSTRAINT chk_wallets_balance_non_negative CHECK (balance >= 0);
ALTER TABLE wallets ADD CONSTRAINT fk_wallets_user FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE wallets ADD CONSTRAINT uk_wallets_account_number UNIQUE (account_number);

ALTER TABLE wallets DROP CONSTRAINT uk_wallets_customer_id;
ALTER TABLE wallets DROP CONSTRAINT uk_wallets_agent_id;
-- chk_wallets_exactly_one_owner was already dropped in V25 (see its comment there) —
-- dropping it again here would error with "constraint does not exist".
ALTER TABLE wallets DROP CONSTRAINT fk_wallets_customer;
ALTER TABLE wallets DROP CONSTRAINT fk_wallets_agent;
ALTER TABLE wallets DROP COLUMN customer_id;
ALTER TABLE wallets DROP COLUMN agent_id;

CREATE UNIQUE INDEX uk_wallets_user_type ON wallets (user_id, wallet_type);
CREATE UNIQUE INDEX uk_wallets_singleton_company_types
    ON wallets (wallet_type)
    WHERE wallet_type IN ('COMPANY_MASTER_ACCOUNT', 'REVENUE', 'ESCROW_BANK');
```

- [ ] **Step 4b: Verify the singleton partial unique index actually rejects a duplicate**

Run against the migrated dev DB:

```sql
INSERT INTO wallets (id, user_id, wallet_type, wallet_number, account_number, currency, balance, status, version, created_at, updated_at)
VALUES (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'REVENUE', 'DUPLICATE-TEST', 'DUPLICATE-TEST', 'TZS', 0, 'ACTIVE', 0, NOW(), NOW());
```

Expected: fails with a unique-violation on `uk_wallets_singleton_company_types`. Then `ROLLBACK;` (or just don't commit this probe statement) so the bad row never lands.

- [ ] **Step 5: Write `V28__ledger_migrate_to_wallet_fk.sql`**

Correction to the design doc's note that `COMPANY_UNALLOCATED`/`ESCROW_BANK`-tagged lines are "zero-transform": that was true before `ESCROW_BANK` became its own wallet row. Now, historical `ESCROW_BANK`-tagged lines (which used `company_master_wallet.id` as a placeholder, since no escrow row existed) must be redirected to the *new* `ESCROW_BANK` wallet's id, not left pointing at `COMPANY_MASTER_ACCOUNT`.

```sql
-- One-time cleanup, found during execution: this specific row is a dangling reference
-- from manual testing during the company-wallet feature's initial build (2026-11-08,
-- described "10M has be allocated for the agent") — its account_ref_id matches no
-- financial_accounts/wallets/agents/customers/users row at all, and there's no FK on
-- this column anywhere in the schema to have caught it. Confirmed it has zero effect on
-- any real balance (Step 3's backfill-sum check already passed without it). Deleted by
-- exact id, not by pattern, so this is surgical and doesn't mask any other orphan — the
-- Step 6 check below still hard-fails on any other unresolvable row, exactly as before.
DELETE FROM transaction_journal_line WHERE id = '1b4ef1e7-76cc-4c7e-bd0d-48817f23ab3d';

ALTER TABLE transaction_journal_line ADD COLUMN wallet_id UUID;

-- AGENT_FLOAT-tagged lines pointed at financial_accounts.id.
UPDATE transaction_journal_line tjl
SET wallet_id = fa.wallet_id
FROM financial_accounts fa
WHERE tjl.ledger_account_type = 'AGENT_FLOAT' AND tjl.account_ref_id = fa.id;

-- ESCROW_BANK-tagged lines: redirect to the new, distinct ESCROW_BANK wallet.
UPDATE transaction_journal_line tjl
SET wallet_id = (SELECT id FROM wallets WHERE wallet_type = 'ESCROW_BANK')
WHERE tjl.ledger_account_type = 'ESCROW_BANK';

-- COMPANY_UNALLOCATED / COMPANY_DISTRIBUTED: account_ref_id already equals the
-- COMPANY_MASTER_ACCOUNT wallet's id (V26 step 5 reused it) — true zero-transform.
UPDATE transaction_journal_line tjl
SET wallet_id = tjl.account_ref_id
WHERE tjl.ledger_account_type IN ('COMPANY_UNALLOCATED', 'COMPANY_DISTRIBUTED')
  AND tjl.wallet_id IS NULL;

ALTER TABLE transaction_journal_line ALTER COLUMN wallet_id SET NOT NULL;
ALTER TABLE transaction_journal_line
    ADD CONSTRAINT fk_transaction_journal_line_wallet FOREIGN KEY (wallet_id) REFERENCES wallets (id);
ALTER TABLE transaction_journal_line DROP COLUMN ledger_account_type;
ALTER TABLE transaction_journal_line DROP COLUMN account_ref_id;
```

- [ ] **Step 6: Verify no `transaction_journal_line` row was left with a null `wallet_id` before Step 5's final `ALTER COLUMN ... SET NOT NULL`**

Run: `SELECT COUNT(*) FROM transaction_journal_line WHERE wallet_id IS NULL;`
Expected: `0`. (If non-zero on your data, stop — it means a `ledger_account_type` value exists that none of the three `UPDATE`s above covered; do not force the `NOT NULL` through.)

- [ ] **Step 7: Write `V29__drop_financial_accounts_and_company_master_wallet.sql`**

```sql
DROP TABLE financial_accounts;
DROP TABLE company_master_wallet;
```

- [ ] **Step 8: Write `V30__rename_agent_float_requests_to_treasury_requests.sql`**

```sql
ALTER TABLE agent_float_requests RENAME TO treasury_requests;
ALTER INDEX idx_agent_float_requests_agent_id RENAME TO idx_treasury_requests_agent_id;
ALTER INDEX idx_agent_float_requests_status RENAME TO idx_treasury_requests_status;
ALTER TABLE treasury_requests RENAME CONSTRAINT fk_agent_float_requests_agent TO fk_treasury_requests_agent;
ALTER TABLE treasury_requests RENAME CONSTRAINT fk_agent_float_requests_decided_by TO fk_treasury_requests_decided_by;
ALTER TABLE treasury_requests RENAME CONSTRAINT chk_agent_float_requests_status TO chk_treasury_requests_status;
ALTER TABLE treasury_requests RENAME CONSTRAINT chk_agent_float_requests_amount_positive TO chk_treasury_requests_amount_positive;
ALTER TABLE treasury_requests RENAME CONSTRAINT uk_agent_float_requests_request_ref TO uk_treasury_requests_request_ref;

ALTER TABLE treasury_requests ADD COLUMN request_type VARCHAR(20) NOT NULL DEFAULT 'TOPUP';
ALTER TABLE treasury_requests ADD CONSTRAINT chk_treasury_requests_request_type CHECK (request_type IN ('TOPUP', 'WITHDRAWAL'));
ALTER TABLE treasury_requests ALTER COLUMN request_type DROP DEFAULT;
```

- [ ] **Step 9: Write `V31__rename_allocate_float_to_topup_float.sql`**

Renames the operation historically called "allocate" to "topup" (matching `RequestType.TOPUP`). `TransactionType` is stored via `@Enumerated(STRING)`, so the historical string value in already-written `transaction_journal` rows must be rewritten too, not just the Java enum constant (Task 3 renames the Java side) — otherwise existing rows fail to deserialize once the old constant no longer exists.

```sql
UPDATE transaction_journal SET transaction_type = 'TOPUP_FLOAT' WHERE transaction_type = 'ALLOCATE_FLOAT';
```

- [ ] **Step 10: Run the full migration chain against a local dev Postgres database**

Run: `mvn flyway:migrate` (or start the app once with `mvn spring-boot:run` against a disposable dev DB).
Expected: all seven migrations apply cleanly, no errors.

- [ ] **Step 11: Commit**

```bash
git add src/main/resources/db/migration/V25__wallets_add_consolidation_columns.sql src/main/resources/db/migration/V26__wallets_backfill_and_seed.sql src/main/resources/db/migration/V27__wallets_lock_down_constraints.sql src/main/resources/db/migration/V28__ledger_migrate_to_wallet_fk.sql src/main/resources/db/migration/V29__drop_financial_accounts_and_company_master_wallet.sql src/main/resources/db/migration/V30__rename_agent_float_requests_to_treasury_requests.sql src/main/resources/db/migration/V31__rename_allocate_float_to_topup_float.sql
git commit -m "migrate: consolidate wallets, simplify ledger, rename agent_float_requests to treasury_requests, rename ALLOCATE_FLOAT to TOPUP_FLOAT"
```

---

## Task 2: `wallet` module — consolidated entity, repository, service

**Files:**
- Create: `src/main/java/com/application/e_wallet/wallet/entity/WalletType.java`
- Modify: `src/main/java/com/application/e_wallet/wallet/entity/WalletEntity.java`
- Modify: `src/main/java/com/application/e_wallet/wallet/repository/WalletRepository.java`
- Modify: `src/main/java/com/application/e_wallet/wallet/service/WalletService.java`
- Test: `src/test/java/com/application/e_wallet/wallet/service/WalletServiceTest.java`

**Interfaces:**
- Consumes: `wallets` table shape from Task 1.
- Produces (for Tasks 4 and 5): `WalletRepository.findByUserIdAndWalletType(UUID userId, WalletType type) -> Optional<WalletEntity>`; `findByWalletType(WalletType type) -> Optional<WalletEntity>`; `findByUserIdAndWalletTypeForUpdate(UUID userId, WalletType type) -> Optional<WalletEntity>` (pessimistic write lock); `findByWalletTypeForUpdate(WalletType type) -> Optional<WalletEntity>` (pessimistic write lock); `sumBalanceByWalletType(WalletType type) -> BigDecimal`; `findByWalletNumber(String) -> Optional<WalletEntity>` (kept, unchanged signature).
- Produces: `WalletEntity` getters `getUser()`, `getWalletType()`, `getAccountNumber()`, `getBalance()`, `setBalance(BigDecimal)`, `getStatus()`, `setStatus(WalletStatus)` — Tasks 4 and 5 read/write these directly.

- [ ] **Step 1: Write `WalletServiceTest` for the rewritten `createWallet`**

```java
@Test
void createWalletCreatesOneCustomerMainWalletWithZeroBalance() {
    UserEntity user = UserEntity.builder().id(UUID.randomUUID()).phone("0712345678").build();
    CustomerEntity customer = CustomerEntity.builder().id(UUID.randomUUID()).user(user).build();
    when(walletRepository.existsByUserIdAndWalletType(user.getId(), WalletType.CUSTOMER_MAIN)).thenReturn(false);
    when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    WalletEntity wallet = walletService.createWallet(customer);

    assertThat(wallet.getWalletType()).isEqualTo(WalletType.CUSTOMER_MAIN);
    assertThat(wallet.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(wallet.getWalletNumber()).isEqualTo("0712345678");
    assertThat(wallet.getAccountNumber()).isEqualTo("0712345678");
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn test -Dtest=WalletServiceTest`
Expected: FAIL — `WalletType`, `existsByUserIdAndWalletType`, and the new `WalletEntity` fields don't exist yet.

- [ ] **Step 3: Create `WalletType` enum**

```java
package com.application.e_wallet.wallet.entity;

public enum WalletType {
    COMPANY_MASTER_ACCOUNT,
    REVENUE,
    AGENT_FLOAT,
    AGENT_COMMISSION,
    CUSTOMER_MAIN,
    ESCROW_BANK
}
```

- [ ] **Step 4: Rewrite `WalletEntity`**

Remove: `customer` (`CustomerEntity`), `agent` (`AgentEntity`), `financialAccount` (`FinancialAccountEntity`) fields and their imports.
Add: `user` (`@ManyToOne(fetch = LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false) UserEntity`), `walletType` (`@Enumerated(STRING) @Column(name = "wallet_type", nullable = false, length = 30) WalletType`), `accountNumber` (`@Column(name = "account_number", nullable = false, unique = true, length = 30) String`), `balance` (`@Column(nullable = false, precision = 19, scale = 2) BigDecimal`), `version` (`@Column(nullable = false) Long` — **plain `@Column`, not `@Version`**, per Global Constraints), `externalReference` (`@Column(name = "external_reference") String`, nullable).
Keep unchanged: `id`, `walletNumber`, `currency`, `status`, `createdAt`, `updatedAt`, the `onCreate`/`onUpdate` lifecycle callbacks. Default `balance` to `BigDecimal.ZERO` and `version` to `0L` in `onCreate` if null, mirroring the existing `status`/`currency` defaulting pattern.

- [ ] **Step 5: Rewrite `WalletRepository`**

Remove `existsByCustomerId`, `findByCustomerId`, `findByAgentId`. Add:

```java
boolean existsByUserIdAndWalletType(UUID userId, WalletType walletType);
Optional<WalletEntity> findByUserIdAndWalletType(UUID userId, WalletType walletType);
Optional<WalletEntity> findByWalletType(WalletType walletType);

@Lock(LockModeType.PESSIMISTIC_WRITE)
Optional<WalletEntity> findByUserIdAndWalletTypeForUpdate(UUID userId, WalletType walletType);

@Lock(LockModeType.PESSIMISTIC_WRITE)
Optional<WalletEntity> findByWalletTypeForUpdate(WalletType walletType);

@Query("SELECT COALESCE(SUM(w.balance), 0) FROM WalletEntity w WHERE w.walletType = :walletType")
BigDecimal sumBalanceByWalletType(@Param("walletType") WalletType walletType);
```

Keep `findByWalletNumber` unchanged.

- [ ] **Step 6: Rewrite `WalletService`**

Remove the `FinancialAccountRepository` field and `financial_account` imports. `createWallet(CustomerEntity customer) -> WalletEntity`: throw `IllegalStateException` if `walletRepository.existsByUserIdAndWalletType(customer.getUser().getId(), WalletType.CUSTOMER_MAIN)`, else build and save one `WalletEntity` (`walletType = CUSTOMER_MAIN`, `walletNumber = accountNumber = customer.getUser().getPhone()`, `balance = BigDecimal.ZERO`, `status = ACTIVE`). `getMyWallet`/`walletForCustomer`/`walletForAgent`: read the single wallet via `findByUserIdAndWalletType`; for an agent, look up `AGENT_FLOAT` specifically (an agent now has two wallets — "my wallet" means the float wallet they operate with; `AGENT_COMMISSION` isn't exposed through this endpoint). `getWalletByNumber`/`toResponse`: read the one `WalletEntity`'s own `balance`/`accountNumber` directly (no second repository lookup); `ownerType` = `"CUSTOMER"` for `CUSTOMER_MAIN`, `"AGENT"` for `AGENT_FLOAT`/`AGENT_COMMISSION`, `"COMPANY"` otherwise.

- [ ] **Step 7: Run to verify Step 1's test passes, plus add and pass two more focused tests**

Add: `getWalletByNumberMapsCustomerMainToCustomerOwnerType`, `getMyWalletForAgentReturnsTheFloatWallet` (mock an agent with both `AGENT_FLOAT` and `AGENT_COMMISSION` rows present; assert the float one is returned).
Run: `mvn test -Dtest=WalletServiceTest`
Expected: PASS (3/3).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/application/e_wallet/wallet src/test/java/com/application/e_wallet/wallet
git commit -m "refactor: consolidate wallet balance and type onto WalletEntity"
```

---

## Task 3: `ledger` module — journal lines reference a wallet directly

**Files:**
- Delete: `src/main/java/com/application/e_wallet/ledger/entity/LedgerAccountType.java`
- Modify: `src/main/java/com/application/e_wallet/ledger/entity/TransactionJournalLineEntity.java`
- Modify: `src/main/java/com/application/e_wallet/ledger/entity/TransactionType.java`
- Modify: `src/main/java/com/application/e_wallet/ledger/dto/JournalLineRequest.java`
- Modify: `src/main/java/com/application/e_wallet/ledger/service/LedgerService.java`
- Modify: `src/test/java/com/application/e_wallet/ledger/service/LedgerServiceTest.java`

**Interfaces:**
- Consumes: nothing from Task 2 — `ledger` stays decoupled from `wallet`'s Java types; `wallet_id` is a plain `UUID`, not a `@ManyToOne WalletEntity`, same as the old `accountRefId`.
- Consumes: Task 1's V31 migration, which rewrites historical `'ALLOCATE_FLOAT'` rows to `'TOPUP_FLOAT'` so they still deserialize once this task renames the Java constant.
- Produces (for Task 4): `JournalLineRequest(UUID walletId, EntryType entryType, BigDecimal amount)`; `LedgerService.postJournal(TransactionType, String, UserEntity, List<JournalLineRequest>) -> TransactionJournalEntity` (signature unchanged — only the line shape inside `JournalLineRequest` changed); `TransactionType.TOPUP_FLOAT` (renamed from `ALLOCATE_FLOAT` — Task 4's `TreasuryService.topup` posts this).

- [ ] **Step 1: Update `LedgerServiceTest` to the new `JournalLineRequest` shape**

Replace every `new JournalLineRequest(LedgerAccountType.X, someUuid, EntryType.Y, amount)` with `new JournalLineRequest(someUuid, EntryType.Y, amount)` in all three existing tests. Remove the `LedgerAccountType` import.

- [ ] **Step 2: Run to verify it fails to compile**

Run: `mvn test -Dtest=LedgerServiceTest`
Expected: FAIL — compile error, `JournalLineRequest` still has the old constructor.

- [ ] **Step 3: Delete `LedgerAccountType.java`**

- [ ] **Step 4: Rewrite `JournalLineRequest`**

```java
public record JournalLineRequest(UUID walletId, EntryType entryType, BigDecimal amount) {}
```

- [ ] **Step 5: Rewrite `TransactionJournalLineEntity`**

Remove `ledgerAccountType` and `accountRefId` fields. Add `walletId` (`@Column(name = "wallet_id", nullable = false) UUID`) — a plain column, not a JPA relationship, matching the decoupling the old `accountRefId` already had.

- [ ] **Step 6: Update `LedgerService.postJournal`'s line-building**

Change `.ledgerAccountType(line.ledgerAccountType()).accountRefId(line.accountRefId())` to `.walletId(line.walletId())`. The balanced debit/credit validation and reference-number generation are unchanged.

- [ ] **Step 6b: Rename `TransactionType.ALLOCATE_FLOAT` to `TOPUP_FLOAT`**

```java
public enum TransactionType {
    MINT_FLOAT,
    TOPUP_FLOAT,
    AGENT_WITHDRAW
}
```

Task 1's V31 migration already rewrote every historical `'ALLOCATE_FLOAT'` row to `'TOPUP_FLOAT'`, so this rename doesn't orphan any existing data.

- [ ] **Step 7: Run to verify all three `LedgerServiceTest` tests pass**

Run: `mvn test -Dtest=LedgerServiceTest`
Expected: PASS (3/3).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/application/e_wallet/ledger src/test/java/com/application/e_wallet/ledger
git commit -m "refactor: journal lines reference a wallet id directly, drop LedgerAccountType"
```

---

## Task 4: `treasury` module — mint/topup/withdraw and the request inbox

**Files:**
- Create: `src/main/java/com/application/e_wallet/treasury/entity/RequestType.java`
- Create: `src/main/java/com/application/e_wallet/treasury/entity/RequestStatus.java`
- Create: `src/main/java/com/application/e_wallet/treasury/entity/TreasuryRequestEntity.java`
- Create: `src/main/java/com/application/e_wallet/treasury/repository/TreasuryRequestRepository.java`
- Create: `src/main/java/com/application/e_wallet/treasury/dto/MintRequest.java`
- Create: `src/main/java/com/application/e_wallet/treasury/dto/TreasuryOverviewResponse.java`
- Create: `src/main/java/com/application/e_wallet/treasury/dto/TreasuryMetrics.java`
- Create: `src/main/java/com/application/e_wallet/treasury/dto/JournalLineResponse.java`
- Create: `src/main/java/com/application/e_wallet/treasury/dto/TransactionJournalResponse.java`
- Create: `src/main/java/com/application/e_wallet/treasury/dto/TreasuryRequestCreateRequest.java`
- Create: `src/main/java/com/application/e_wallet/treasury/dto/TreasuryRequestDecisionRequest.java`
- Create: `src/main/java/com/application/e_wallet/treasury/dto/TreasuryRequestResponse.java`
- Create: `src/main/java/com/application/e_wallet/treasury/service/TreasuryService.java`
- Create: `src/main/java/com/application/e_wallet/treasury/service/TreasuryRequestService.java`
- Create: `src/main/java/com/application/e_wallet/treasury/controller/TreasuryController.java`
- Create: `src/main/java/com/application/e_wallet/treasury/controller/TreasuryRequestController.java`
- Test: `src/test/java/com/application/e_wallet/treasury/service/TreasuryServiceTest.java`
- Test: `src/test/java/com/application/e_wallet/treasury/service/TreasuryRequestServiceTest.java`

**Interfaces:**
- Consumes: `WalletRepository`'s locked/unlocked query methods and `sumBalanceByWalletType` (Task 2); `LedgerService.postJournal`/`JournalLineRequest` (Task 3); existing, unchanged `AgentEntity`/`AgentRepository` (for `TreasuryRequestEntity`'s `agent` association).
- Produces: `TreasuryService.mint(MintRequest, UUID adminUserId)`, `topup(UUID agentId, BigDecimal amount, String description, UUID adminUserId)`, `withdraw(UUID agentId, BigDecimal amount, String description, UUID adminUserId)` — all `void`, called only from `TreasuryRequestService` (and `mint` from `TreasuryController`). No later task calls these directly from a controller for `topup`/`withdraw`. (Renamed from `allocate` — the spec's design doc still says "allocate"; this plan uses `topup` throughout per your instruction, matching `RequestType.TOPUP`.)

- [ ] **Step 1: Write `TreasuryServiceTest` covering mint's two-leg entry and the status/insufficiency checks**

```java
@Test
void mintPostsTwoDistinctWalletLegs() {
    // escrowWallet and companyWallet are two different WalletEntity mocks returned by
    // findByWalletTypeForUpdate(ESCROW_BANK) and findByWalletTypeForUpdate(COMPANY_MASTER_ACCOUNT)
    treasuryService.mint(new MintRequest(new BigDecimal("200.00"), "bank deposit"), adminUserId);

    verify(ledgerService).postJournal(eq(TransactionType.MINT_FLOAT), any(), eq(admin), argThat(lines ->
        lines.size() == 2
        && lines.stream().map(JournalLineRequest::walletId).distinct().count() == 2  // Review Focus #5
        && lines.stream().anyMatch(l -> l.walletId().equals(escrowWallet.getId()) && l.entryType() == EntryType.DEBIT)
        && lines.stream().anyMatch(l -> l.walletId().equals(companyWallet.getId()) && l.entryType() == EntryType.CREDIT)
    ));
}

@Test
void topupRejectsInactiveAgentFloatWallet() {
    // agentFloatWallet mock has status = SUSPENDED
    assertThatThrownBy(() -> treasuryService.topup(agentId, new BigDecimal("10.00"), "x", adminUserId))
        .isInstanceOf(BadRequestException.class);                                        // Review Focus #4
    verifyNoInteractions(ledgerService);
}

@Test
void withdrawRejectsWhenAgentFloatBalanceInsufficient() {
    // agentFloatWallet mock has balance = 10.00
    assertThatThrownBy(() -> treasuryService.withdraw(agentId, new BigDecimal("10.01"), "x", adminUserId))
        .isInstanceOf(BadRequestException.class);                                        // Review Focus #3
}

@Test
void withdrawRejectsInactiveAgentFloatWallet() {
    // agentFloatWallet mock has status = SUSPENDED
    assertThatThrownBy(() -> treasuryService.withdraw(agentId, new BigDecimal("5.00"), "x", adminUserId))
        .isInstanceOf(BadRequestException.class);                                        // Review Focus #4 (withdraw side)
    verifyNoInteractions(ledgerService);
}

@Test
void topupLocksTheSpecificAgentWalletRow() {
    treasuryService.topup(agentId, new BigDecimal("50.00"), "x", adminUserId);
    verify(walletRepository).findByUserIdAndWalletTypeForUpdate(agentUserId, WalletType.AGENT_FLOAT); // Review Focus #1
}
```

Also port the existing `mintRejectsNonPositiveAmount`, `mintRejectsAmountWithMoreThanTwoDecimalPlaces`, `allocateMovesFloatFromUnallocatedToAgent` (rename to `topupMovesFloatFromUnallocatedToAgent`), `allocateSucceedsWhenAmountEqualsAvailableUnallocatedFloat` (rename to `topupSucceedsWhenAmountEqualsAvailableUnallocatedFloat`), `allocateRejectsWhenUnallocatedFloatInsufficient` (rename to `topupRejectsWhenUnallocatedFloatInsufficient`), `withdrawMovesFloatFromAgentToUnallocated`, `withdrawSucceedsWhenAmountEqualsAgentBalance` tests from `CompanyMasterWalletServiceTest`, adapted to wallet-based mocks and the new `void` return type (assert on wallet balances and `verify(walletRepository).save(...)` instead of the response object).

- [ ] **Step 2: Run to verify it fails**

Run: `mvn test -Dtest=TreasuryServiceTest`
Expected: FAIL — `TreasuryService` doesn't exist yet.

- [ ] **Step 3: Create `RequestType` and `RequestStatus` enums**

```java
public enum RequestType { TOPUP, WITHDRAWAL }
public enum RequestStatus { PENDING, APPROVED, REJECTED }
```

- [ ] **Step 4: Create `TreasuryRequestEntity`** (`@Table(name = "treasury_requests")`)

Same fields as the old `AgentFloatRequestEntity` (`id, requestRef, agent, amount, reason, status, decidedBy, decisionNote, decidedAt, createdAt, updatedAt`), using `RequestStatus` in place of `FloatRequestStatus`, plus a new `@Enumerated(STRING) @Column(name = "request_type", nullable = false) RequestType requestType` field. Keep the existing `onCreate`'s default-to-`PENDING` and generated `requestRef` logic unchanged.

- [ ] **Step 5: Create `TreasuryRequestRepository`**

Same five methods as `AgentFloatRequestRepository` (`findAllByOrderByCreatedAtDesc`, `findByRequestRef`, `findAllByAgent_IdOrderByCreatedAtDesc`, `countByStatus`, `sumAmountByStatus`), plus the locked `findByRequestId` query — **this time actually used** (see Step 7; the old service defined this query but called the unlocked `findById` instead, a latent race — fixed here, not carried forward).

- [ ] **Step 6: Create the eight `treasury.dto` records**

`MintRequest(BigDecimal amount, String description)` — same validation annotations as the old `MintFloatRequest`. `TreasuryOverviewResponse(UUID walletId, String currency, BigDecimal unallocatedFloat, BigDecimal totalDistributedFloat, String externalReference, TreasuryMetrics metrics)`. `TreasuryMetrics` — identical shape to the old `CompanyWalletMetrics`. `JournalLineResponse(UUID walletId, String entryType, BigDecimal amount)` — drops the old `ledgerAccountType` field (it no longer exists at the line level). `TransactionJournalResponse` — identical shape to the old `TransactionJournalResponse`, just using the new `JournalLineResponse`. `TreasuryRequestCreateRequest(RequestType requestType, BigDecimal amount, String reason)` — same validation as the old `FloatRequestCreateRequest` plus the new `@NotNull requestType`. `TreasuryRequestDecisionRequest(String reason)` — identical to the old `FloatRequestDecisionRequest`. `TreasuryRequestResponse` — identical shape to the old `FloatRequestResponse` plus a new `String requestType` field (placed right after `requestRef`).

- [ ] **Step 7: Create `TreasuryService`**

```java
public TreasuryOverviewResponse getOverview()
public List<TransactionJournalResponse> getJournalHistory()
public void mint(MintRequest request, UUID adminUserId)
public void topup(UUID agentId, BigDecimal amount, String description, UUID adminUserId)
public void withdraw(UUID agentId, BigDecimal amount, String description, UUID adminUserId)
```

Port `validateScale`, `normalizeZero`, `normalizeScale`, `resolveAdmin` unchanged. `mint`: lock `COMPANY_MASTER_ACCOUNT` then `ESCROW_BANK` via `walletRepository.findByWalletTypeForUpdate`, add `amount` to both balances, post `MINT_FLOAT` with `DEBIT` on the escrow wallet's id and `CREDIT` on the company wallet's id (two *different* ids — this is the exact case Review Focus #5 pins), save both wallets. `topup` (renamed from `allocate`): resolve the agent via `agentRepository.findById`, lock `COMPANY_MASTER_ACCOUNT` and the agent's `AGENT_FLOAT` wallet via `findByUserIdAndWalletTypeForUpdate(agent.getUser().getId(), AGENT_FLOAT)`, reject if that wallet's `status != ACTIVE` or the company wallet's balance `< amount`, move the balances, post `TOPUP_FLOAT` (the renamed `TransactionType` constant from Task 3). `withdraw`: mirror of `topup` (reverse direction), reject if the agent wallet's balance `< amount`. `getOverview`: read `COMPANY_MASTER_ACCOUNT` via `findByWalletType`, compute `totalDistributedFloat` via `walletRepository.sumBalanceByWalletType(AGENT_FLOAT)` (no longer a stored column), build `TreasuryMetrics` from `TreasuryRequestRepository` counts/sums by `RequestStatus`. `getJournalHistory`: unchanged logic, mapping lines to the new `JournalLineResponse(walletId, entryType, amount)`.

- [ ] **Step 8: Run to verify all `TreasuryServiceTest` tests pass**

Run: `mvn test -Dtest=TreasuryServiceTest`
Expected: PASS.

- [ ] **Step 9: Write `TreasuryRequestServiceTest`** (this class has zero existing tests to port — it's new coverage)

```java
@Test
void approveTopupRequestCallsTopup() {
    // request has requestType = TOPUP, status = PENDING
    treasuryRequestService.approveRequest(requestId, adminUserId);
    verify(treasuryService).topup(eq(agentId), eq(amount), any(), eq(adminUserId));
}

@Test
void approveWithdrawalRequestCallsWithdraw() {
    // request has requestType = WITHDRAWAL, status = PENDING
    treasuryRequestService.approveRequest(requestId, adminUserId);
    verify(treasuryService).withdraw(eq(agentId), eq(amount), any(), eq(adminUserId));
}

@Test
void approveNonPendingRequestThrows() {
    // request has status = APPROVED already
    assertThatThrownBy(() -> treasuryRequestService.approveRequest(requestId, adminUserId))
        .isInstanceOf(BadRequestException.class);
    verifyNoInteractions(treasuryService);
}

@Test
void approveAndRejectUseTheLockedRepositoryQuery() {
    treasuryRequestService.approveRequest(requestId, adminUserId);
    verify(treasuryRequestRepository).findByRequestId(requestId);   // fixes the old dead-lock-query bug
    verify(treasuryRequestRepository, never()).findById(requestId);
}

@Test
void rejectRequiresAReason() {
    assertThatThrownBy(() -> treasuryRequestService.rejectRequest(requestId, adminUserId, new TreasuryRequestDecisionRequest("")))
        .isInstanceOf(BadRequestException.class);
}
```

- [ ] **Step 10: Run to verify it fails**

Run: `mvn test -Dtest=TreasuryRequestServiceTest`
Expected: FAIL — `TreasuryRequestService` doesn't exist yet.

- [ ] **Step 11: Create `TreasuryRequestService`**

```java
public TreasuryRequestResponse createRequest(UUID requestingUserId, TreasuryRequestCreateRequest request)
public List<TreasuryRequestResponse> listRequests(UUID callerUserId, boolean canManage)
public TreasuryRequestResponse getRequest(UUID requestId, UUID callerUserId, boolean canManage)
public TreasuryRequestResponse approveRequest(UUID requestId, UUID adminUserId)
public TreasuryRequestResponse rejectRequest(UUID requestId, UUID adminUserId, TreasuryRequestDecisionRequest request)
```

Depends only on `TreasuryRequestRepository`, `AgentRepository`, `UserRepository`, `WalletRepository` (for `toResponse`'s wallet-number/account-number lookup — one call to `findByUserIdAndWalletType(agent.getUser().getId(), AGENT_FLOAT)`, replacing the old two-call wallet+financial-account lookup), and `TreasuryService` directly (no `AgentService` dependency — see Global Constraints). Both `approveRequest` and `rejectRequest` fetch the request via `treasuryRequestRepository.findByRequestId(requestId)` (the locked query), not `findById`. `approveRequest` dispatches on `requestType`: `TOPUP` → `treasuryService.topup(agent.getId(), amount, "Treasury request " + requestRef + " approved", adminUserId)`, `WITHDRAWAL` → `treasuryService.withdraw(...)` with the same description shape.

- [ ] **Step 12: Run to verify all `TreasuryRequestServiceTest` tests pass**

Run: `mvn test -Dtest=TreasuryRequestServiceTest`
Expected: PASS.

- [ ] **Step 13: Create `TreasuryController`** (`@RequestMapping("/api/v1/treasury")`)

Three endpoints only: `GET` (overview, `@PreAuthorize("hasAuthority('COMPANY_WALLET_VIEW')")`), `GET /journal` (`LEDGER_VIEW`), `POST /mint` (`FLOAT_MANAGE`). **No `/topup` or `/withdraw` endpoints** — that bypass is closed per the design decision.

- [ ] **Step 14: Create `TreasuryRequestController`** (`@RequestMapping("/api/v1/treasury/requests")`)

Same five endpoints as the old `FloatRequestController` (`POST`, `GET`, `GET /{requestId}`, `POST /{requestId}/approve`, `POST /{requestId}/reject`), same permission strings (`REQUEST_FLOAT`, `FLOAT_VIEW`, `FLOAT_MANAGE`), same `canManage` authority-check helper.

- [ ] **Step 15: Commit**

```bash
git add src/main/java/com/application/e_wallet/treasury src/test/java/com/application/e_wallet/treasury
git commit -m "feat: add treasury module (mint/topup/withdraw + request inbox), close the admin bypass"
```

---

## Task 5: `agent` module — profile-only cleanup and the DTO split

**Files:**
- Delete: `src/main/java/com/application/e_wallet/agent/entity/AgentFloatRequestEntity.java`
- Delete: `src/main/java/com/application/e_wallet/agent/entity/FloatRequestStatus.java`
- Delete: `src/main/java/com/application/e_wallet/agent/repository/AgentFloatRequestRepository.java`
- Delete: `src/main/java/com/application/e_wallet/agent/service/AgentFloatRequestService.java`
- Delete: `src/main/java/com/application/e_wallet/agent/controller/FloatRequestController.java`
- Delete: `src/main/java/com/application/e_wallet/agent/dto/FloatRequestCreateRequest.java`
- Delete: `src/main/java/com/application/e_wallet/agent/dto/FloatRequestDecisionRequest.java`
- Delete: `src/main/java/com/application/e_wallet/agent/dto/FloatRequestResponse.java`
- Delete: `src/main/java/com/application/e_wallet/agent/dto/FloatTopupRequest.java`
- Delete: `src/main/java/com/application/e_wallet/agent/dto/FloatWithdrawRequest.java`
- Delete: `src/main/java/com/application/e_wallet/agent/dto/AgentResponse.java`
- Create: `src/main/java/com/application/e_wallet/agent/dto/AgentOnboardResponse.java`
- Create: `src/main/java/com/application/e_wallet/agent/dto/AgentDetailResponse.java`
- Create: `src/main/java/com/application/e_wallet/agent/dto/AgentSummaryResponse.java`
- Create: `src/main/java/com/application/e_wallet/agent/dto/AgentUpdateResponse.java`
- Modify: `src/main/java/com/application/e_wallet/agent/service/AgentService.java`
- Modify: `src/main/java/com/application/e_wallet/agent/controller/AgentController.java`
- Test: `src/test/java/com/application/e_wallet/agent/service/AgentServiceTest.java`

**Interfaces:**
- Consumes: `WalletRepository`, `WalletEntity`, `WalletType` (Task 2). No dependency on `treasury` — `AgentService` no longer references `TreasuryService`/`CompanyMasterWalletService` at all.
- Produces: nothing later tasks consume (this is the last module task before cleanup).

- [ ] **Step 1: Write `AgentServiceTest` for the two changed behaviors** (this class has zero existing tests to port — new coverage)

```java
@Test
void createAgentCreatesBothFloatAndCommissionWallets() {
    // ...mocks through role/region/district/user/agent save...
    agentService.createAgent(request);

    ArgumentCaptor<WalletEntity> captor = ArgumentCaptor.forClass(WalletEntity.class);
    verify(walletRepository, times(2)).save(captor.capture());
    List<WalletType> savedTypes = captor.getAllValues().stream().map(WalletEntity::getWalletType).toList();
    assertThat(savedTypes).containsExactlyInAnyOrder(WalletType.AGENT_FLOAT, WalletType.AGENT_COMMISSION);
    assertThat(captor.getAllValues()).allMatch(w -> w.getBalance().compareTo(BigDecimal.ZERO) == 0);
}

@Test
void deactivateAgentClosesBothWallets() {
    // mock findByUserIdAndWalletType(userId, AGENT_FLOAT) and (..., AGENT_COMMISSION) to return ACTIVE wallets
    agentService.deactivateAgent(agentId);

    verify(walletRepository, times(2)).save(argThat(w -> w.getStatus() == WalletStatus.CLOSED));
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn test -Dtest=AgentServiceTest`
Expected: FAIL — `AgentService.createAgent` still only saves one wallet via the old `financial_account` path.

- [ ] **Step 3: Create the four response DTOs**

`AgentOnboardResponse(UUID agentId, String agentNumber, UUID userId, String firstName, String middleName, String lastName, String email, String phone, Gender gender, LocalDate dateOfBirth, String regionId, String regionName, String districtId, String districtName, String floatWalletNumber, String floatAccountNumber, String commissionWalletNumber, String commissionAccountNumber, String status)` — no balance, commission, or transaction-count fields; none of that exists for a brand-new agent.
`AgentDetailResponse` — same identity/location fields as `AgentOnboardResponse` plus `BigDecimal floatBalance, FloatHealth floatHealth, BigDecimal commissionBalance, String floatAccountNumber, String floatWalletNumber, String commissionAccountNumber, String commissionWalletNumber` — omits `transactionCount` entirely (no placeholder).
`AgentSummaryResponse(UUID agentId, String agentNumber, String firstName, String middleName, String lastName, String phone, String regionName, String districtName, String status, BigDecimal floatBalance)` — the lighter list-row shape.
`AgentUpdateResponse(UUID agentId, String firstName, String middleName, String lastName, Gender gender, LocalDate dateOfBirth, String regionId, String regionName, String districtId, String districtName, String status)` — no balance/wallet fields; update doesn't touch those.

- [ ] **Step 4: Rewrite `AgentService.createAgent`**

Remove the `FinancialAccountEntity`/`FinancialAccountRepository` build-and-save; after saving the agent, build and save two `WalletEntity` rows directly (same pattern the method already uses, just two instead of one+financial-account): `AGENT_FLOAT` (`walletNumber = accountNumber = floatNumber`, the existing `generateFloatNumber()`), `AGENT_COMMISSION` (`walletNumber = accountNumber = "COM-" + savedAgent.getAgentNumber()`, matching the migration's own convention from Task 1 Step 2). Both `balance = BigDecimal.ZERO`, `status = ACTIVE`, `user = savedUser`. Return `toOnboardResponse(savedAgent, savedUser, floatWallet, commissionWallet)`.

- [ ] **Step 5: Rewrite `AgentService.deactivateAgent`**

Replace the single wallet+financial-account close with two: fetch `AGENT_FLOAT` and `AGENT_COMMISSION` via `walletRepository.findByUserIdAndWalletType(user.getId(), ...)`, set both to `WalletStatus.CLOSED`, save both. Remove the `FinancialAccountRepository` call entirely.

- [ ] **Step 6: Delete `topupFloat`, `withdrawFloat`, and their `FloatTopupRequest`/`FloatWithdrawRequest`/`CompanyMasterWalletService`/`AllocateFloatRequest`/`AgentWithdrawRequest` imports from `AgentService`**

Per Global Constraints — these are dead code once Task 4's `TreasuryRequestService` calls `TreasuryService` directly and `AgentController`'s float endpoints are removed (Step 9 below).

- [ ] **Step 7: Split `toAgentResponse` into four mapper methods**

`toOnboardResponse(agent, user, floatWallet, commissionWallet)`, `toDetailResponse(agent, user)` (internally fetches both wallets via `walletRepository.findByUserIdAndWalletType`), `toSummaryResponse(agent)` (fetches only the float wallet, for `floatBalance`), `toUpdateResponse(agent, user)` (no wallet lookup at all). Update `createAgent`, `listAgents`, `listAgent`, `updateAgent` to call the matching one.

- [ ] **Step 8: Run to verify all `AgentServiceTest` tests pass**

Run: `mvn test -Dtest=AgentServiceTest`
Expected: PASS.

- [ ] **Step 9: Update `AgentController`**

Remove the `/{agentId}/float/topup` and `/{agentId}/float/withdraw` endpoints entirely (and their `FloatTopupRequest`/`FloatWithdrawRequest` imports). Update the remaining four endpoints' response types: `onboardAgent -> ApiResponse<AgentOnboardResponse>`, `listAgents -> ApiResponse<List<AgentSummaryResponse>>`, `listAgent -> ApiResponse<AgentDetailResponse>`, `updateAgent -> ApiResponse<AgentUpdateResponse>`. `deactivateAgent` is unchanged (`ApiResponse<Void>`).

- [ ] **Step 10: Run the full test suite to confirm nothing else broke**

Run: `mvn test`
Expected: PASS — all suites green (this also catches any remaining compile error from a missed call site).

- [ ] **Step 11: Commit**

```bash
git add src/main/java/com/application/e_wallet/agent src/test/java/com/application/e_wallet/agent
git commit -m "refactor: agent module is profile-only; split AgentResponse into four action-scoped DTOs"
```

---

## Task 6: Delete `financial_account` and `company_wallet`; final verification

**Files:**
- Delete: `src/main/java/com/application/e_wallet/financial_account/` (entire package — `entity/FinancialAccountEntity.java`, `entity/FinancialAccountStatus.java`, `entity/FinancialAccountType.java`, `repository/FinancialAccountRepository.java`)
- Delete: `src/main/java/com/application/e_wallet/company_wallet/` (entire package — `controller/CompanyMasterWalletController.java`, `dto/` (6 files: `AgentWithdrawRequest.java`, `AllocateFloatRequest.java`, `CompanyMasterWalletResponse.java`, `CompanyWalletMetrics.java`, `JournalLineResponse.java`, `MintFloatRequest.java`, `TransactionJournalResponse.java`), `entity/CompanyMasterWalletEntity.java`, `repository/CompanyMasterWalletRepository.java`, `service/CompanyMasterWalletService.java`)
- Delete: `src/test/java/com/application/e_wallet/company_wallet/service/CompanyMasterWalletServiceTest.java`

**Interfaces:** None — by this point nothing in `main` imports either package (verified in Step 1 before deleting).

- [ ] **Step 1: Confirm nothing outside the two packages being deleted still references them**

Run: `grep -rl "financial_account\|company_wallet" src/main/java --include=*.java | grep -v "/financial_account/\|/company_wallet/"`
Expected: no output. (If anything prints, stop — Task 5 missed a call site; fix it before deleting.)

- [ ] **Step 2: Delete the `financial_account` package, the `company_wallet` package, and `CompanyMasterWalletServiceTest`**

- [ ] **Step 3: Run the full build**

Run: `mvn clean test -Dtest='!EWalletApplicationTests'`
Expected: BUILD SUCCESS — clean compile, every unit test in `wallet`, `ledger`, `treasury`, `agent` passes. `EWalletApplicationTests.contextLoads` is excluded deliberately: it fails in this environment on a missing `APP_MAIL_HOST` env var, confirmed pre-existing and unrelated to any code this plan touches — not a regression to chase.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "cleanup: delete financial_account and company_wallet, superseded by wallet and treasury"
```
