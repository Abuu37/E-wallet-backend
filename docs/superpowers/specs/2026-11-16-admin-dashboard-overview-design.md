# Admin Live Dashboard Overview Endpoint — Design

## Intent and why

The admin needs one screen that shows whether the business is healthy right now: how much money sits where (escrow, company, agents, customers), what's moving today, what needs action (pending float requests, agents running low), and basic network health. The sample response given in discussion is the target shape, with two adjustments agreed during discussion (payment channel dropped, transaction breakdown renamed — see below).

This is read by an admin dashboard that stays open and refreshes itself — "live" in the sense of frequently re-fetched, not push-based.

## Decisions made in discussion (yours, recorded so they aren't re-litigated)

1. **`paymentChannel` is dropped from the response for now.** It doesn't exist anywhere in the schema (`TreasuryRequestEntity` has no such column) and isn't being added as part of this work. Revisit later as its own change. `floatAccountNumber` stays — it's derived from the requesting agent's `AGENT_FLOAT` wallet, not stored on the request.
2. **Live = client polling, not WebSocket/SSE.** This is an admin-only screen (one or a handful of viewers), the stack has no event bus or socket infrastructure today, and the actual scaling lever is query cost, not transport (see decision 5). The frontend re-fetches this endpoint on an interval; the backend always computes fresh numbers, no caching layer.
3. **`todayTransactionMetrics` counts `COMPLETED` transactions only.** Pending/failed transactions don't count as real volume. Failed transactions still surface, but only as the `failedTransactionsToday` count in `networkStats`, not blended into volume.
4. **The breakdown buckets are renamed to match the real `TransactionType` enum, not the sample's deposits/withdrawals/transfers.** `TransactionType` is `CASH_IN, CASH_OUT, INTERNAL_TRANSFER, COMMISSION_TRANSFER` — the response breakdown has one entry per value: `cashIn`, `cashOut`, `internalTransfer`, `commissionTransfer`. This also means the four bucket volumes/counts always sum to the total (the sample's three-bucket version wouldn't, once commission transfers exist).
5. **Scaling is handled with indexes, not by changing the polling model.** Confirmed during discussion: none of `transactions.created_at`, `wallets.wallet_type`, or `treasury_requests.status` are indexed today, even though this endpoint's queries filter/group on exactly those columns. Add the indexes (migration, below); keep plain polling.
6. **New `DASHBOARD_VIEW` permission**, not a reuse of `COMPANY_WALLET_VIEW`.
7. **New `dashboard` module** (controller/service/dto), not bolted onto `treasury`. This endpoint aggregates `wallet`, `ledger`, `treasury`, `agent`, `customer`, and `transaction` data — no existing module owns it.
8. **`todayTransactionMetrics` and `failedTransactionsToday` query `TransactionRepository`/`TransactionEntity` as originally specified, and that's correct even though it returns all-zero today.** Builder-check flagged that `transactions` (V39) is a dead table — nothing in the codebase writes a `TransactionEntity` row yet, since customer-facing cash-in/cash-out/transfer hasn't been built (only the ledger-backed treasury mint/topup/withdraw flows exist). Confirmed: that's expected present-state, not a gap to fix here. This feature does **not** add a write path to `transactions`, and does **not** redirect these sections to source from the ledger instead (the ledger's `TransactionType` values and lack of a `status` field don't map onto this response's COMPLETED/FAILED/breakdown shape anyway). The metrics are wired correctly against the real (currently empty) table and will start reporting real numbers the moment a cash-in/cash-out feature starts writing to it — no change needed here when that lands.

## Response shape

Same top-level shape as the sample, with the two changes above. Field-by-field source:

### `liquidityCards`

Every balance here is `WalletRepository.sumBalanceByWalletType(WalletType)`, which already exists and already does exactly this — no new wallet-side query needed.

