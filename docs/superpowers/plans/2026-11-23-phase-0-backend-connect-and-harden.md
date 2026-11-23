# Phase 0 (Backend): Connect and Harden — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every money movement in the backend safe to demo and safe to build on: customers withdraw with their own PIN, retries never double-post, concurrent postings can't deadlock, the riskiest code is tested, and the API is documented, containerised and checked by CI.

**Architecture:** Changes stay inside the existing feature packages (`transaction`, `wallet`, `dashboard/agent`, `common`) plus one new package, `idempotency`. Every money flow keeps the existing shape (one `@Transactional` method → lock wallets → validate → move balances → `LedgerService.postJournal` → `transactions` row). New in this phase: locking goes through one `WalletLockService`, and controller calls for money POSTs go through `IdempotencyService`. Schema changes are new Flyway files from `V48`.

**Tech Stack:** Java 21, Spring Boot 4.2 (parent `4.2.0-SNAPSHOT`), Spring Data JPA, Spring Security, Flyway, PostgreSQL 16, JUnit 5, Mockito, AssertJ, Testcontainers, springdoc-openapi, Spring Boot Actuator, Docker, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-11-23-flagship-wallet-program-design.md`, sections *Phase 0 — Connect and harden*, *Database → Schema findings*, *Docker*, *CI*. The frontend half of Phase 0 (API client, drift fixes, dashboards on real data, frontend Docker/CI) is a separate plan.

## Global Constraints

- Never edit a merged migration. New files start at `V48`; one change per file. `ddl-auto=validate` must keep passing.
- Money is `BigDecimal` / `numeric(19,2)`. Balances change only in the same `@Transactional` method that posts the journal.
- Every money `POST` requires an `Idempotency-Key` header: same key + same body returns the stored response; same key + different body returns **409**.
- Wallets locked in one posting are locked in ascending `id` order.
- Withdrawals are customer-initiated: the customer enters the agent's float account number and the amount in their own app and confirms with **their own PIN**. No endpoint accepts a customer PIN from an agent.
- Existing response wrapper stays: `ApiResponse<T>` with `timestamp`, `status`, `message`, `data`.
- Error mapping: `BadRequestException` → 400, `DuplicationResourceException` → 409, `TooManyRequestsException` → 429, unhandled → 500.
- Business timezone `Africa/Dar_es_Salaam`; currency `TZS`; minimum cash-in and withdraw amount `1000.00`.
- TDD for everything that moves money: failing test first.
- Commit style: Conventional Commits (`feat(…)`, `fix(…)`, `test(…)`, `chore(…)`), one branch per task group off `main`.

## Before you start (environment)

- Docker must be running locally: integration tests use Testcontainers PostgreSQL.
- The parent POM is `spring-boot-starter-parent:4.2.0-SNAPSHOT` from `https://repo.spring.io/snapshot`. Builds depend on that snapshot repository being reachable. If Spring Boot 4.2.0 has a GA release by the time you start, ask before switching. It is a one-line change but affects every dependency.
- Two test files don't compile today (Task 1). Run nothing else until Task 1 is green.

## Review Focus

1. **Double-tap on *Withdraw* or *Cash-in*** (two identical requests within milliseconds, same `Idempotency-Key`): exactly one journal and one `transactions` row. The second caller gets the first response, or a 409 "still processing", never a second posting. Pinned in Task 6.
2. **Customer withdraws at an agent who is SUSPENDED, or enters a customer's account number instead of an agent's float account**: 400 with a clear message, and no balance moves. Pinned in Task 4.
3. **Withdraw where balance covers the amount but not amount + fee** (e.g. balance 10,200, amount 10,000, fee 300): rejected as insufficient balance. Pinned in Task 4.
4. **Two postings touching the same two wallets at once** (agent cash-in to customer X while X withdraws at the same agent): both complete without a deadlock, and the final balances equal the sum of both journals. Pinned in Task 4 (Task 3 covers two cash-ins).
5. **A permission named in `@PreAuthorize` that no role holds** (the `CASHIER_VIEW` bug): caught by a test before merge. Pinned in Task 7.

---

## File Structure

