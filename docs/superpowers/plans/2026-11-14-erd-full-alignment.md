# Full ERD Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restructure `users`/`customers`/`agents` to match the ERD, add the missing `staff` profile type, adjust `wallets`/`treasury_requests`, and lay the data-model foundation for `tariffs` and `transactions` — all against a freshly dropped-and-recreated dev database, so every migration is a plain create/alter with no backfill logic.

**Architecture:** One migration task rebuilds the schema from an empty database (replaying V1–V31, then applying new V32+ migrations). Application code then follows strict dependency order: identity (`users`/`staff`/`customers`/`agents`, which are all mutually coupled through shared code — `AuthService`, `AgentService`, `DataInitializer`, `JwtService`) lands as one group; `wallet`/`treasury` field adjustments as a second; the brand-new `tariff`/`transaction` data models as a third (no existing consumers, no coupling risk); `modules`/permission-grouping last, since it's pure organization nothing currently depends on.

**Tech Stack:** Spring Boot 4.2 (snapshot), Java 21, Spring Data JPA, PostgreSQL + Flyway, JUnit 5 + Mockito + AssertJ.

**Spec:** `docs/superpowers/specs/2026-11-14-erd-full-alignment-design.md`

## Global Constraints

- **The dev database is dropped and recreated before any new migration runs.** Take a fresh `pg_dump` backup first (same discipline as the wallet/treasury work). Because the database is empty when V32+ run, every new migration is a plain `CREATE`/`ALTER` — no backfill, no nullable-then-lock-down two-step, no "does existing data already violate this constraint" check anywhere in this plan.
- No `password_hash` anywhere — PIN remains the only credential.
- `users.role` is a single `@ManyToOne` FK, not a `@ManyToMany` collection. `user_roles` is dropped.
- Every "who did this" column points at `users`, never a profile table (`staff.createdBy`, `agents.createdBy`, matching the existing `treasury_requests.decidedBy` precedent).
- `agentStatus` (new, on `agents`) is `ACTIVE | SUSPENDED | DEACTIVATED` — an operational standing, independent of `user.status` (which governs login). `deactivateAgent` sets both.
- Every entity gaining a `reference` column per the ERD gets it as `UUID NOT NULL UNIQUE`, generated the same way `id` is (`gen_random_uuid()` at the DB level in migrations; `GenerationType.UUID`-equivalent default in application-created rows — a `@PrePersist` default, consistent with how other `*Number`/`*Ref` fields in this codebase self-generate).
- Currency, money-scale, and other conventions already established by the wallet/treasury work are unchanged and not revisited here.

## Review Focus

