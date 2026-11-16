# Admin Dashboard Overview Endpoint Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `GET /api/v1/dashboard/overview`, a single endpoint an admin dashboard polls to show live liquidity, today's transaction activity, action alerts (pending float requests, low-float agents), and basic network health.

**Architecture:** A new `com.application.e_wallet.dashboard` module (controller → service → DTOs) aggregates read-only queries across the existing `wallet`, `ledger`, `treasury`, `agent`, `customer`, and `transaction` repositories. No new write paths, no caching, no push transport — the client polls, the server always computes fresh. Two small schema migrations (indexes, a new permission) land first.

**Tech Stack:** Spring Boot 4.2 (Java 21), Spring Data JPA, Flyway (PostgreSQL), JUnit 5 + Mockito + AssertJ (existing project conventions — service-layer unit tests with mocked repositories; this codebase has no `@DataJpaTest` or controller-layer tests anywhere, so this plan doesn't introduce either).

**Spec:** `docs/superpowers/specs/2026-11-16-admin-dashboard-overview-design.md`

## Global Constraints

- Currency is fixed `"TZS"` — no multi-currency handling.
- `todayTransactionMetrics` (totals, breakdown, hourly trend) counts `COMPLETED` transactions only.
- `networkStats.pendingTransactionsToday` and `.failedTransactionsToday` are each `{count, volume}`, sourced from `PENDING`/`FAILED` transactions respectively — not folded into `todayTransactionMetrics`.
- No `paymentChannel` field anywhere in the response (doesn't exist in the schema; not added).
- Live = client polling only. No WebSocket/SSE, no caching layer.
- Endpoint is gated by a new `DASHBOARD_VIEW` permission, not a reuse of an existing one.
- "Low float" uses the single existing threshold, `FloatHealth.LOW_THRESHOLD` (100000) — never redefined or duplicated.
- "Today" and hour boundaries are computed in a fixed zone, `Africa/Dar_es_Salaam` (UTC+3) — never the JVM default zone.
- `todayTransactionMetrics`/`failedTransactionsToday`/`pendingTransactionsToday` correctly return all-zero right now — nothing writes a `TransactionEntity` row yet (no cash-in/cash-out feature exists). That's expected; this plan does not add a write path.

## Review Focus

- **BigDecimal zero-comparison must use `compareTo`, never `equals`.** `COALESCE(SUM(...), 0)` and `BigDecimal.ZERO` can differ in scale (`0` vs `0.00`), which `.equals()` treats as unequal. `isLedgerBalanced` and every `{count, volume}` zero-state must compare with `compareTo(...) == 0`.
- **The low-float threshold comparison is strict `<`, matching `FloatHealth.fromBalance`'s existing semantics.** A float wallet balance exactly equal to 100000.00 must NOT appear in `lowFloatAgents` — only strictly below it.
- **Every empty-state renders as zero/empty, never null or an exception.** No pending requests, no low-float agents, no transactions today — each section must still return a complete, zeroed-out object.
- **The hourly trend buckets by the agent's local hour (`Africa/Dar_es_Salaam`), not the stored UTC instant's hour.** A transaction timestamped near local midnight must land in the correct day/hour after zone conversion, not spill into the wrong bucket.
- **`recentRequests`'s 10-item cap never affects `pendingFloatRequests.count`/`.totalRequestedAmount`.** Those two numbers always reflect *all* pending requests; the list is a separate, capped view.

---

## Task 1: Database migrations — indexes and the DASHBOARD_VIEW permission

**Files:**
- Create: `src/main/resources/db/migration/V42__add_dashboard_overview_indexes.sql`
- Create: `src/main/resources/db/migration/V43__add_dashboard_view_permission.sql`

**Interfaces:**
- Produces: a `DASHBOARD_VIEW` row in `permissions`, granted to the `ADMIN` role in `role_permissions`. Later tasks' `@PreAuthorize("hasAuthority('DASHBOARD_VIEW')")` depends on this row existing.

- [ ] **Step 1: Write `V42__add_dashboard_overview_indexes.sql`**

```sql
CREATE INDEX idx_transactions_status_created_at ON transactions (status, created_at);
CREATE INDEX idx_wallets_wallet_type ON wallets (wallet_type);
CREATE INDEX idx_treasury_requests_status ON treasury_requests (status);
```

- [ ] **Step 2: Write `V43__add_dashboard_view_permission.sql`**

```sql
INSERT INTO permissions (id, name, description, module_reference, created_at, updated_at)
VALUES (gen_random_uuid(), 'DASHBOARD_VIEW', 'View the admin live dashboard overview',
        (SELECT id FROM modules WHERE name = 'FINANCE'), NOW(), NOW());

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.name = 'ADMIN' AND p.name = 'DASHBOARD_VIEW';
```

- [ ] **Step 3: Verify the migrations apply cleanly**

Run: `mvn test -Dtest=EWalletApplicationTests`
Expected: PASS (`contextLoads` boots Spring, which runs Flyway against the configured dev database — a failure here means a SQL error in either file)

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/db/migration/V42__add_dashboard_overview_indexes.sql src/main/resources/db/migration/V43__add_dashboard_view_permission.sql
git commit -m "feat: add dashboard overview indexes and DASHBOARD_VIEW permission"
```

---

## Task 2: Liquidity cards section

**Files:**
- Modify: `src/main/java/com/application/e_wallet/ledger/repository/TransactionJournalLineRepository.java`
- Create: `src/main/java/com/application/e_wallet/dashboard/dto/LiquidityCards.java`
- Create: `src/main/java/com/application/e_wallet/dashboard/service/DashboardService.java`
- Test: `src/test/java/com/application/e_wallet/dashboard/service/DashboardServiceTest.java`

**Interfaces:**
- Consumes: `WalletRepository.sumBalanceByWalletType(WalletType) -> BigDecimal` (exists).
- Produces: `LiquidityCards(BigDecimal escrowBankBalance, BigDecimal companyMasterBalance, BigDecimal companyRevenueBalance, BigDecimal totalAgentFloatBalance, BigDecimal totalAgentCommissionBalance, BigDecimal totalCustomerWalletBalance, boolean isLedgerBalanced)`. `DashboardService.buildLiquidityCards()` (package-private, not `private` — later tasks' tests call section-builder methods directly before `getOverview()` exists to wire them together in Task 6). `TransactionJournalLineRepository.sumAmountByEntryType(EntryType) -> BigDecimal`.

- [ ] **Step 1: Write the failing tests**

```java
@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {
    @Mock private WalletRepository walletRepository;
    @Mock private TransactionJournalLineRepository transactionJournalLineRepository;
    @InjectMocks private DashboardService dashboardService;

    @Test
    void buildLiquidityCardsSumsEachWalletTypeAndFlagsBalancedLedger() {
        when(walletRepository.sumBalanceByWalletType(WalletType.ESCROW_BANK)).thenReturn(new BigDecimal("50000000.00"));
        when(walletRepository.sumBalanceByWalletType(WalletType.COMPANY_MASTER_ACCOUNT)).thenReturn(new BigDecimal("18500000.00"));
        when(walletRepository.sumBalanceByWalletType(WalletType.REVENUE)).thenReturn(new BigDecimal("1250000.00"));
        when(walletRepository.sumBalanceByWalletType(WalletType.AGENT_FLOAT)).thenReturn(new BigDecimal("19100000.00"));
        when(walletRepository.sumBalanceByWalletType(WalletType.AGENT_COMMISSION)).thenReturn(new BigDecimal("850000.00"));
        when(walletRepository.sumBalanceByWalletType(WalletType.CUSTOMER_MAIN)).thenReturn(new BigDecimal("10300000.00"));
        when(transactionJournalLineRepository.sumAmountByEntryType(EntryType.DEBIT)).thenReturn(new BigDecimal("100000.00"));
        when(transactionJournalLineRepository.sumAmountByEntryType(EntryType.CREDIT)).thenReturn(new BigDecimal("100000.00"));

        LiquidityCards result = dashboardService.buildLiquidityCards();

        assertThat(result.escrowBankBalance()).isEqualByComparingTo("50000000.00");
        assertThat(result.totalCustomerWalletBalance()).isEqualByComparingTo("10300000.00");
        assertThat(result.isLedgerBalanced()).isTrue();
    }

    @Test
    void buildLiquidityCardsFlagsUnbalancedLedger() {
        when(walletRepository.sumBalanceByWalletType(any())).thenReturn(BigDecimal.ZERO);
        when(transactionJournalLineRepository.sumAmountByEntryType(EntryType.DEBIT)).thenReturn(new BigDecimal("100000.00"));
        when(transactionJournalLineRepository.sumAmountByEntryType(EntryType.CREDIT)).thenReturn(new BigDecimal("99999.00"));

        LiquidityCards result = dashboardService.buildLiquidityCards();

        assertThat(result.isLedgerBalanced()).isFalse();
    }

    @Test
    void buildLiquidityCardsTreatsScaleDifferenceAsBalanced() {
        // DEBIT returns scale-0 zero, CREDIT returns scale-2 zero (the COALESCE(...,0) vs NUMERIC(19,2) mismatch
        // the design doc flags) — must still compare equal via compareTo, not equals.
        when(walletRepository.sumBalanceByWalletType(any())).thenReturn(BigDecimal.ZERO);
        when(transactionJournalLineRepository.sumAmountByEntryType(EntryType.DEBIT)).thenReturn(BigDecimal.ZERO);
        when(transactionJournalLineRepository.sumAmountByEntryType(EntryType.CREDIT)).thenReturn(new BigDecimal("0.00"));

        LiquidityCards result = dashboardService.buildLiquidityCards();

        assertThat(result.isLedgerBalanced()).isTrue();
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn test -Dtest=DashboardServiceTest`
Expected: FAIL (compile error — `DashboardService`, `LiquidityCards`, `sumAmountByEntryType` don't exist yet)

- [ ] **Step 3: Add `sumAmountByEntryType(EntryType) -> BigDecimal` to `TransactionJournalLineRepository`**

Same `@Query("SELECT COALESCE(SUM(l.amount), 0) FROM TransactionJournalLineEntity l WHERE l.entryType = :entryType")` style already used by `WalletRepository.sumBalanceByWalletType`.

- [ ] **Step 4: Create the `LiquidityCards` record** with the fields listed in Interfaces, in that order.

- [ ] **Step 5: Create `DashboardService`** (`@Service`, `@RequiredArgsConstructor`), with fields `walletRepository` (`WalletRepository`) and `transactionJournalLineRepository` (`TransactionJournalLineRepository`), and package-private method `LiquidityCards buildLiquidityCards()`.

Compare the two entry-type sums with `compareTo(...) == 0`, not `.equals(...)` (Review Focus item 1).

- [ ] **Step 6: Run the tests to verify they pass**

Run: `mvn test -Dtest=DashboardServiceTest`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/application/e_wallet/ledger/repository/TransactionJournalLineRepository.java src/main/java/com/application/e_wallet/dashboard/dto/LiquidityCards.java src/main/java/com/application/e_wallet/dashboard/service/DashboardService.java src/test/java/com/application/e_wallet/dashboard/service/DashboardServiceTest.java
git commit -m "feat: compute dashboard liquidity cards"
```

---

## Task 3: Today's transaction metrics section

**Files:**
- Modify: `src/main/java/com/application/e_wallet/transaction/repository/TransactionRepository.java`
- Modify: `src/main/java/com/application/e_wallet/dashboard/service/DashboardService.java`
- Create: `src/main/java/com/application/e_wallet/dashboard/dto/TransactionTypeMetric.java`
- Create: `src/main/java/com/application/e_wallet/dashboard/dto/TransactionBreakdown.java`
- Create: `src/main/java/com/application/e_wallet/dashboard/dto/HourlyTrendPoint.java`
- Create: `src/main/java/com/application/e_wallet/dashboard/dto/TodayTransactionMetrics.java`
- Test: `src/test/java/com/application/e_wallet/dashboard/service/DashboardServiceTest.java`

**Interfaces:**
- Produces, for reuse by Task 5: `TransactionRepository.findByStatusAndCreatedAtBetween(String status, OffsetDateTime start, OffsetDateTime end) -> List<TransactionEntity>`; `DashboardService.DASHBOARD_ZONE` (package-private `static final ZoneId`, value `ZoneId.of("Africa/Dar_es_Salaam")`); package-private nested record `DashboardService.DayWindow(OffsetDateTime start, OffsetDateTime end)`; package-private static method `DashboardService.today() -> DayWindow` (start = local midnight today in `DASHBOARD_ZONE`, end = `OffsetDateTime.now(DASHBOARD_ZONE)`).
- Produces: `TransactionTypeMetric(long count, BigDecimal volume)` — reused again in Task 5 for `networkStats`. `TransactionBreakdown(TransactionTypeMetric cashIn, TransactionTypeMetric cashOut, TransactionTypeMetric internalTransfer, TransactionTypeMetric commissionTransfer)`. `HourlyTrendPoint(String hour, BigDecimal volume, long count)`, `hour` formatted `"HH:00"`. `TodayTransactionMetrics(BigDecimal totalVolume, long totalCount, TransactionBreakdown breakdown, List<HourlyTrendPoint> hourlyTrend)`. `DashboardService.buildTodayTransactionMetrics(DayWindow window)` (package-private).
- Consumes: nothing new from earlier tasks besides `DashboardService`'s existing shape from Task 2.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void buildTodayTransactionMetricsSumsOnlyCompletedTransactionsIntoBreakdownAndTotals() {
    DashboardService.DayWindow window = new DashboardService.DayWindow(
            OffsetDateTime.parse("2026-11-16T00:00:00+03:00"), OffsetDateTime.parse("2026-11-16T10:30:00+03:00"));

    TransactionEntity cashIn = transaction("CASH_IN", "COMPLETED", "100.00", "2026-11-16T08:00:00+03:00");
    TransactionEntity cashOut = transaction("CASH_OUT", "COMPLETED", "50.00", "2026-11-16T09:00:00+03:00");

    when(transactionRepository.findByStatusAndCreatedAtBetween("COMPLETED", window.start(), window.end()))
            .thenReturn(List.of(cashIn, cashOut));

    TodayTransactionMetrics result = dashboardService.buildTodayTransactionMetrics(window);

    assertThat(result.totalVolume()).isEqualByComparingTo("150.00");
    assertThat(result.totalCount()).isEqualTo(2);
    assertThat(result.breakdown().cashIn().count()).isEqualTo(1);
    assertThat(result.breakdown().cashIn().volume()).isEqualByComparingTo("100.00");
    assertThat(result.breakdown().cashOut().volume()).isEqualByComparingTo("50.00");
    assertThat(result.breakdown().internalTransfer().count()).isZero();
    assertThat(result.breakdown().commissionTransfer().count()).isZero();
}

@Test
void buildTodayTransactionMetricsBucketsHourlyTrendByLocalHourUpToNow() {
    DashboardService.DayWindow window = new DashboardService.DayWindow(
            OffsetDateTime.parse("2026-11-16T00:00:00+03:00"), OffsetDateTime.parse("2026-11-16T10:30:00+03:00"));

    // Stored as UTC — 2026-11-15T21:30:00Z is 2026-11-16T00:30:00+03:00: today's first half-hour, local bucket "00:00".
    TransactionEntity nearMidnight = transaction("CASH_IN", "COMPLETED", "10.00", "2026-11-15T21:30:00Z");

    when(transactionRepository.findByStatusAndCreatedAtBetween("COMPLETED", window.start(), window.end()))
            .thenReturn(List.of(nearMidnight));

    TodayTransactionMetrics result = dashboardService.buildTodayTransactionMetrics(window);

    assertThat(result.hourlyTrend()).hasSize(11); // "00:00".."10:00" inclusive
    assertThat(result.hourlyTrend().get(0).hour()).isEqualTo("00:00");
    assertThat(result.hourlyTrend().get(0).count()).isEqualTo(1);
    assertThat(result.hourlyTrend().get(0).volume()).isEqualByComparingTo("10.00");
    assertThat(result.hourlyTrend().get(10).hour()).isEqualTo("10:00");
    assertThat(result.hourlyTrend().get(10).count()).isZero();
}

@Test
void buildTodayTransactionMetricsHandlesNoTransactionsToday() {
    DashboardService.DayWindow window = new DashboardService.DayWindow(
            OffsetDateTime.parse("2026-11-16T00:00:00+03:00"), OffsetDateTime.parse("2026-11-16T00:00:00+03:00"));

    when(transactionRepository.findByStatusAndCreatedAtBetween("COMPLETED", window.start(), window.end()))
            .thenReturn(List.of());

    TodayTransactionMetrics result = dashboardService.buildTodayTransactionMetrics(window);

    assertThat(result.totalVolume()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(result.totalCount()).isZero();
    assertThat(result.hourlyTrend()).hasSize(1); // just "00:00"
}
```

A `transaction(String type, String status, String amount, String createdAtIso)` test helper building a `TransactionEntity` via its builder is needed — add it as a private static method in the test class.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn test -Dtest=DashboardServiceTest`
Expected: FAIL (compile error — `findByStatusAndCreatedAtBetween`, `DayWindow`, `buildTodayTransactionMetrics`, the new DTOs don't exist yet)

- [ ] **Step 3: Add `findByStatusAndCreatedAtBetween(String status, OffsetDateTime start, OffsetDateTime end) -> List<TransactionEntity>` to `TransactionRepository`**

A plain Spring Data derived query (matches the entity's `status`/`createdAt` field names exactly) — no `@Query` needed.

- [ ] **Step 4: Create the `TransactionTypeMetric`, `TransactionBreakdown`, `HourlyTrendPoint`, `TodayTransactionMetrics` records** with the fields and order listed in Interfaces.

- [ ] **Step 5: Add `DASHBOARD_ZONE`, `DayWindow`, `today()`, and `buildTodayTransactionMetrics(DayWindow)` to `DashboardService`**

Add `TransactionRepository transactionRepository` as a new constructor field. For the breakdown, filter the fetched list by `transactionType` matching each of `TransactionType.CASH_IN/CASH_OUT/INTERNAL_TRANSFER/COMMISSION_TRANSFER`'s `.name()` (the column is a raw `String`, not enumerated) and reduce each filtered list to a `TransactionTypeMetric` (count = size, volume = sum via `compareTo`-safe addition starting from `BigDecimal.ZERO`). For the hourly trend, convert each transaction's `createdAt` to `DASHBOARD_ZONE` with `atZoneSameInstant`, bucket by `.getHour()`, and synthesize one `HourlyTrendPoint` for every hour from `0` through `window.end()`'s local hour inclusive (zero-filled where nothing happened), formatting `hour` as `"%02d:00"`.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `mvn test -Dtest=DashboardServiceTest`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/application/e_wallet/transaction/repository/TransactionRepository.java src/main/java/com/application/e_wallet/dashboard/service/DashboardService.java src/main/java/com/application/e_wallet/dashboard/dto/TransactionTypeMetric.java src/main/java/com/application/e_wallet/dashboard/dto/TransactionBreakdown.java src/main/java/com/application/e_wallet/dashboard/dto/HourlyTrendPoint.java src/main/java/com/application/e_wallet/dashboard/dto/TodayTransactionMetrics.java src/test/java/com/application/e_wallet/dashboard/service/DashboardServiceTest.java
git commit -m "feat: compute today's transaction metrics for the dashboard"
```

---

## Task 4: Action alerts section (pending float requests, low-float agents)

**Files:**
- Modify: `src/main/java/com/application/e_wallet/treasury/repository/TreasuryRequestRepository.java`
- Modify: `src/main/java/com/application/e_wallet/agent/repository/AgentRepository.java`
- Modify: `src/main/java/com/application/e_wallet/agent/dto/FloatHealth.java`
- Modify: `src/main/java/com/application/e_wallet/dashboard/service/DashboardService.java`
- Create: `src/main/java/com/application/e_wallet/agent/repository/LowFloatAgentProjection.java`
- Create: `src/main/java/com/application/e_wallet/dashboard/dto/PendingFloatRequestItem.java`
- Create: `src/main/java/com/application/e_wallet/dashboard/dto/PendingFloatRequestsSummary.java`
- Create: `src/main/java/com/application/e_wallet/dashboard/dto/LowFloatAgentItem.java`
- Create: `src/main/java/com/application/e_wallet/dashboard/dto/LowFloatAgentsSummary.java`
- Create: `src/main/java/com/application/e_wallet/dashboard/dto/ActionAlerts.java`
- Test: `src/test/java/com/application/e_wallet/dashboard/service/DashboardServiceTest.java`

**Interfaces:**
- Consumes: `TreasuryRequestRepository.countByStatus(RequestStatus) -> long` and `.sumAmountByStatus(RequestStatus) -> BigDecimal` (both exist). `WalletRepository.findByUserIdAndWalletType(UUID, WalletType) -> Optional<WalletEntity>` (exists).
- Produces: `LowFloatAgentProjection` interface (`getAgentId():UUID`, `getAgentCode():String`, `getBusinessName():String`, `getPhoneNumber():String`, `getFloatAccountNumber():String`, `getCurrentBalance():BigDecimal`). `AgentRepository.findLowFloatAgents(WalletType walletType, BigDecimal threshold, AgentStatus agentStatus) -> List<LowFloatAgentProjection>`. `TreasuryRequestRepository.findTop10ByStatusOrderByCreatedAtDesc(RequestStatus) -> List<TreasuryRequestEntity>`. `FloatHealth.LOW_THRESHOLD` becomes `public` (was `private`) — this task is its first external caller. `DashboardService.buildActionAlerts()` (package-private).

- [ ] **Step 1: Write the failing tests**

```java
@Test
void buildActionAlertsReportsFullPendingTotalsButCapsRecentRequestsListAtTen() {
    when(treasuryRequestRepository.countByStatus(RequestStatus.PENDING)).thenReturn(15L);
    when(treasuryRequestRepository.sumAmountByStatus(RequestStatus.PENDING)).thenReturn(new BigDecimal("2500000.00"));
    TreasuryRequestEntity request = pendingRequest("45", "40512", "Kariakoo Wakala Point", "500000.00", "CI261116.0830.A11223");
    when(treasuryRequestRepository.findTop10ByStatusOrderByCreatedAtDesc(RequestStatus.PENDING)).thenReturn(List.of(request));
    when(walletRepository.findByUserIdAndWalletType(request.getAgent().getUser().getId(), WalletType.AGENT_FLOAT))
            .thenReturn(Optional.of(WalletEntity.builder().accountNumber("018492019482").build()));
    stubNoLowFloatAgents();

    ActionAlerts result = dashboardService.buildActionAlerts();

    assertThat(result.pendingFloatRequests().count()).isEqualTo(15);
    assertThat(result.pendingFloatRequests().totalRequestedAmount()).isEqualByComparingTo("2500000.00");
    assertThat(result.pendingFloatRequests().recentRequests()).hasSize(1);
    assertThat(result.pendingFloatRequests().recentRequests().get(0).floatAccountNumber()).isEqualTo("018492019482");
}

@Test
void buildActionAlertsExcludesAgentExactlyAtThreshold() {
    stubNoPendingRequests();
    when(agentRepository.findLowFloatAgents(WalletType.AGENT_FLOAT, FloatHealth.LOW_THRESHOLD, AgentStatus.ACTIVE))
            .thenReturn(List.of()); // the repository query itself enforces strict "<"; nothing at/above the threshold is ever returned

    ActionAlerts result = dashboardService.buildActionAlerts();

    assertThat(result.lowFloatAgents().count()).isZero();
    assertThat(result.lowFloatAgents().threshold()).isEqualByComparingTo(FloatHealth.LOW_THRESHOLD);
}

@Test
void buildActionAlertsHandlesNoPendingRequestsAndNoLowFloatAgents() {
    stubNoPendingRequests();
    stubNoLowFloatAgents();

    ActionAlerts result = dashboardService.buildActionAlerts();

    assertThat(result.pendingFloatRequests().count()).isZero();
    assertThat(result.pendingFloatRequests().recentRequests()).isEmpty();
    assertThat(result.lowFloatAgents().agents()).isEmpty();
}
```

Add private test helpers `pendingRequest(...)` (builds a `TreasuryRequestEntity` with a nested `AgentEntity`/`UserEntity`), `stubNoPendingRequests()`, and `stubNoLowFloatAgents()` (stubs the three repository calls each builder method needs to zero/empty).

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn test -Dtest=DashboardServiceTest`
Expected: FAIL (compile error)

- [ ] **Step 3: Widen `FloatHealth.LOW_THRESHOLD` from `private` to `public`** (no behavior change).

- [ ] **Step 4: Create `LowFloatAgentProjection`** with the six getters listed in Interfaces.

- [ ] **Step 5: Add `findLowFloatAgents` to `AgentRepository`**

```java
@Query("""
        SELECT a.id AS agentId, a.agentNumber AS agentCode, a.businessName AS businessName,
               a.phoneNumber AS phoneNumber, w.accountNumber AS floatAccountNumber, w.balance AS currentBalance
        FROM AgentEntity a
        JOIN WalletEntity w ON w.user.id = a.user.id
        WHERE w.walletType = :walletType AND w.balance < :threshold AND a.agentStatus = :agentStatus
        """)
List<LowFloatAgentProjection> findLowFloatAgents(
        @Param("walletType") WalletType walletType,
        @Param("threshold") BigDecimal threshold,
        @Param("agentStatus") AgentStatus agentStatus);
```

- [ ] **Step 6: Add `findTop10ByStatusOrderByCreatedAtDesc(RequestStatus) -> List<TreasuryRequestEntity>` to `TreasuryRequestRepository`** — a plain Spring Data derived query, no `@Query` needed.

- [ ] **Step 7: Create the `PendingFloatRequestItem`, `PendingFloatRequestsSummary`, `LowFloatAgentItem`, `LowFloatAgentsSummary`, `ActionAlerts` records** per the field tables in the spec's "actionAlerts" section.

- [ ] **Step 8: Add `buildActionAlerts()` to `DashboardService`**

Add `TreasuryRequestRepository` and `AgentRepository` as new constructor fields (`WalletRepository` already exists from Task 2). Map each `LowFloatAgentProjection` 1:1 onto a `LowFloatAgentItem`. For each of the top-10 pending requests, resolve its `floatAccountNumber` via `walletRepository.findByUserIdAndWalletType(request.getAgent().getUser().getId(), WalletType.AGENT_FLOAT)`.

- [ ] **Step 9: Run the tests to verify they pass**

Run: `mvn test -Dtest=DashboardServiceTest`
Expected: PASS

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/application/e_wallet/treasury/repository/TreasuryRequestRepository.java src/main/java/com/application/e_wallet/agent/repository/AgentRepository.java src/main/java/com/application/e_wallet/agent/dto/FloatHealth.java src/main/java/com/application/e_wallet/dashboard/service/DashboardService.java src/main/java/com/application/e_wallet/agent/repository/LowFloatAgentProjection.java src/main/java/com/application/e_wallet/dashboard/dto/PendingFloatRequestItem.java src/main/java/com/application/e_wallet/dashboard/dto/PendingFloatRequestsSummary.java src/main/java/com/application/e_wallet/dashboard/dto/LowFloatAgentItem.java src/main/java/com/application/e_wallet/dashboard/dto/LowFloatAgentsSummary.java src/main/java/com/application/e_wallet/dashboard/dto/ActionAlerts.java src/test/java/com/application/e_wallet/dashboard/service/DashboardServiceTest.java
git commit -m "feat: compute dashboard action alerts"
```

---

## Task 5: Network stats section

**Files:**
- Modify: `src/main/java/com/application/e_wallet/agent/repository/AgentRepository.java`
- Modify: `src/main/java/com/application/e_wallet/customer/repository/CustomerRepository.java`
- Modify: `src/main/java/com/application/e_wallet/dashboard/service/DashboardService.java`
- Create: `src/main/java/com/application/e_wallet/dashboard/dto/NetworkStats.java`
- Test: `src/test/java/com/application/e_wallet/dashboard/service/DashboardServiceTest.java`

**Interfaces:**
- Consumes: `TransactionRepository.findByStatusAndCreatedAtBetween` and `DashboardService.DayWindow` (both from Task 3) — reused for `PENDING`/`FAILED`, not re-specified.
- Produces: `AgentRepository.countByAgentStatus(AgentStatus) -> long`. `CustomerRepository.countByStatus(CustomerStatus) -> long`. `NetworkStats(long totalActiveAgents, long totalActiveCustomers, TransactionTypeMetric pendingTransactionsToday, TransactionTypeMetric failedTransactionsToday)`. `DashboardService.buildNetworkStats(DayWindow window)` (package-private).

- [ ] **Step 1: Write the failing tests**

```java
@Test
void buildNetworkStatsCountsActiveAgentsAndCustomersAndSplitsPendingAndFailedTransactions() {
    DashboardService.DayWindow window = new DashboardService.DayWindow(
            OffsetDateTime.parse("2026-11-16T00:00:00+03:00"), OffsetDateTime.parse("2026-11-16T10:30:00+03:00"));

    when(agentRepository.countByAgentStatus(AgentStatus.ACTIVE)).thenReturn(85L);
    when(customerRepository.countByStatus(CustomerStatus.ACTIVE)).thenReturn(1420L);
    when(transactionRepository.findByStatusAndCreatedAtBetween("PENDING", window.start(), window.end()))
            .thenReturn(List.of(transaction("CASH_IN", "PENDING", "30000.00", "2026-11-16T09:00:00+03:00")));
    when(transactionRepository.findByStatusAndCreatedAtBetween("FAILED", window.start(), window.end()))
            .thenReturn(List.of());

    NetworkStats result = dashboardService.buildNetworkStats(window);

    assertThat(result.totalActiveAgents()).isEqualTo(85);
    assertThat(result.totalActiveCustomers()).isEqualTo(1420);
    assertThat(result.pendingTransactionsToday().count()).isEqualTo(1);
    assertThat(result.pendingTransactionsToday().volume()).isEqualByComparingTo("30000.00");
    assertThat(result.failedTransactionsToday().count()).isZero();
    assertThat(result.failedTransactionsToday().volume()).isEqualByComparingTo(BigDecimal.ZERO);
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn test -Dtest=DashboardServiceTest`
Expected: FAIL (compile error)

- [ ] **Step 3: Add `countByAgentStatus(AgentStatus) -> long` to `AgentRepository`** and **`countByStatus(CustomerStatus) -> long` to `CustomerRepository`** — both plain Spring Data derived queries.

- [ ] **Step 4: Create the `NetworkStats` record** with the fields listed in Interfaces.

- [ ] **Step 5: Add `buildNetworkStats(DayWindow window)` to `DashboardService`**

Add `CustomerRepository` as a new constructor field (`AgentRepository`, `TransactionRepository` already exist from Tasks 4 and 3). Reuse the same count+sum-from-list reduction helper Task 3's breakdown used, called once for `PENDING` and once for `FAILED`.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `mvn test -Dtest=DashboardServiceTest`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/application/e_wallet/agent/repository/AgentRepository.java src/main/java/com/application/e_wallet/customer/repository/CustomerRepository.java src/main/java/com/application/e_wallet/dashboard/service/DashboardService.java src/main/java/com/application/e_wallet/dashboard/dto/NetworkStats.java src/test/java/com/application/e_wallet/dashboard/service/DashboardServiceTest.java
git commit -m "feat: compute dashboard network stats"
```

---

## Task 6: Assemble the full overview response

**Files:**
- Modify: `src/main/java/com/application/e_wallet/dashboard/service/DashboardService.java`
- Create: `src/main/java/com/application/e_wallet/dashboard/dto/DashboardOverviewResponse.java`
- Test: `src/test/java/com/application/e_wallet/dashboard/service/DashboardServiceTest.java`

**Interfaces:**
- Consumes: every `buildXxx` method from Tasks 2–5, and `today()` from Task 3.
- Produces: `DashboardOverviewResponse(OffsetDateTime timestamp, String currency, LiquidityCards liquidityCards, TodayTransactionMetrics todayTransactionMetrics, ActionAlerts actionAlerts, NetworkStats networkStats)`. `DashboardService.getOverview() -> DashboardOverviewResponse` (public, `@Transactional(readOnly = true)`) — this is what `DashboardController` (Task 7) calls.

- [ ] **Step 1: Write the failing test**

```java
@Test
void getOverviewAssemblesAllFourSectionsWithFixedCurrencyAndZoneTimestamp() {
    // Stub every repository call each buildXxx method needs (same stubs as their individual tests,
    // combined) so getOverview() runs end-to-end without NPEs.

    DashboardOverviewResponse result = dashboardService.getOverview();

    assertThat(result.currency()).isEqualTo("TZS");
    assertThat(result.timestamp().getOffset()).isEqualTo(ZoneOffset.ofHours(3));
    assertThat(result.liquidityCards()).isNotNull();
    assertThat(result.todayTransactionMetrics()).isNotNull();
    assertThat(result.actionAlerts()).isNotNull();
    assertThat(result.networkStats()).isNotNull();
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=DashboardServiceTest`
Expected: FAIL (compile error — `getOverview`, `DashboardOverviewResponse` don't exist yet)

- [ ] **Step 3: Create the `DashboardOverviewResponse` record** with the fields listed in Interfaces.

- [ ] **Step 4: Add `getOverview()` to `DashboardService`**

```java
@Transactional(readOnly = true)
public DashboardOverviewResponse getOverview() {
    DayWindow window = today();
    return new DashboardOverviewResponse(
            OffsetDateTime.now(DASHBOARD_ZONE), "TZS",
            buildLiquidityCards(), buildTodayTransactionMetrics(window), buildActionAlerts(), buildNetworkStats(window));
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn test -Dtest=DashboardServiceTest`
Expected: PASS

- [ ] **Step 6: Run the full test suite to confirm nothing else broke**

Run: `mvn test`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/application/e_wallet/dashboard/service/DashboardService.java src/main/java/com/application/e_wallet/dashboard/dto/DashboardOverviewResponse.java src/test/java/com/application/e_wallet/dashboard/service/DashboardServiceTest.java
git commit -m "feat: assemble the dashboard overview response"
```

---

## Task 7: Controller and security wiring

**Files:**
- Create: `src/main/java/com/application/e_wallet/dashboard/controller/DashboardController.java`

**Interfaces:**
- Consumes: `DashboardService.getOverview()` (Task 6), `ApiResponse<T>` (`com.application.e_wallet.common.dto.ApiResponse`, exists).
- Produces: `GET /api/v1/dashboard/overview`, gated by `DASHBOARD_VIEW` (Task 1).

This codebase has no controller-level tests anywhere (every existing `*ControllerTest` simply doesn't exist — controllers are thin and verified by running the app). This task follows that convention: no new test file, verified by a manual run.

- [ ] **Step 1: Create `DashboardController`**

```java
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('DASHBOARD_VIEW')")
    public ResponseEntity<ApiResponse<DashboardOverviewResponse>> getOverview() { ... }
}
```

Follow `WalletController.getMyWallet`'s exact `ApiResponse.<T>builder()...` wrapping pattern (`timestamp(Instant.now())`, `status(HttpStatus.OK.value())`, a message string, `data(response)`), returning `ResponseEntity.ok(body)`.

- [ ] **Step 2: Build the project**

Run: `mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Start the app and call the endpoint as an ADMIN user**

Run: `mvn spring-boot:run` (in one terminal), then, with a valid ADMIN JWT:
`curl -H "Authorization: Bearer <token>" http://localhost:8080/api/v1/dashboard/overview`
Expected: `200 OK` with a JSON body matching the shape in the spec (liquidity cards, today's metrics, action alerts, network stats — real numbers if any data exists, zeros/empty lists otherwise per Review Focus item 3).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/application/e_wallet/dashboard/controller/DashboardController.java
git commit -m "feat: add GET /api/v1/dashboard/overview endpoint"
```

---

## Self-Review Notes

- **Spec coverage:** every field in the spec's response shape maps to exactly one task (liquidityCards → Task 2, todayTransactionMetrics → Task 3, actionAlerts → Task 4, networkStats → Task 5, assembly → Task 6, endpoint/permission → Tasks 1 & 7). The spec's eight "Decisions made" are each reflected in Global Constraints or a specific task step (payment channel dropped: no such field anywhere in Task 4's DTOs; polling-only: no new infra added anywhere; COMPLETED-only + renamed buckets: Task 3; DASHBOARD_VIEW + dashboard module: Tasks 1 & 7; transaction-metrics zero-state: Global Constraints, exercised by Task 3/5's empty-list tests).
- **Type consistency:** `DayWindow`, `DASHBOARD_ZONE`, and `today()` are defined once (Task 3) and only ever reused (Tasks 5, 6), never redefined. `TransactionTypeMetric` is defined once (Task 3) and reused for both the breakdown buckets and `networkStats`'s pending/failed fields (Task 5), never duplicated.
- **Review Focus:** all five items have an owning task and test — BigDecimal scale (Task 2's third test), threshold strictness (Task 4's second test), empty states (Tasks 3, 4, 5's "no X" tests), hour-bucketing across the UTC/local boundary (Task 3's second test), and the recent-requests cap vs. full totals (Task 4's first test).