| File | Responsibility |
|---|---|
| `src/test/java/.../ledger/service/LedgerServiceTest.java`, `.../treasury/service/TreasuryServiceTest.java` | Modify: fix stale enum import (Task 1) |
| `src/test/java/.../support/PostgresIntegrationTest.java` | Create: Testcontainers base class for every `*IntegrationTest` (Task 2) |
| `src/test/java/.../support/TestDataFactory.java` | Create: builds a customer, an active agent and funded wallets through real repositories (Task 2) |
| `src/test/java/.../transaction/service/TransactionServiceTest.java` | Create: unit tests for cash-in and withdraw rules (Tasks 2, 4) |
| `src/test/java/.../transaction/TransactionPostingIntegrationTest.java` | Create: real-DB tests of posting legs, concurrency and idempotency (Tasks 2–4, 6) |
| `src/main/java/.../wallet/service/WalletLockService.java` | Create: locks a set of wallets in id order (Task 3) |
| `src/main/java/.../wallet/repository/WalletRepository.java` | Modify: add `findAllByIdInForUpdate` (Task 3) |
| `src/main/java/.../transaction/service/TransactionService.java` | Modify: cash-in uses `WalletLockService`; `cashOut` replaced by `withdraw` (Tasks 3, 4) |
| `src/main/java/.../transaction/dto/WithdrawRequest.java`, `WithdrawResponse.java` | Create; delete `CashOutRequest.java`, `CashOutResponse.java` (Task 4) |
| `src/main/java/.../transaction/controller/TransactionController.java` | Modify: `/withdraw` replaces `/cash-out`; idempotency header (Tasks 4, 6) |
| `src/main/java/.../dashboard/agent/controller/AgentWithdrawalController.java` + dto `AgentWithdrawalItem.java` | Create: agent's confirmation feed (Task 5) |
| `src/main/java/.../idempotency/{IdempotencyKeyEntity, IdempotencyStatus, IdempotencyKeyRepository, IdempotencyService, IdempotencyConflictException}.java` | Create (Task 6) |
| `src/main/java/.../common/exception/GlobalExceptionHandler.java` | Modify: 409 for `IdempotencyConflictException`, 400 for `MissingRequestHeaderException` (Task 6) |
| `src/main/java/.../treasury/controller/{TreasuryController, TreasuryRequestController}.java` | Modify: idempotency on mint, commission top-up, approve, reject (Task 6) |
| `src/main/java/.../dashboard/agent/controller/AgentDashboardController.java` | Modify: `CASHIER_VIEW` → `AGENT_VIEW` (Task 7) |
| `src/test/java/.../security/PermissionConsistencyIntegrationTest.java` | Create (Task 7) |
| `src/main/java/.../agent/entity/AgentEntity.java` | Modify: column `update_at` → `updated_at` (Task 8) |
| `src/main/resources/db/migration/V48__add_withdrawal_request_permission.sql` | Task 4 |
| `src/main/resources/db/migration/V49__create_idempotency_keys.sql` | Task 6 |
| `src/main/resources/db/migration/V50__schema_hardening.sql` | Task 8 |
| `src/main/resources/db/migration/V51__rename_agents_update_at.sql` | Task 8 |
| `pom.xml`, `src/main/resources/application.properties`, `security/SecurityConfig.java` | Modify: Testcontainers (Task 2), springdoc + actuator (Task 9) |
| `Dockerfile`, `.dockerignore`, `docker-compose.yml`, `.github/workflows/ci.yml` | Create (Task 10) |

Paths abbreviate `com/application/e_wallet` as `...`.

---

### Task 1: Make the test suite compile again

`LedgerServiceTest` and `TreasuryServiceTest` import `com.application.e_wallet.ledger.entity.TransactionType`, which was renamed to `LedgerTransactionType`. Until this is fixed, no test runs.

**Files:**
- Modify: `src/test/java/.../ledger/service/LedgerServiceTest.java:6`
- Modify: `src/test/java/.../treasury/service/TreasuryServiceTest.java:8`

**Interfaces:**
- Produces: a compiling, passing test suite (baseline for every later task).

- [ ] **Step 1: Confirm the failure**

Run: `./mvnw -B test-compile`
Expected: FAIL, `cannot find symbol … ledger.entity.TransactionType`

- [ ] **Step 2: Replace the import and every use of `TransactionType` in both files with `LedgerTransactionType`**

- [ ] **Step 3: Run the unit tests**

Run: `./mvnw -B test -Dtest='*ServiceTest'`
Expected: PASS. Any failure other than a compile error predates this plan: record it in the PR description, and don't fix it in this task.