| Field | Source |
|---|---|
| `escrowBankBalance` | `sumBalanceByWalletType(ESCROW_BANK)` |
| `companyMasterBalance` | `sumBalanceByWalletType(COMPANY_MASTER_ACCOUNT)` |
| `companyRevenueBalance` | `sumBalanceByWalletType(REVENUE)` |
| `totalAgentFloatBalance` | `sumBalanceByWalletType(AGENT_FLOAT)` |
| `totalAgentCommissionBalance` | `sumBalanceByWalletType(AGENT_COMMISSION)` |
| `totalCustomerWalletBalance` | `sumBalanceByWalletType(CUSTOMER_MAIN)` |
| `isLedgerBalanced` | New: sum all `DEBIT` lines vs. sum all `CREDIT` lines across `transaction_journal_line`, compare. `LedgerService.postJournal` already rejects unbalanced journals at write time, so this is a sanity/trust indicator (confirms nothing bypassed the ledger), not a live risk check. It reads the whole table — acceptable now; flagged under non-goals if it ever needs bounding. |

### `todayTransactionMetrics`

All of this is new — `TransactionRepository` currently has no query methods at all.

- `totalVolume` / `totalCount`: sum/count of `COMPLETED` transactions where `createdAt` falls within "today".
- `breakdown.{cashIn,cashOut,internalTransfer,commissionTransfer}`: one `{count, volume}` per `TransactionType`, same `COMPLETED` + "today" filter, grouped by `transactionType`.
- `hourlyTrend`: one `{hour, volume, count}` entry per hour from `00:00` through the current hour (not the full 24 — hours that haven't happened yet don't appear), `COMPLETED` + "today", grouped by hour of `createdAt`.