1. **Every consumer of `UserEntity.getFirstName/getMiddleName/getLastName` and `.getRoles()` must be updated in the same task that removes those members** — confirmed by grep, the full list is `AuthService`, `AgentService`, `DataInitializer`, `JwtService`, plus `UserEntity.getAuthorities()` itself. Missing even one leaves the whole project non-compiling (the lesson from the wallet/treasury plan's pre-flight scan).
2. **`TreasuryService.getOverview()`/`TreasuryOverviewResponse` read `wallet.getExternalReference()`** — removing that column breaks this unless it's fixed in the same task.
3. **`AgentResponse`-family DTOs currently report `user.getStatus()` as the agent's status** — a real, pre-existing bug this plan's `agentStatus` field exists to fix. A task that adds `agentStatus` but leaves the DTOs reading `user.getStatus()` hasn't actually fixed anything.
4. **`username` collides in spirit with `UserEntity.getUsername()`**, the `UserDetails` contract method that currently returns `id.toString()`. The new `username` field needs its own accessor name (e.g. `getUsernameField()` is ugly — prefer naming the JPA field something the `UserDetails` override doesn't shadow, or confirm Lombok's generated `getUsername()` for the new field would conflict with the interface method and must be renamed at the field level, e.g. call the column/field `username` but expose it via a differently-named getter, or override `UserDetails.getUsername()` deliberately to return the new field instead of `id.toString()` if that's actually desired — **not decided here, left to the builder as a naming/wiring detail, but it must not silently break Spring Security's use of the existing contract method**).
5. **`CustomerRegistrationRequest`/`AgentCreationRequest` need new required fields** (NIDA/DOB/address for customers; businessName/TIN for agents; username for both) — a task that adds the entity columns but not the request DTO fields leaves no way to populate them.

---

## Task 1: Drop and recreate the dev database; write migrations V32–V39

**Pre-step (manual, not a migration file):**
1. Back up the current database: `pg_dump -h localhost -p 5432 -U postgres -d e_walletDB -F c -f e_walletDB_pre_erd_alignment_backup.dump`.
2. Drop and recreate it empty: `DROP DATABASE e_walletDB; CREATE DATABASE e_walletDB;` (or `DROP SCHEMA public CASCADE; CREATE SCHEMA public;` if dropping the whole database isn't permitted by the connection).
3. Run the app (or Flyway directly) so V1–V31 replay in full against the empty database, confirming the baseline (today's shape) rebuilds cleanly before any new migration is written.

**Files:**
- Create: `src/main/resources/db/migration/V32__create_modules_and_permission_grouping.sql`
- Create: `src/main/resources/db/migration/V33__restructure_users_identity.sql`
- Create: `src/main/resources/db/migration/V34__create_staff.sql`
- Create: `src/main/resources/db/migration/V35__expand_customers_kyc.sql`
- Create: `src/main/resources/db/migration/V36__expand_agents_profile.sql`
- Create: `src/main/resources/db/migration/V37__adjust_wallets_and_treasury_requests.sql`
- Create: `src/main/resources/db/migration/V38__create_tariffs.sql`
- Create: `src/main/resources/db/migration/V39__create_transactions.sql`

- [ ] **Step 1: Write `V32__create_modules_and_permission_grouping.sql`**

Groups the ~30 existing permissions (seeded in `V6`) into modules. The grouping scheme is the builder's/this plan's own judgment call — the ERD specifies the *concept*, not which permission belongs to which named module:

```sql
CREATE TABLE modules (
    id UUID PRIMARY KEY,
    reference UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    name VARCHAR(50) NOT NULL UNIQUE,
    description VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

INSERT INTO modules (id, name, description) VALUES
    (gen_random_uuid(), 'USER', 'User account management'),
    (gen_random_uuid(), 'CUSTOMER', 'Customer profile management'),
    (gen_random_uuid(), 'WALLET', 'Wallet viewing and transfers'),
    (gen_random_uuid(), 'TRANSACTION', 'Transaction history, cash-in and cash-out'),
    (gen_random_uuid(), 'AGENT', 'Agent and float operations'),
    (gen_random_uuid(), 'LOAN', 'Loan application and repayment'),
    (gen_random_uuid(), 'FINANCE', 'Reconciliation, ledger and financial reporting'),
    (gen_random_uuid(), 'ROLE', 'Role and permission administration'),
    (gen_random_uuid(), 'TARIFF', 'Fee and charge configuration'),
    (gen_random_uuid(), 'SYSTEM', 'System-level configuration');

ALTER TABLE permissions ADD COLUMN module_reference UUID;

UPDATE permissions SET module_reference = (SELECT id FROM modules WHERE name = 'USER')
    WHERE name IN ('USER_CREATE', 'USER_VIEW', 'USER_UPDATE');
UPDATE permissions SET module_reference = (SELECT id FROM modules WHERE name = 'CUSTOMER')
    WHERE name IN ('CHECK_BALANCE', 'CUSTOMER_VIEW', 'CUSTOMER_UPDATE');
UPDATE permissions SET module_reference = (SELECT id FROM modules WHERE name = 'WALLET')
    WHERE name IN ('WALLET_VIEW', 'COMPANY_WALLET_VIEW', 'WALLET_TO_WALLET_TRANSFER', 'WALLET_TO_BANK_TRANSFER');
-- Corrected during execution: V6 seeded 'TRANSACTION_VIEW' and 'CASHIER_VIEW', but V11 (split
-- TRANSACTION_VIEW into three granular permissions, deleting the coarse one) and V16 (renamed
-- CASHIER_VIEW to AGENT_VIEW) both run after V6 and before this migration — the names below
-- are what actually exists by the time V32 runs, confirmed by querying the rebuilt database.
UPDATE permissions SET module_reference = (SELECT id FROM modules WHERE name = 'TRANSACTION')
    WHERE name IN ('TRANSACTION_VIEW_ALL', 'TRANSACTION_VIEW_OWN', 'TRANSACTION_VIEW_DETAILS', 'TOPUP_CREATE', 'TOPUP_VIEW', 'WITHDRAWAL_CREATE');
UPDATE permissions SET module_reference = (SELECT id FROM modules WHERE name = 'AGENT')
    WHERE name IN ('AGENT_VIEW', 'FLOAT_VIEW', 'REQUEST_FLOAT', 'FLOAT_MANAGE');
UPDATE permissions SET module_reference = (SELECT id FROM modules WHERE name = 'LOAN')
    WHERE name IN ('LOAN_VIEW', 'LOAN_APPLY', 'LOAN_APPROVE', 'LOAN_REPAY');
UPDATE permissions SET module_reference = (SELECT id FROM modules WHERE name = 'FINANCE')
    WHERE name IN ('RECONCILIATION_VIEW', 'RECONCILIATION_MANAGE', 'FINANCIAL_REPORT_VIEW', 'LEDGER_VIEW');
UPDATE permissions SET module_reference = (SELECT id FROM modules WHERE name = 'ROLE')
    WHERE name IN ('ROLE_VIEW', 'ROLE_ASSIGN');
UPDATE permissions SET module_reference = (SELECT id FROM modules WHERE name = 'TARIFF')
    WHERE name IN ('CHARGE_VIEW', 'CHARGE_MANAGE', 'FEES_MANAGE');
UPDATE permissions SET module_reference = (SELECT id FROM modules WHERE name = 'SYSTEM')
    WHERE name IN ('SYSTEM_CONFIG');

ALTER TABLE permissions ALTER COLUMN module_reference SET NOT NULL;
ALTER TABLE permissions ADD CONSTRAINT fk_permissions_module FOREIGN KEY (module_reference) REFERENCES modules(id);
```

- [ ] **Step 2: Verify every permission got a module**

Run: `SELECT name FROM permissions WHERE module_reference IS NULL;` against the rebuilt database.
Expected: zero rows. (If any print, the `UPDATE` list above missed a permission name — check it against `V6`'s actual seed list before moving on.)

- [ ] **Step 3: Write `V33__restructure_users_identity.sql`**

```sql
ALTER TABLE users
    ADD COLUMN reference UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    ADD COLUMN username VARCHAR(100),
    ADD COLUMN last_login_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN role_id UUID;

-- Correction, found during execution: `users` is NOT empty on the freshly recreated database —
-- V26 (already-existing, replayed as part of rebuilding today's shape) seeds one real row: the
-- system/treasury user (id '11111111-1111-1111-1111-111111111111', BLOCKED, null pin_hash,
-- can never log in) that owns the company-level wallets. It needs a real username and role_id
-- before either column can be locked down. None of ADMIN/CASHIER/CUSTOMER/AGENT semantically fit
-- a non-interactive internal principal, so give it its own role rather than forcing a wrong one:
INSERT INTO roles (id, name, description, created_at, updated_at)
VALUES (gen_random_uuid(), 'SYSTEM', 'Internal, non-interactive system principal — cannot log in', NOW(), NOW());

UPDATE users
SET username = 'system_treasury',
    role_id = (SELECT id FROM roles WHERE name = 'SYSTEM')
WHERE id = '11111111-1111-1111-1111-111111111111';

-- Now safe: that was the only existing row, and it's populated.
ALTER TABLE users ALTER COLUMN username SET NOT NULL;
ALTER TABLE users ADD CONSTRAINT uk_users_username UNIQUE (username);
ALTER TABLE users ALTER COLUMN role_id SET NOT NULL;
ALTER TABLE users ADD CONSTRAINT fk_users_role FOREIGN KEY (role_id) REFERENCES roles(id);

DROP TABLE user_roles;

ALTER TABLE users
    DROP COLUMN first_name,
    DROP COLUMN middle_name,
    DROP COLUMN last_name;
```

(Every *other* table this plan touches — `customers`, `agents`, `staff`, `tariffs`, `transactions` — really is empty on the fresh database; `users` was the one exception, because it's the one table an earlier migration seeds a real row into. Worth remembering for any future "is this table really empty" assumption: check every migration that touches it, not just whether the database was just recreated.)

- [ ] **Step 4: Write `V34__create_staff.sql`**

```sql
CREATE TABLE staff (
    id UUID PRIMARY KEY,
    reference UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL UNIQUE,
    staff_no VARCHAR(30) NOT NULL UNIQUE,
    job_title VARCHAR(100) NOT NULL,
    first_name VARCHAR(100) NOT NULL,
    middle_name VARCHAR(100),
    last_name VARCHAR(100) NOT NULL,
    email VARCHAR(255) NOT NULL,
    phone_number VARCHAR(30) NOT NULL,
    created_by UUID,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_staff_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_staff_created_by FOREIGN KEY (created_by) REFERENCES users(id)
);
```

`created_by` is nullable — the bootstrap admin has no creator by definition; every staff member created afterward through the application should populate it.

- [ ] **Step 5: Write `V35__expand_customers_kyc.sql`**

```sql
ALTER TABLE customers
    ADD COLUMN reference UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    ADD COLUMN first_name VARCHAR(100) NOT NULL,
    ADD COLUMN middle_name VARCHAR(100),
    ADD COLUMN last_name VARCHAR(100) NOT NULL,
    ADD COLUMN email VARCHAR(255) NOT NULL,
    ADD COLUMN phone_number VARCHAR(30) NOT NULL,
    ADD COLUMN nida_number VARCHAR(30) NOT NULL,
    ADD COLUMN date_of_birth DATE NOT NULL,
    ADD COLUMN address VARCHAR(255) NOT NULL;
```

(Again, `NOT NULL` with no default is only safe because `customers` is empty on this freshly recreated database.)

- [ ] **Step 6: Write `V36__expand_agents_profile.sql`**

```sql
ALTER TABLE agents
    ADD COLUMN reference UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    ADD COLUMN first_name VARCHAR(100) NOT NULL,
    ADD COLUMN middle_name VARCHAR(100),
    ADD COLUMN last_name VARCHAR(100) NOT NULL,
    ADD COLUMN email VARCHAR(255) NOT NULL,
    ADD COLUMN phone_number VARCHAR(30) NOT NULL,
    ADD COLUMN business_name VARCHAR(150) NOT NULL,
    ADD COLUMN tin_number VARCHAR(30) NOT NULL,
    ADD COLUMN agent_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN created_by UUID;

ALTER TABLE agents ADD CONSTRAINT fk_agents_created_by FOREIGN KEY (created_by) REFERENCES users(id);
```

`gender`/`date_of_birth`-as-already-present and the normalized `region`/`district` FKs are untouched — kept deliberately, per the design doc.

- [ ] **Step 7: Write `V37__adjust_wallets_and_treasury_requests.sql`**

```sql
ALTER TABLE wallets DROP COLUMN external_reference;
ALTER TABLE wallets
    ADD COLUMN reference UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    ADD COLUMN account_name VARCHAR(150) NOT NULL DEFAULT '';
ALTER TABLE wallets ALTER COLUMN account_name DROP DEFAULT;

ALTER TABLE treasury_requests ADD COLUMN external_reference VARCHAR(100);
```

(`account_name` needs a transient default only because the escrow/company-level wallets this V26/V27 already seeded have no name yet when this migration runs — the default is dropped immediately after so future inserts must supply a real value. `treasury_requests.external_reference` is nullable — only `TOPUP` requests backed by a real bank deposit would populate it.)

- [ ] **Step 8: Write `V38__create_tariffs.sql`**

```sql
CREATE TABLE tariffs (
    id UUID PRIMARY KEY,
    reference UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    transaction_type VARCHAR(30) NOT NULL,
    min_amount NUMERIC(19,2) NOT NULL,
    max_amount NUMERIC(19,2) NOT NULL,
    fee NUMERIC(19,2) NOT NULL,
    agent_commission NUMERIC(19,2) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_tariffs_amount_range CHECK (max_amount >= min_amount)
);
```

- [ ] **Step 9: Write `V39__create_transactions.sql`**

```sql
CREATE TABLE transactions (
    id UUID PRIMARY KEY,
    reference_no VARCHAR(60) NOT NULL UNIQUE,
    sender_wallet_id UUID NOT NULL,
    receiver_wallet_id UUID NOT NULL,
    amount NUMERIC(19,2) NOT NULL,
    fee NUMERIC(19,2) NOT NULL DEFAULT 0,
    commission NUMERIC(19,2) NOT NULL DEFAULT 0,
    transaction_type VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_transactions_sender_wallet FOREIGN KEY (sender_wallet_id) REFERENCES wallets(id),
    CONSTRAINT fk_transactions_receiver_wallet FOREIGN KEY (receiver_wallet_id) REFERENCES wallets(id)
);

CREATE INDEX idx_transactions_sender_wallet ON transactions (sender_wallet_id);
CREATE INDEX idx_transactions_receiver_wallet ON transactions (receiver_wallet_id);
```

- [ ] **Step 10: Run the full migration chain against the freshly recreated database**

Run the app (or Flyway directly) so V1–V39 apply in sequence.
Expected: clean apply, no errors. Spot-check: `SELECT * FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;` shows version `39`.

- [ ] **Step 11: Commit**

```bash
git add src/main/resources/db/migration/V32__create_modules_and_permission_grouping.sql src/main/resources/db/migration/V33__restructure_users_identity.sql src/main/resources/db/migration/V34__create_staff.sql src/main/resources/db/migration/V35__expand_customers_kyc.sql src/main/resources/db/migration/V36__expand_agents_profile.sql src/main/resources/db/migration/V37__adjust_wallets_and_treasury_requests.sql src/main/resources/db/migration/V38__create_tariffs.sql src/main/resources/db/migration/V39__create_transactions.sql
git commit -m "migrate: restructure users/customers/agents, add staff/tariffs/transactions/modules per ERD"
```

---

## Task 2: Core identity restructuring — `users`, `staff`, `customers`, `agents`

This is the large, mutually-coupled group. `UserEntity` loses its name fields and its role collection; `CustomerEntity`/`AgentEntity` gain names plus their own new fields; a brand-new `StaffEntity`/`StaffService`/`StaffController` is built; and every shared consumer (`AuthService`, `AgentService`, `DataInitializer`, `JwtService`) is updated in this same task because each touches more than one of these entities at once — splitting this across dispatches would leave the project non-compiling (Review Focus #1).

**Files:**
- Modify: `src/main/java/com/application/e_wallet/user/entity/UserEntity.java`
- Modify: `src/main/java/com/application/e_wallet/role/entity/RoleEntity.java`
- Modify: `src/main/java/com/application/e_wallet/permission/entity/PermissionEntity.java` (add `moduleReference` field only — the full `ModuleEntity`/module-grouping application code is Task 5; this task just needs `PermissionEntity` to compile against the schema from Task 1)
- Modify: `src/main/java/com/application/e_wallet/customer/entity/CustomerEntity.java`
- Modify: `src/main/java/com/application/e_wallet/customer/service/CustomerService.java`
- Modify: `src/main/java/com/application/e_wallet/agent/entity/AgentEntity.java`
- Modify: `src/main/java/com/application/e_wallet/agent/service/AgentService.java`
- Modify: `src/main/java/com/application/e_wallet/agent/dto/AgentCreationRequest.java`, `AgentOnboardResponse.java`, `AgentDetailResponse.java`, `AgentSummaryResponse.java`, `AgentUpdateResponse.java`
- Create: `src/main/java/com/application/e_wallet/agent/entity/AgentStatus.java`
- Modify: `src/main/java/com/application/e_wallet/auth/service/AuthService.java`
- Modify: `src/main/java/com/application/e_wallet/auth/dto/CustomerRegistrationRequest.java`, `UserProfileResponse.java`
- Modify: `src/main/java/com/application/e_wallet/security/DataInitializer.java`
- Modify: `src/main/java/com/application/e_wallet/security/jwt/JwtService.java`
- Create: `src/main/java/com/application/e_wallet/staff/entity/StaffEntity.java`, `StaffStatus.java`
- Create: `src/main/java/com/application/e_wallet/staff/repository/StaffRepository.java`
- Create: `src/main/java/com/application/e_wallet/staff/dto/StaffCreationRequest.java`, `StaffOnboardResponse.java`, `StaffDetailResponse.java`, `StaffSummaryResponse.java`, `StaffUpdateResponse.java`
- Create: `src/main/java/com/application/e_wallet/staff/service/StaffService.java`
- Create: `src/main/java/com/application/e_wallet/staff/controller/StaffController.java`
- Test: focused tests for `AuthService`, `AgentService`, `StaffService`, `CustomerService`'s changed behavior.

**Interfaces:**
- Consumes: Task 1's schema (`users.role_id`, no `user_roles`; `customers`/`agents`' new KYC columns; the `staff` table).
- Produces: nothing a later task needs by exact name — Task 3 and Task 4 don't touch identity.

- [ ] **Step 1: Rewrite `UserEntity`**

Remove `firstName`, `middleName`, `lastName`, the `@ManyToMany roles` field. Add `reference` (`UUID`, generated in `@PrePersist` if null, same pattern as other self-generating fields in this codebase), `username` (`String`), `lastLoginAt` (`Instant`, nullable), `role` (`@ManyToOne(fetch = LAZY, optional = false) @JoinColumn(name = "role_id") RoleEntity`). Rewrite `getAuthorities()` to build from the single `role` (its name plus its permissions) instead of iterating a set. Per Review Focus #4: the new `username` field must not silently break the existing `UserDetails.getUsername()` override (which returns `id.toString()` and backs Spring Security's principal lookup) — name the field's accessor so Lombok doesn't generate a conflicting `getUsername()`, or make a deliberate, explicit choice about which one wins and say so in a comment. This plan does not decide which; it only requires the collision be resolved visibly, not accidentally.

- [ ] **Step 2: Update `RoleEntity`**

Change `@ManyToMany(mappedBy = "roles") Set<UserEntity> users` to `@OneToMany(mappedBy = "role") Set<UserEntity> users` (or drop the inverse side entirely if nothing reads it — check for callers first).

- [ ] **Step 3: Add `PermissionEntity.moduleReference`**

`@Column(name = "module_reference", nullable = false) UUID moduleReference` — a plain UUID, not a `@ManyToOne` to a `ModuleEntity` yet (that entity doesn't exist until Task 5; this task only needs the column mapped so the entity matches the Task 1 schema under `ddl-auto=validate`).

- [ ] **Step 4: Create `AgentStatus` enum**

```java
public enum AgentStatus { ACTIVE, SUSPENDED, DEACTIVATED }
```

- [ ] **Step 5: Rewrite `CustomerEntity`**

Add `reference`, `firstName`, `middleName`, `lastName`, `email`, `phoneNumber`, `nidaNumber`, `dateOfBirth`, `address` — same field-naming convention as the rest of the codebase.

- [ ] **Step 6: Rewrite `CustomerService.createCustomer`**

New signature: `createCustomer(UserEntity user, CustomerRegistrationRequest request) -> CustomerEntity` (needs the request's new KYC fields, not just the user). Update the one caller (`AuthService.registerCustomer`).

- [ ] **Step 7: Rewrite `AgentEntity`**

Add `reference`, `firstName`, `middleName`, `lastName`, `email`, `phoneNumber`, `businessName`, `tinNumber`, `agentStatus` (`AgentStatus`, defaults to `ACTIVE`), `createdBy` (`@ManyToOne UserEntity`). Keep `gender`, `dateOfBirth`, `region`, `district` unchanged.

- [ ] **Step 8: Update `AgentCreationRequest`**

Add `username`, `businessName`, `tinNumber` fields (validated `@NotBlank`, matching the existing style).

- [ ] **Step 9: Rewrite `AgentService`**

`createAgent`: build `UserEntity` without name fields, set `.role(agentRole)` (not `.getRoles().add(...)`), set `username` from the request; build `AgentEntity` with the new profile fields directly (no longer reading names off `user`); set `createdBy` to the acting admin's `UserEntity` (passed in — change the method signature to accept the admin's user id, threaded from `AgentController` via the authenticated principal, same pattern `topupFloat`/`withdrawFloat` used to use). `deactivateAgent`: set both `user.status = DISABLED` and `agent.agentStatus = DEACTIVATED` (Global Constraints). All four `toXResponse` mappers: read names/email/phone off the `AgentEntity` now, not `agent.getUser()`; read `agentStatus` for the DTOs' status field, not `user.getStatus()` (Review Focus #3).

- [ ] **Step 10: Update the four `Agent*Response` DTOs**

Field sources change (per Step 9) but shapes stay the same, except: confirm each DTO's `status` field is documented/understood as the agent's operational status now, not the account's login status.

- [ ] **Step 11: Rewrite `AuthService.registerCustomer`**

Build `UserEntity` without names, `.role(customerRole)`. Call `customerService.createCustomer(savedUser, request)` (new signature). `CustomerRegistrationRequest` gains `nidaNumber`, `dateOfBirth`, `address`, `username` fields (required, matching the design doc's decision to collect full KYC at registration, consistent with how agent onboarding already requires DOB/gender upfront).

- [ ] **Step 12: Rewrite `AuthService.setPin`'s response building and `UserProfileResponse`**

`UserProfileResponse` can no longer read `user.getFirstName()` etc. — fetch the `CustomerEntity` (already available via `customerService.activateCustomer(...)`'s return value, called on the same line) and read names from there instead.

- [ ] **Step 13: Rewrite `AuthService.buildLoginResponse` and `JwtService.generateToken`**

Replace `user.getRoles().stream()...` with the single `user.getRole()` — `roles` claim/field becomes a one-element list (`List.of(user.getRole().getName())`) to keep the API/JWT shape stable for any existing consumer expecting a list; permissions come from `user.getRole().getPermissions()` directly, no stream/flatMap needed. Add the `lastLoginAt` write: set it on `UserEntity` right after a successful PIN check in `login()`, before issuing the session.

- [ ] **Step 14: Rewrite `DataInitializer`**

Build the seed admin `UserEntity` without names, `.role(adminRole)`, with a `username`. After saving the user, create and save a matching `StaffEntity` (the bootstrap admin's own profile) with `createdBy = null` (no creator — it's the bootstrap record), `jobTitle = "System Administrator"` (hardcoded — a new `.env` property for one seeded row isn't worth it; flag to the user if this should become configurable instead), and whatever name/staffNo/email/phone values are appropriate (reuse the existing `app.initial-admin.*` properties for the name fields, now going onto `StaffEntity` instead of `UserEntity`).

- [ ] **Step 15: Create the `staff` module**

`StaffEntity` (id, reference, user `@OneToOne`, staffNo, jobTitle, firstName, middleName, lastName, email, phoneNumber, createdBy `@ManyToOne UserEntity`, status `StaffStatus`, createdAt, updatedAt) and `StaffStatus` (`ACTIVE, SUSPENDED, DISABLED`, mirroring `CustomerStatus`'s shape). `StaffRepository` (`findByUserId`, `existsByStaffNo`). `StaffService` mirroring `AgentService`'s shape: `createStaff`, `listStaff`, `getStaff`, `updateStaff`, `deactivateStaff`, each with its own action-scoped response DTO (`StaffOnboardResponse`, `StaffDetailResponse`, `StaffSummaryResponse`, `StaffUpdateResponse`) — per the DTO convention this whole project has followed since the wallet/treasury work, do not share one `StaffResponse` across all four actions. `StaffController` at `/api/v1/staff`, mirroring `AgentController`'s endpoint shape and `@PreAuthorize` style (reuse `USER_CREATE`/`USER_VIEW`/`USER_UPDATE` permissions — staff administration is a `USER`-module action by the Task 1 grouping). `StaffCreationRequest` includes `jobTitle` (`@NotBlank`) alongside `username`/name/email/phone — every staff member onboarded through the API provides their own, unlike the hardcoded bootstrap admin.

- [ ] **Step 16: Write focused tests**

`AuthServiceTest` (if none exist yet, add one covering `registerCustomer`'s new `CustomerService` call signature and `setPin`'s response now reading from `CustomerEntity`), updated `AgentServiceTest` (createAgent creates the agent with its own name fields and `agentStatus = ACTIVE`; `deactivateAgent` sets both statuses), new `StaffServiceTest` (mirroring `AgentServiceTest`'s shape for the five lifecycle methods).

- [ ] **Step 17: Run the full suite**

Run: `mvn test -Dtest='!EWalletApplicationTests'`
Expected: all green.

- [ ] **Step 18: Commit**

```bash
git add src/main/java/com/application/e_wallet/user src/main/java/com/application/e_wallet/role src/main/java/com/application/e_wallet/permission src/main/java/com/application/e_wallet/customer src/main/java/com/application/e_wallet/agent src/main/java/com/application/e_wallet/auth src/main/java/com/application/e_wallet/security src/main/java/com/application/e_wallet/staff src/test/java/com/application/e_wallet
git commit -m "refactor: restructure users/customers/agents per ERD, add staff module"
```

---

## Task 3: `wallet`/`treasury` field adjustments

**Files:**
- Modify: `src/main/java/com/application/e_wallet/wallet/entity/WalletEntity.java`
- Modify: `src/main/java/com/application/e_wallet/wallet/service/WalletService.java`
- Modify: `src/main/java/com/application/e_wallet/treasury/entity/TreasuryRequestEntity.java`
- Modify: `src/main/java/com/application/e_wallet/treasury/service/TreasuryService.java`, `TreasuryRequestService.java`
- Modify: `src/main/java/com/application/e_wallet/treasury/dto/TreasuryOverviewResponse.java`, `TreasuryRequestCreateRequest.java`, `TreasuryRequestResponse.java`
- Modify: `src/main/java/com/application/e_wallet/agent/service/AgentService.java` (wallet creation call sites only — set `accountName` when building each agent's wallets)
- Test: updated `WalletServiceTest`, `TreasuryServiceTest`, `TreasuryRequestServiceTest`.

**Interfaces:**
- Consumes: Task 1's schema (`wallets` without `external_reference`, with `reference`/`account_name`; `treasury_requests.external_reference`); Task 2's `CustomerEntity.getFirstName()/getLastName()` and `AgentEntity.getFirstName()/getLastName()` (Steps 2/3 below build `accountName` from these — this task cannot run before Task 2 lands, not just after Task 1's migrations).
- Produces: nothing Task 4 needs.

- [ ] **Step 1: Remove `WalletEntity.externalReference`, add `reference`/`accountName`**

`accountName` is set at wallet-creation time, not defaulted in the entity — every creation call site (Step 2/3) must supply it.

- [ ] **Step 2: Update `WalletService.createWallet`**

Set `accountName` to the customer's full name (`firstName + " " + lastName`, from the now-expanded `CustomerEntity`).

- [ ] **Step 3: Update `AgentService`'s wallet-creation code** (the two `WalletEntity.builder()` calls for `AGENT_FLOAT`/`AGENT_COMMISSION`)

Set `accountName` to `"<agent full name> — Float"` / `"— Commission"` respectively (per the design doc's resolved convention).

- [ ] **Step 4: Fix `TreasuryService.getOverview()` and `TreasuryOverviewResponse`** (Review Focus #2)

Remove the `externalReference` field from `TreasuryOverviewResponse` and the line in `getOverview()` that reads `companyWallet.getExternalReference()` — that column no longer exists.

- [ ] **Step 5: Add `TreasuryRequestEntity.externalReference`, thread it through create/response**

`TreasuryRequestCreateRequest` gains an optional `externalReference` field (nullable — only meaningful for `TOPUP` requests backed by a real bank deposit); `TreasuryRequestResponse` gains the matching field; `TreasuryRequestService.createRequest` persists it.

- [ ] **Step 6: Update tests, run the full suite, commit**

Run: `mvn test -Dtest='!EWalletApplicationTests'` — confirm green, including Task 2's tests (this task touches files Task 2 also touches, e.g. `AgentService`'s wallet-creation lines — run the whole suite, not just this task's new assertions).

```bash
git add src/main/java/com/application/e_wallet/wallet src/main/java/com/application/e_wallet/treasury src/main/java/com/application/e_wallet/agent/service/AgentService.java src/test/java/com/application/e_wallet/wallet src/test/java/com/application/e_wallet/treasury
git commit -m "refactor: align wallets/treasury_requests fields with the ERD"
```

---

## Task 4: `tariff` and `transaction` modules (data model only)

New, standalone — nothing existing depends on either, per the design doc's non-goals (business logic for fee calculation and cash-in/cash-out is explicitly out of scope; this is the foundation, same scoping as the original wallet/treasury design doc used for these two modules).

**Files:**
- Create: `src/main/java/com/application/e_wallet/tariff/entity/TariffEntity.java`
- Create: `src/main/java/com/application/e_wallet/tariff/repository/TariffRepository.java`
- Create: `src/main/java/com/application/e_wallet/transaction/entity/TransactionEntity.java`, `TransactionType.java`, `TransactionStatus.java`
- Create: `src/main/java/com/application/e_wallet/transaction/repository/TransactionRepository.java`
- Test: minimal repository-level tests if the project's conventions call for them (this codebase's existing tests are all service-layer unit tests against mocked repositories — a bare entity/repository with no service logic yet may not need a dedicated test; use judgment, don't invent coverage for code with no behavior).

**Interfaces:** None — nothing in this plan or the existing codebase calls into either module yet.

- [ ] **Step 1: Create `TariffEntity`**

Fields matching `V38`: `id`, `reference`, `transactionType` (`String` or a shared enum — see Step 3), `minAmount`, `maxAmount`, `fee`, `agentCommission`, `active`, `createdAt`.

- [ ] **Step 2: Create `TariffRepository`**

`findByTransactionTypeAndActiveTrue(String transactionType)` — the one lookup a future fee-calculation feature would need; nothing more, per YAGNI.

- [ ] **Step 3: Create `TransactionType`, `TransactionStatus`, `TransactionEntity`**

`TransactionType`: `CASH_IN, CASH_OUT, INTERNAL_TRANSFER, COMMISSION_TRANSFER` (matching the original wallet/treasury design doc's sketch). `TransactionStatus`: `PENDING, COMPLETED, FAILED` (or whatever minimal set a not-yet-built feature needs — this is a placeholder shape, not a finished state machine). `TransactionEntity` fields matching `V39`: `id`, `referenceNo`, `senderWallet`/`receiverWallet` (`@ManyToOne WalletEntity` — real relationships, not raw UUIDs, consistent with how `treasury`/`ledger` already do this), `amount`, `fee`, `commission`, `transactionType`, `status`, `createdAt`.

- [ ] **Step 4: Create `TransactionRepository`**

Bare `JpaRepository<TransactionEntity, UUID>` — no custom queries yet; nothing calls this module.

- [ ] **Step 5: Run the full suite, commit**

Run: `mvn test -Dtest='!EWalletApplicationTests'`

```bash
git add src/main/java/com/application/e_wallet/tariff src/main/java/com/application/e_wallet/transaction
git commit -m "feat: add tariff and transaction data model foundation"
```

---

## Task 5: `modules`/permission-grouping application code (lowest priority)

Pure organization — nothing currently reads module groupings, so this is safe to do last, or skip if time runs out (noted as such in the design doc).

**Files:**
- Create: `src/main/java/com/application/e_wallet/module/entity/ModuleEntity.java`
- Create: `src/main/java/com/application/e_wallet/module/repository/ModuleRepository.java`
- Modify: `src/main/java/com/application/e_wallet/permission/entity/PermissionEntity.java` (change `moduleReference` from a plain `UUID` to a real `@ManyToOne ModuleEntity module`)

**Interfaces:** None.

- [ ] **Step 1: Create `ModuleEntity`**

`id`, `reference`, `name`, `description`, `createdAt` — matching `V32`.

- [ ] **Step 2: Create `ModuleRepository`**

`findByName(String name)`.

- [ ] **Step 3: Change `PermissionEntity.moduleReference` to a real relationship**

`@ManyToOne(fetch = LAZY, optional = false) @JoinColumn(name = "module_reference") ModuleEntity module`, replacing the plain `UUID` field from Task 2 Step 3.

- [ ] **Step 4: Run the full suite, commit**

```bash
git add src/main/java/com/application/e_wallet/module src/main/java/com/application/e_wallet/permission
git commit -m "feat: give permissions a real module relationship"
```

---

## Task 6: Final verification

- [ ] **Step 1: Confirm no leftover references to removed members**

Run: `grep -rn "getFirstName\|getMiddleName\|getLastName" src/main/java/com/application/e_wallet/user/ src/main/java/com/application/e_wallet/auth/ src/main/java/com/application/e_wallet/agent/ src/main/java/com/application/e_wallet/security/` — expected: no hits against `UserEntity`'s old accessors (hits against `CustomerEntity`/`AgentEntity`/`StaffEntity`'s *own* new accessors are fine and expected).
Run: `grep -rn "getExternalReference" src/main/java/com/application/e_wallet/` — expected: no output.
Run: `grep -rn "\.getRoles()\." src/main/java/com/application/e_wallet/` — expected: no output (everything uses the singular `.getRole()` now).

- [ ] **Step 2: Run the full build**

Run: `mvn clean test -Dtest='!EWalletApplicationTests'`
Expected: BUILD SUCCESS, every test passes. (`EWalletApplicationTests.contextLoads` stays excluded — same pre-existing, unrelated `APP_MAIL_HOST` environment gap as before.)

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "chore: final verification pass for ERD alignment"
```