- [ ] **Step 4: Commit**

```bash
git add src/test
git commit -m "test: fix stale LedgerTransactionType imports so the suite compiles"
```

---

### Task 2: Test harness + cash-in characterization tests

Pin today's cash-in behaviour before changing it.

**Files:**
- Modify: `pom.xml` (test scope: `org.springframework.boot:spring-boot-testcontainers`, `org.testcontainers:testcontainers-postgresql`, `org.testcontainers:testcontainers-junit-jupiter`; versions from the Boot BOM)
- Create: `src/test/java/.../support/PostgresIntegrationTest.java`
- Create: `src/test/java/.../support/TestDataFactory.java`
- Create: `src/test/java/.../transaction/service/TransactionServiceTest.java`
- Create: `src/test/java/.../transaction/TransactionPostingIntegrationTest.java`
- Modify: `src/test/java/.../EWalletApplicationTests.java` (extend `PostgresIntegrationTest`)

**Interfaces:**
- Produces:
  - `abstract class PostgresIntegrationTest`: `@SpringBootTest`, one static `postgres:16-alpine` container with `@ServiceConnection`, `@Container`. Properties: `app.initial-admin.enabled=false`, a fixed test `JWT_SECRET`. Subclasses get a migrated, empty schema plus seed data.
  - `TestDataFactory` (`@Component` in test sources):
    - `Customer createCustomer(String phone, String pin, BigDecimal balance)`
    - `Agent createAgent(String pin, BigDecimal floatBalance, AgentStatus status)`
    - `void fundCommissionPool(BigDecimal amount)`
    - records `Customer(UserEntity user, WalletEntity wallet)` and `Agent(UserEntity user, WalletEntity floatWallet, WalletEntity commissionWallet)`
    - Wallet balances are set directly through `WalletRepository` (test setup only).
    - `String bearerFor(UserEntity user)` = `"Bearer " + jwtService.generateAccessToken(user)` for MockMvc calls
  - Helper on the IT class: `BigDecimal journalNet(String referencePrefix)` = Σ DEBIT − Σ CREDIT over `transaction_journal_line` for journals whose `reference_number` starts with the prefix.

- [ ] **Step 1: Write unit tests for `TransactionService.cashIn` (Mockito, same style as `LedgerServiceTest`)**

```java
@Test void cashInRejectsInactiveAgent()            // AgentStatus.SUSPENDED → BadRequestException("Agent is not active"); verifyNoInteractions(ledgerService)
@Test void cashInRejectsWrongAgentPin()            // pinEncoder.matches → false → "Invalid agent PIN"
@Test void cashInRejectsPhoneNotMatchingAccount()  // → "Phone number does not match the account holder"
@Test void cashInRejectsInsufficientFloat()        // float 5,000, amount 10,000 → "Insufficient agent float balance"
@Test void cashInPostsFourBalancedLines()          // captor on postJournal: CASH_IN, 4 lines,
                                                   // DEBIT float 10000, CREDIT customer 10000, DEBIT pool 200, CREDIT agent commission 200
```

- [ ] **Step 2: Run them**

Run: `./mvnw -B test -Dtest=TransactionServiceTest`
Expected: PASS. They describe existing behaviour. If one fails, the test is wrong, not the code: fix the test.

- [ ] **Step 3: Write the cash-in integration test**

```java
@Test void cashInMovesBalancesAndPostsBalancedJournal() {
    // agent float 100,000; customer 0; pool 10,000; tariff CASH_IN fee 0, commission 200
    // cashIn 10,000
    assertThat(floatAfter).isEqualByComparingTo("90000.00");
    assertThat(customerAfter).isEqualByComparingTo("10000.00");
    assertThat(agentCommissionAfter).isEqualByComparingTo("200.00");
    assertThat(journalNet("CASH_IN-")).isEqualByComparingTo("0");
    assertThat(transactionRepository.count()).isEqualTo(1);
}
```

- [ ] **Step 4: Run all tests**

Run: `./mvnw -B test`
Expected: PASS (`EWalletApplicationTests.contextLoads` now boots against Testcontainers).

- [ ] **Step 5: Commit**

```bash
git add pom.xml src/test
git commit -m "test(transaction): add Testcontainers harness and cash-in characterization tests"
```

---

### Task 3: Lock wallets in id order