"Today" and hour boundaries use a fixed app timezone rather than the server process's default zone (my call — nothing in this codebase pins a zone today, and leaving it to the JVM default would make "today" shift if the deployment environment's zone ever changes). Use `Africa/Dar_es_Salaam` (UTC+3, matches the sample's `+03:00` timestamps).

Note: `TransactionEntity.transactionType` and `.status` are plain `String` columns, not `@Enumerated` against the `TransactionType`/`TransactionStatus` enums (unlike `WalletEntity.walletType`, which is enumerated). This is already how the entity exists — not something this feature changes, just something the new queries need to account for (compare against `TransactionType.CASH_IN.name()` etc., or whatever string values are actually persisted).

### `actionAlerts`

**`pendingFloatRequests`**: `count` and `totalRequestedAmount` are exactly `TreasuryRequestRepository.countByStatus(PENDING)` / `.sumAmountByStatus(PENDING)` — both already exist (`TreasuryMetrics` already uses them). `recentRequests` is new: the most recent pending requests (my call: top 10, newest first — mirrors the "top 5" pattern already used for `AgentDetailResponse`'s recent transactions), each with `requestId`, `agentCode` (`agent.agentNumber`), `businessName`, `floatAccountNumber` (the agent's `AGENT_FLOAT` wallet account number — same lookup `TreasuryRequestService.toResponse` already does), `amount`, `externalReference`, `requestedAt` (`createdAt`). No `paymentChannel` (decision 1).

**`lowFloatAgents`**: `threshold` is the existing `FloatHealth.LOW_THRESHOLD` (100000) — reused, not redefined, so the dashboard's definition of "low" never drifts from the agent detail screen's. `agents` is every `ACTIVE` agent whose `AGENT_FLOAT` wallet balance is below that threshold, each with `agentId`, `agentCode`, `businessName`, `phoneNumber`, `floatAccountNumber`, `currentBalance`.

### `networkStats`

- `totalActiveAgents`: count of `AgentEntity` where `agentStatus = ACTIVE` (new repository method).
- `totalActiveCustomers`: count of `CustomerEntity` where `status = ACTIVE` (new repository method).
- `pendingTransactionsToday`: `{count, volume}` — same shape as a `todayTransactionMetrics` breakdown bucket, but filtered `status = PENDING` and "today" (same day-boundary helper). Added per discussion: a bare count wasn't enough for the admin to see how much money is actually sitting in pending state, not just how many transactions.
- `failedTransactionsToday`: `{count, volume}` — same shape, `status = FAILED` and "today". Originally scoped as a bare count; changed to `{count, volume}` for the same reason as `pendingTransactionsToday`, and so the two stay visually/structurally consistent with each other.

## Data model changes

**Migration — indexes** (new Flyway file, next available version):

```sql
CREATE INDEX idx_transactions_status_created_at ON transactions (status, created_at);
CREATE INDEX idx_wallets_wallet_type ON wallets (wallet_type);
CREATE INDEX idx_treasury_requests_status ON treasury_requests (status);
```

The transactions index is composite (`status`, `created_at` together) because every query this endpoint runs against that table filters by `status = COMPLETED` (or `FAILED`) and a `created_at` range/group in the same query.

**Migration — permission** (separate file, since it depends on `modules` existing from V32): insert `DASHBOARD_VIEW` into `permissions`, `module_reference` pointing at the existing `FINANCE` module (my call — "Reconciliation, ledger and financial reporting" fits an aggregate financial/ops dashboard better than adding a new module row for one permission), and grant it to the `ADMIN` role in `role_permissions`, following the exact pattern V6/V32 already use.

## Module layout

New `com.application.e_wallet.dashboard` package:

- `DashboardController` — `GET /api/v1/dashboard/overview`, `@PreAuthorize("hasAuthority('DASHBOARD_VIEW')")`, wrapped in the existing `ApiResponse<T>` pattern every other controller uses.
- `DashboardService` — one read-only `@Transactional` method that assembles the response from the repositories below. No caching layer (decision 2).
- `dto` — `DashboardOverviewResponse` and its nested records (`LiquidityCards`, `TodayTransactionMetrics`, `TransactionTypeMetric`, `HourlyTrendPoint`, `ActionAlerts`, `PendingFloatRequestsSummary`, `PendingFloatRequestItem`, `LowFloatAgentsSummary`, `LowFloatAgentItem`, `NetworkStats`) — exact record shapes left to the builder, matching the field tables above. `TransactionTypeMetric`'s `{count, volume}` shape is reused for `networkStats.pendingTransactionsToday`/`failedTransactionsToday` rather than defining a second identical record.

New repository methods needed (exact JPQL/native left to the builder):

- `TransactionRepository`: today's `COMPLETED` sum+count; today's `COMPLETED` grouped by `transactionType`; today's hourly `COMPLETED` grouped by hour; today's `PENDING` sum+count; today's `FAILED` sum+count. (The last two share the same "sum+count for a given status, today" shape — one parameterized query covers both.)
- `TransactionJournalLineRepository`: sum of amount by `EntryType` (for `isLedgerBalanced`).
- `AgentRepository`: count by `agentStatus`; a join query returning `ACTIVE` agents whose `AGENT_FLOAT` wallet balance is below a threshold (for `lowFloatAgents` — avoid N+1 by joining rather than looping `findByUserId` per wallet).
- `CustomerRepository`: count by `status`.
- `TreasuryRequestRepository`: top-N by status ordered by `createdAt` desc (for `recentRequests` — `findAllByOrderByCreatedAtDesc` already exists but isn't status-filtered or capped).

## Non-goals

- No push transport (WebSocket/SSE) — decision 2.
- No `paymentChannel` field or schema change for it — decision 1.
- No write path for `TransactionEntity` (no cash-in/cash-out/transfer feature) — decision 8. That's separate, future work; this dashboard will report real `todayTransactionMetrics`/`failedTransactionsToday` numbers automatically once it exists, with no change to this feature's code.
- No fix to `TransactionEntity`'s `String`-typed `transactionType`/`status` columns — noted as an existing inconsistency, out of scope here.
- No bounding/windowing of the `isLedgerBalanced` full-history check — acceptable at current and foreseeable data volume; revisit only if `transaction_journal_line` grows large enough for a full-table sum to matter.
- No configurable low-float threshold — stays the existing hardcoded `FloatHealth.LOW_THRESHOLD`, not promoted to app config, since that would be a change to existing agent-detail behavior this feature doesn't need to make.

## What's left to the builder

- Exact JPQL/query shape for each new repository method listed above.
- Exact DTO record field ordering/naming (shapes are specified above; Java record boilerplate isn't).
- Migration version numbers (next available after V41).
- Whether `recentRequests`/any other list needs pagination later — today it's a fixed top-10, matching existing "recent N" precedent elsewhere in the codebase.