**Files:**
- Modify: `src/main/java/.../wallet/repository/WalletRepository.java`
- Create: `src/main/java/.../wallet/service/WalletLockService.java`
- Modify: `src/main/java/.../transaction/service/TransactionService.java` (cash-in)
- Test: `src/test/java/.../wallet/service/WalletLockServiceTest.java`, `TransactionPostingIntegrationTest.java`

**Interfaces:**
- Consumes: `TestDataFactory` (Task 2).
- Produces:
  - `WalletRepository`: `@Lock(PESSIMISTIC_WRITE) @Query("SELECT w FROM WalletEntity w WHERE w.id IN :ids ORDER BY w.id") List<WalletEntity> findAllByIdInForUpdate(@Param("ids") Collection<UUID> ids)`
  - `WalletLockService.lockInOrder(Collection<UUID> walletIds) -> Map<UUID, WalletEntity>`: de-duplicates ids, locks them all in one ordered query, and throws `IllegalStateException("Wallet not found: <id>")` if any id is missing. Must be called inside an existing transaction (`@Transactional(propagation = MANDATORY)`).

- [ ] **Step 1: Write failing tests**

```java
// WalletLockServiceTest (Mockito)
@Test void lockInOrderDeduplicatesAndReturnsMapById()
@Test void lockInOrderThrowsWhenAWalletIsMissing()   // repo returns 1 of 2 → IllegalStateException

// TransactionPostingIntegrationTest
@Test void concurrentCashInAndCashInOnSameWalletsDoNotDeadlock() {
    // two threads, same agent + same customer, each cash-in 5,000, started together with a CountDownLatch
    // both complete within 10 s; customer balance == 10,000; journalNet("CASH_IN-") == 0
}
```

- [ ] **Step 2: Run them and see them fail**

Run: `./mvnw -B test -Dtest='WalletLockServiceTest,TransactionPostingIntegrationTest'`
Expected: FAIL (`WalletLockService` does not exist)

- [ ] **Step 3: Implement `WalletLockService` and the repository method. Refactor `cashIn` to:** (1) resolve all four wallets *without* locks to collect their ids, (2) call `lockInOrder` once, (3) run all status and balance checks against the locked entities. Messages and journal lines stay unchanged.

- [ ] **Step 4: Run the tests from Steps 1–2 plus `TransactionServiceTest`**

Run: `./mvnw -B test -Dtest='WalletLockServiceTest,TransactionServiceTest,TransactionPostingIntegrationTest'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "refactor(wallet): lock wallets in id order for every posting"
```

---

### Task 4: Customer-initiated withdrawal (replaces agent cash-out)

**Files:**
- Create: `src/main/resources/db/migration/V48__add_withdrawal_request_permission.sql`
- Create: `src/main/java/.../transaction/dto/WithdrawRequest.java`, `WithdrawResponse.java`
- Delete: `src/main/java/.../transaction/dto/CashOutRequest.java`, `CashOutResponse.java`
- Modify: `src/main/java/.../transaction/service/TransactionService.java`, `transaction/controller/TransactionController.java`
- Test: `TransactionServiceTest.java`, `TransactionPostingIntegrationTest.java`

**Interfaces:**
- Consumes: `WalletLockService.lockInOrder` (Task 3).
- Produces:
  - Permission `WITHDRAWAL_REQUEST` (module `TRANSACTION`), granted to role `CUSTOMER`.
  - `record WithdrawRequest(@NotBlank String agentAccountNumber, @NotNull @DecimalMin("1000.00") BigDecimal amount, @NotBlank String pin)`
  - `record WithdrawResponse(String transactionReference, String status, String currency, BigDecimal amount, BigDecimal fee, BigDecimal totalDebited, BigDecimal newBalance, String agentNumber, String agentBusinessName, OffsetDateTime createdAt)`
  - `TransactionService.withdraw(WithdrawRequest request, UUID customerUserId) -> WithdrawResponse`
  - `POST /api/v1/transactions/withdraw`, `@PreAuthorize("hasAuthority('WITHDRAWAL_REQUEST')")`. `POST /api/v1/transactions/cash-out` is removed.
  - Journal: `LedgerTransactionType.CASH_OUT` with exactly today's five lines (customer DEBIT amount+fee; agent float CREDIT amount; pool DEBIT commission; agent commission CREDIT commission; revenue CREDIT fee). `transactions` row: type `CASH_OUT`, sender = customer wallet, receiver = agent float.

- [ ] **Step 1: Write failing unit tests**

```java
@Test void withdrawRejectsWrongCustomerPin()              // "Invalid PIN"
@Test void withdrawRejectsNonAgentFloatAccount()          // account is CUSTOMER_MAIN → "Account number is not an agent float account"
@Test void withdrawRejectsSuspendedAgent()                // → "Agent is not active"
@Test void withdrawRejectsInactiveCustomer()              // CustomerStatus.SUSPENDED → "Customer account is not active"
@Test void withdrawRejectsBalanceCoveringAmountButNotFee()// balance 10,200, amount 10,000, fee 300 → "Insufficient balance"
@Test void withdrawPostsFiveBalancedLines()               // captor: lines and amounts as in Interfaces
```

- [ ] **Step 2: Run and see them fail**

Run: `./mvnw -B test -Dtest=TransactionServiceTest`
Expected: FAIL (`withdraw` does not exist)

- [ ] **Step 3: Write `V48`**: insert `WITHDRAWAL_REQUEST` ("Customer withdraws cash at an agent") into `permissions` with the `TRANSACTION` module's id, and grant it to `CUSTOMER` in `role_permissions`.

- [ ] **Step 4: Implement `withdraw`, add the endpoint, and delete `cashOut`, its DTOs and its endpoint.** The customer is the authenticated user. The agent is found through the float wallet's owner (`agentRepository.findByUserId`).

- [ ] **Step 5: Write the integration test**

```java
@Test void withdrawMovesFiveLegsAndBalances() {
    // customer 50,000; agent float 0; pool 10,000; CASH_OUT fee 300, commission 200; withdraw 10,000
    assertThat(customerAfter).isEqualByComparingTo("39700.00");
    assertThat(floatAfter).isEqualByComparingTo("10000.00");
    assertThat(revenueAfter.subtract(revenueBefore)).isEqualByComparingTo("300.00");
    assertThat(agentCommissionAfter).isEqualByComparingTo("200.00");
    assertThat(journalNet("CASH_OUT-")).isEqualByComparingTo("0");
}
@Test void concurrentCashInAndWithdrawAtSameAgentDoNotDeadlock() {
    // customer 50,000, agent float 50,000; thread A: agent cash-in 5,000 to the customer; thread B: customer withdraws 10,000 at the same agent
    // both complete within 10 s; customer == 50,000 + 5,000 − 10,300; journalNet("CASH_IN-") and journalNet("CASH_OUT-") both 0
}
```

- [ ] **Step 6: Run everything**

Run: `./mvnw -B test`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src
git commit -m "fix(cash-out): customer-initiated withdraw with own PIN; remove agent cash-out"
```

---

### Task 5: Agent confirmation feed

Lets the agent's screen show a withdrawal the moment the customer confirms it. The frontend polls every 5 s.

**Files:**
- Modify: `src/main/java/.../transaction/repository/TransactionRepository.java`
- Create: `src/main/java/.../dashboard/agent/controller/AgentWithdrawalController.java`, `dashboard/agent/dto/AgentWithdrawalItem.java`
- Modify: `src/main/java/.../dashboard/agent/service/AgentDashboardService.java`
- Test: `src/test/java/.../dashboard/agent/service/AgentDashboardServiceTest.java`, `TransactionPostingIntegrationTest.java`

**Interfaces:**
- Consumes: `withdraw` (Task 4).
- Produces:
  - `TransactionRepository.findByReceiverWalletIdAndTransactionTypeAndCreatedAtAfterOrderByCreatedAtAsc(UUID walletId, String type, OffsetDateTime after) -> List<TransactionEntity>`
  - `record AgentWithdrawalItem(String transactionReference, BigDecimal amount, String customerName, String customerPhoneMasked, OffsetDateTime createdAt)`. Phone masked as `+255 7•• ••• 123`, keeping the country code, first digit and last 3.
  - `AgentDashboardService.withdrawalsSince(UUID agentUserId, OffsetDateTime since) -> List<AgentWithdrawalItem>`. `since` defaults to 15 minutes ago and is clamped to at most 24 hours back.
  - `GET /api/v1/agent/withdrawals?since=<ISO-8601>`, `@PreAuthorize("hasAuthority('AGENT_VIEW')")`

- [ ] **Step 1: Write failing tests**

```java
@Test void withdrawalsSinceReturnsOnlyThisAgentsCashOutsAfterSince()
@Test void withdrawalsSinceClampsSinceToTwentyFourHours()
@Test void withdrawalsSinceMasksCustomerPhone()   // "+255712345123" → "+255 7•• ••• 123"
```

- [ ] **Step 2: Run them and see them fail.** Run: `./mvnw -B test -Dtest=AgentDashboardServiceTest`. Expected: FAIL.

- [ ] **Step 3: Implement the repository method, the service method and the controller.**

- [ ] **Step 4: Run them.** Run: `./mvnw -B test -Dtest='AgentDashboardServiceTest,TransactionPostingIntegrationTest'`. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "feat(agent): withdrawal confirmation feed for agent polling"
```

---

### Task 6: Idempotency for money POSTs

**Files:**
- Create: `src/main/resources/db/migration/V49__create_idempotency_keys.sql` (DDL exactly as the spec's *Tables to add → idempotency_keys*)
- Create: `src/main/java/.../idempotency/IdempotencyKeyEntity.java`, `IdempotencyStatus.java`, `IdempotencyKeyRepository.java`, `IdempotencyService.java`, `IdempotencyConflictException.java`
- Modify: `common/exception/GlobalExceptionHandler.java`, `transaction/controller/TransactionController.java`, `treasury/controller/TreasuryController.java` (mint, commission top-up), `treasury/controller/TreasuryRequestController.java` (approve, reject)
- Test: `src/test/java/.../idempotency/IdempotencyServiceTest.java`, `TransactionPostingIntegrationTest.java`

**Interfaces:**
- Produces:
  - `enum IdempotencyStatus { IN_PROGRESS, COMPLETED, FAILED }`
  - `IdempotencyService.execute(UUID userId, String key, String endpoint, Object requestBody, Class<T> responseType, Supplier<T> action) -> T`
    - The request hash is SHA-256 hex of the request body serialised by the app's `ObjectMapper`.
    - No row → insert `IN_PROGRESS` in its own transaction (`REQUIRES_NEW`), run `action`, store `response_json` + `http_status=200`, mark `COMPLETED`, return.
    - Row with a different hash → `IdempotencyConflictException("Idempotency-Key reused with a different request")`.
    - Row `COMPLETED`, same hash → deserialise and return the stored response, without running `action`.
    - Row `IN_PROGRESS`, same hash → `IdempotencyConflictException("Request with this Idempotency-Key is still processing")`.
    - Row `FAILED`, same hash → run again as if new (reuse the row).
    - `action` throws → mark `FAILED`, rethrow the original exception.
    - The insert hits the unique `(user_id, idem_key)` race → treat as `IN_PROGRESS`.
  - `IdempotencyConflictException extends RuntimeException` → 409 via `GlobalExceptionHandler`.
  - `MissingRequestHeaderException` → 400 with message `"<header> header is required"`.
  - Every money endpoint takes `@RequestHeader("Idempotency-Key") @Size(min = 8, max = 80) String idempotencyKey`. Endpoint names stored: `transactions.cash-in`, `transactions.withdraw`, `treasury.mint`, `treasury.commission-topup`, `treasury.request-approve`, `treasury.request-reject`.

- [ ] **Step 1: Write failing unit tests (`IdempotencyServiceTest`)**

```java
@Test void firstCallRunsActionAndStoresResponse()
@Test void repeatWithSameBodyReturnsStoredResponseWithoutRunningAction()
@Test void repeatWithDifferentBodyThrowsConflict()
@Test void repeatWhileInProgressThrowsConflict()
@Test void failedActionMarksFailedAndAllowsRetry()
```

- [ ] **Step 2: Run them and see them fail.** Run: `./mvnw -B test -Dtest=IdempotencyServiceTest`. Expected: FAIL.

- [ ] **Step 3: Implement `V49`, the entity, repository, service and exception, and the two handler mappings.**

- [ ] **Step 4: Wire the six endpoints through `IdempotencyService.execute`.**

- [ ] **Step 5: Write the integration test for the double-tap case (Review Focus 1)**

```java
@Test void doubleWithdrawWithSameKeyPostsOnce() {
    // two threads call the withdraw endpoint via MockMvc with TestDataFactory.bearerFor(customer), same key and body
    assertThat(journalRepository.countByReferenceNumberStartingWith("CASH_OUT-")).isEqualTo(1);
    assertThat(statuses).containsAnyOf(200, 409).doesNotContain(500);
    assertThat(customerAfter).isEqualByComparingTo("39700.00");
}
@Test void moneyPostWithoutIdempotencyKeyIs400()
```

- [ ] **Step 6: Run everything.** Run: `./mvnw -B test`. Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src
git commit -m "feat(idempotency): Idempotency-Key on all money POSTs"
```

---

### Task 7: Fix the locked agent dashboard + permission guard test

`AgentDashboardController` requires `CASHIER_VIEW`, which V16 renamed to `AGENT_VIEW`, so every agent gets 403 today.

**Files:**
- Modify: `src/main/java/.../dashboard/agent/controller/AgentDashboardController.java:26`
- Create: `src/test/java/.../security/PermissionConsistencyIntegrationTest.java`

**Interfaces:**
- Produces: a guard test that fails whenever any `@PreAuthorize("hasAuthority('X')")` in `com.application.e_wallet` names a permission that isn't in the `permissions` table, or that no role holds.

- [ ] **Step 1: Write the failing guard test.** Scan `@RestController` beans from the `ApplicationContext`, read each handler method's `@PreAuthorize` value, extract names with the regex `hasAuthority\('([A-Z_]+)'\)`, and compare against `SELECT p.name FROM permissions p JOIN role_permissions rp ON rp.permission_id = p.id`.

```java
@Test void everyPreAuthorizePermissionExistsAndIsGranted() {
    assertThat(missing).as("permissions referenced in code but held by no role").isEmpty();
}
```

- [ ] **Step 2: Run it.** Run: `./mvnw -B test -Dtest=PermissionConsistencyIntegrationTest`. Expected: FAIL, listing `CASHIER_VIEW`.

- [ ] **Step 3: Change the annotation to `hasAuthority('AGENT_VIEW')`.**

- [ ] **Step 4: Run it again.** Expected: PASS. If it also lists other permissions, fix each the same way and say which in the commit message.

- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "fix(agent): dashboard required renamed CASHIER_VIEW; add permission guard test"
```

---

### Task 8: Schema hardening (spec findings 1–5)

**Files:**
- Create: `src/main/resources/db/migration/V50__schema_hardening.sql`
- Create: `src/main/resources/db/migration/V51__rename_agents_update_at.sql`
- Modify: `src/main/java/.../agent/entity/AgentEntity.java:99` (`@Column(name = "updated_at", …)`)
- Test: `src/test/java/.../SchemaHardeningIntegrationTest.java`

**Interfaces:**
- Produces, in `V50`:
  - Indexes `idx_journal_line_wallet_id (wallet_id)` and `idx_journal_line_journal_id (journal_id)`.
  - Drop the duplicate plain indexes `idx_users_phone`, `idx_users_email`, `idx_roles_name`, `idx_permissions_name`, `idx_otp_user_id` and `idx_pin_reset_tokens_user_id`. Their unique indexes remain.
  - `CHECK (amount > 0)` on `transaction_journal_line` and `transactions`; `CHECK (fee >= 0 AND commission >= 0)` on `transactions`.
  - `CHECK … IN (…)` on:
    - `users.status`: `UserStatus` values
    - `customers.status`: PENDING, ACTIVE, SUSPENDED
    - `wallets.status`: ACTIVE, SUSPENDED, BLOCKED, CLOSED
    - `wallets.wallet_type`: the 7 `WalletType` values
    - `transactions.status`: PENDING, COMPLETED, FAILED
    - `transaction_journal_line.entry_type`: DEBIT, CREDIT
    - `staff.status`: ACTIVE, SUSPENDED, DISABLED
    - `agents.agent_status`: ACTIVE, SUSPENDED, DEACTIVATED
- `V51`: `ALTER TABLE agents RENAME COLUMN update_at TO updated_at`.

- [ ] **Step 1: Write failing integration tests**

```java
@Test void journalLineRejectsZeroAmount()            // native insert amount 0 → DataIntegrityViolationException
@Test void walletRejectsUnknownStatus()               // native update status 'FROZEN' → DataIntegrityViolationException
@Test void journalLineWalletIdIsIndexed()             // pg_indexes has idx_journal_line_wallet_id
@Test void agentsHasUpdatedAtColumn()                 // information_schema: agents.updated_at exists, update_at doesn't
```

- [ ] **Step 2: Run them.** Run: `./mvnw -B test -Dtest=SchemaHardeningIntegrationTest`. Expected: FAIL.

- [ ] **Step 3: Write `V50`, `V51` and the entity mapping change.**

- [ ] **Step 4: Run everything.** Run: `./mvnw -B test`. Expected: PASS, and `contextLoads` proves `ddl-auto=validate` still agrees.

- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "fix(db): journal indexes, amount and status checks, drop duplicate indexes, rename agents.updated_at"
```

---

### Task 9: OpenAPI docs + health endpoint

**Files:**
- Modify: `pom.xml` (add `org.springdoc:springdoc-openapi-starter-webmvc-ui` in the 3.x line that supports Spring Boot 4, and `spring-boot-starter-actuator`)
- Create: `src/main/java/.../config/OpenApiConfig.java`
- Modify: `src/main/resources/application.properties`, `security/SecurityConfig.java:41-53`
- Test: `src/test/java/.../ApiDocsIntegrationTest.java`

**Interfaces:**
- Produces:
  - `OpenApiConfig`: an `OpenAPI` bean titled `"e-Wallet API"`, version `"v1"`, with an HTTP bearer JWT security scheme named `bearerAuth`.
  - Properties: `springdoc.api-docs.enabled=${API_DOCS_ENABLED:true}`, `management.endpoints.web.exposure.include=health`, `management.endpoint.health.probes.enabled=true`.
  - `permitAll` additions: `/v3/api-docs/**`, `/swagger-ui/**`, `/swagger-ui.html`, `/actuator/health/**`.

- [ ] **Step 1: Write failing tests**

```java
@Test void apiDocsListWithdrawEndpoint()   // GET /v3/api-docs → 200, body contains "/api/v1/transactions/withdraw" and "Idempotency-Key"
@Test void healthIsUpWithoutAuth()          // GET /actuator/health → 200, body contains "\"status\":\"UP\""
```

- [ ] **Step 2: Run them.** Run: `./mvnw -B test -Dtest=ApiDocsIntegrationTest`. Expected: FAIL (404).

- [ ] **Step 3: Add the dependencies, config, properties and security matchers.**

- [ ] **Step 4: Run them again.** Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add pom.xml src
git commit -m "chore(api): OpenAPI docs and actuator health"
```

---

### Task 10: Docker, Compose and CI

**Files:**
- Create: `Dockerfile`, `.dockerignore` (`target/`, `.git/`, `.env`, `logs/`, `.idea/`)
- Create: `docker-compose.yml` (services `db`, `mail`, `backend`; the frontend plan adds `frontend`)
- Create: `.github/workflows/ci.yml`
- Modify: `.env.example` (add `API_DOCS_ENABLED=true`)

**Interfaces:**
- Produces: the Dockerfile and Compose file as in the spec's *Docker* section, but with only `db`, `mail` and `backend`. `backend` gets a healthcheck on `GET /actuator/health` (`wget -qO- http://localhost:8080/actuator/health`). CI is the spec's backend workflow, with the Postgres service removed (Testcontainers starts its own) and `./mvnw -B verify` as the check.

- [ ] **Step 1: Build the image.** Run: `docker build -t e-wallet-backend:dev .`. Expected: success; the image runs as non-root user `wallet`.

- [ ] **Step 2: Start the stack.** Run: `cp .env.example .env` (fill in values), then `docker compose up -d`, then `curl -s localhost:8080/actuator/health`. Expected: `{"status":"UP"…}`, and Mailpit's UI on `localhost:8025` shows an email after an OTP request.

- [ ] **Step 3: Add the CI workflow and push the branch.** Expected: the GitHub Actions run is green on the PR.

- [ ] **Step 4: Commit**

```bash
git add Dockerfile .dockerignore docker-compose.yml .github .env.example
git commit -m "chore(ci): Dockerfile, compose stack and GitHub Actions"
```

---

## Done when

- `./mvnw -B verify` is green locally and in CI.
- `POST /api/v1/transactions/cash-out` no longer exists. No request body anywhere has a `customerPin` field.
- A repeated money POST with the same `Idempotency-Key` never produces a second journal.
- `GET /api/v1/agent/dashboard/overview` returns 200 for an agent.
- `docker compose up` gives a healthy backend with migrations up to `V51`.
