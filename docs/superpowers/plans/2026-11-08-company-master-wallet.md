# Company Master Wallet Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a singleton company master wallet (trust/escrow float pool) with mint, allocate, and withdraw operations, each recorded as a balanced double-entry journal.

**Architecture:** Two new top-level packages. `ledger` is neutral shared bookkeeping (`TransactionJournalEntity`/`TransactionJournalLineEntity`, `LedgerService.postJournal(...)`) that any domain can post to. `company_wallet` owns only the singleton `CompanyMasterWalletEntity` and its `CompanyMasterWalletService`, which updates its own balances and calls `LedgerService` for the ledger side — it is a client of the ledger, not its owner. Agent float balances reuse the existing `FinancialAccountEntity` (`AGENT_FLOAT`) rather than a new table.

**Tech Stack:** Spring Boot 4.2.0-SNAPSHOT, Java 21, Spring Data JPA, PostgreSQL, Flyway, Lombok, JUnit 5 + Mockito (unit tests; no DB-backed test infra exists in this repo today — see Task 1 verification).

**Spec:** `docs/superpowers/specs/2026-11-08-company-master-wallet-design.md`

## Global Constraints

- Entity IDs: `UUID` via `@GeneratedValue(strategy = GenerationType.UUID)` (matches `WalletEntity`, `FinancialAccountEntity`, `UserEntity` — not `AgentEntity`'s outlier `GenerationType.AUTO`).
- Timestamps: `OffsetDateTime`, set in `@PrePersist`/`@PreUpdate` (matches `WalletEntity`, `FinancialAccountEntity`, `AgentEntity`).
- Entity annotations: `@Entity @Table(name = "...") @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor`.
- Money fields: `BigDecimal`, `precision = 19, scale = 2` (matches `financial_accounts.current_balance`).
- Currency default: `"TZS"`, `length = 3`.
- New multi-word package name is snake_case: `company_wallet` (matches `financial_account`), not camelCase.
- Controller responses wrapped in `ApiResponse<T>` (`timestamp`, `status`, `message`, `data`); endpoint security via `@PreAuthorize("hasAuthority('X')")`.
- User-facing validation failures (insufficient balance, inactive account, bad amount, agent not found): `BadRequestException` (maps to HTTP 400 via existing `GlobalExceptionHandler`).
- Internal invariant violations that should never happen if callers are correct (ledger imbalance, missing singleton row, missing seeded role): `IllegalStateException` — same precedent as `WalletService`/`AgentService` (unhandled, surfaces as 500; this repo has no handler for it and this plan does not add one).
- `LedgerService.postJournal(...)` must use default `REQUIRED` transaction propagation (join the caller's transaction), never `REQUIRES_NEW` — this is what makes balance updates and journal posts roll back together (see spec's "Invariant" note under `CompanyMasterWalletService`).
- `company_master_wallet` singleton is enforced at the DB level via a unique `singleton_key` column, not application discipline alone (matches `chk_wallets_exactly_one_owner` philosophy).
- The pre-existing untracked file `src/main/java/com/application/e_wallet/companyWallet/entity/companyMasterWallet.java` is confirmed disposable scratch work (never wired anywhere) — delete it; it is not a starting point.

## Review Focus

- **Boundary amount equals available balance** — allocating/withdrawing exactly the full available amount must succeed and leave a zero balance, not be rejected as "insufficient" by an off-by-one in the comparison. Tested in Task 4.
- **Agent account not `ACTIVE`** — a `SUSPENDED` (or `CLOSED`) agent financial account must block both allocate and withdraw, not just one of them. Tested in Task 4.
- **Non-positive amount reaches the service layer directly** — `@Positive` on the DTO is not the only guard; the service itself must reject zero/negative amounts independent of controller validation (defense in depth for any future direct caller). Tested in Task 4.
- **Unbalanced journal lines** — if a caller ever constructs mismatched debit/credit lines, `LedgerService` must refuse to persist anything rather than silently writing an unbalanced journal. Tested in Task 2.
- **Lock bypass across the three write paths** — mint, allocate, and withdraw must all load the singleton wallet through the same pessimistic-locking repository method, not a plain unlocked read, or concurrent admin actions can race. Tested in Task 4 (each success test asserts the locked method was the one invoked).

---

## Task 1: Migration — schema, singleton seed, permission grants

**Files:**
- Create: `src/main/resources/db/migration/V17__create_company_master_wallet_and_ledger.sql`

**Interfaces:**
- Produces: tables `company_master_wallet`, `transaction_journal`, `transaction_journal_line` with the exact columns below, which every later task's entities map onto 1:1.

- [ ] **Step 1: Write the migration**

```sql
-- V17: Company master wallet + ledger (journal) tables, and permission grants.

CREATE TABLE company_master_wallet (
    id UUID PRIMARY KEY,
    singleton_key SMALLINT NOT NULL DEFAULT 1,
    wallet_name VARCHAR(100) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    unallocated_float NUMERIC(19,2) NOT NULL DEFAULT 0,
    total_distributed_float NUMERIC(19,2) NOT NULL DEFAULT 0,
    escrow_bank_account_no VARCHAR(50),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT uk_company_master_wallet_singleton UNIQUE (singleton_key)
);

INSERT INTO company_master_wallet (
    id, singleton_key, wallet_name, currency,
    unallocated_float, total_distributed_float, escrow_bank_account_no,
    created_at, updated_at
)
VALUES (
    gen_random_uuid(), 1, 'Company Trust Account', 'TZS',
    0, 0, NULL,
    NOW(), NOW()
);

CREATE TABLE transaction_journal (
    id UUID PRIMARY KEY,
    reference_number VARCHAR(60) NOT NULL,
    transaction_type VARCHAR(30) NOT NULL,
    description VARCHAR(255) NOT NULL,
    created_by UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT uk_transaction_journal_reference_number UNIQUE (reference_number),
    CONSTRAINT fk_transaction_journal_created_by FOREIGN KEY (created_by) REFERENCES users (id)
);

CREATE TABLE transaction_journal_line (
    id UUID PRIMARY KEY,
    journal_id UUID NOT NULL,
    ledger_account_type VARCHAR(30) NOT NULL,
    account_ref_id UUID NOT NULL,
    entry_type VARCHAR(10) NOT NULL,
    amount NUMERIC(19,2) NOT NULL,

    CONSTRAINT fk_transaction_journal_line_journal FOREIGN KEY (journal_id) REFERENCES transaction_journal (id)
);

-- Grant the previously-orphaned view permissions (seeded in V6, never assigned) to ADMIN.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'ADMIN'
AND p.name IN ('COMPANY_WALLET_VIEW', 'LEDGER_VIEW');
```

- [ ] **Step 2: Delete the disposable scratch file and its empty package**

```bash
git rm src/main/java/com/application/e_wallet/companyWallet/entity/companyMasterWallet.java
```
Remove the now-empty `companyWallet` directory tree as well.

- [ ] **Step 3: Verify the migration applies cleanly**

Run: `mvn test -Dtest=EWalletApplicationTests`
Expected: PASS (the existing context-load test runs Flyway against the configured datasource on startup; a migration syntax or constraint error fails this test). This repo has no dedicated migration-test infrastructure beyond this — do not add one.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/db/migration/V17__create_company_master_wallet_and_ledger.sql
git rm -r src/main/java/com/application/e_wallet/companyWallet
git commit -m "feat: add company master wallet and ledger schema"
```

---

## Task 2: Ledger domain — entities, enums, repository, `LedgerService`

**Files:**
- Create: `src/main/java/com/application/e_wallet/ledger/entity/TransactionType.java`
- Create: `src/main/java/com/application/e_wallet/ledger/entity/LedgerAccountType.java`
- Create: `src/main/java/com/application/e_wallet/ledger/entity/EntryType.java`
- Create: `src/main/java/com/application/e_wallet/ledger/entity/TransactionJournalEntity.java`
- Create: `src/main/java/com/application/e_wallet/ledger/entity/TransactionJournalLineEntity.java`
- Create: `src/main/java/com/application/e_wallet/ledger/repository/TransactionJournalRepository.java`
- Create: `src/main/java/com/application/e_wallet/ledger/service/JournalLineRequest.java`
- Create: `src/main/java/com/application/e_wallet/ledger/service/LedgerService.java`
- Test: `src/test/java/com/application/e_wallet/ledger/service/LedgerServiceTest.java`

**Interfaces:**
- Consumes: `UserEntity` (`com.application.e_wallet.user.entity.UserEntity`) for `createdBy`.
- Produces (used by Task 4):
  - `enum TransactionType { MINT_FLOAT, ALLOCATE_FLOAT, AGENT_WITHDRAW }`
  - `enum LedgerAccountType { ESCROW_BANK, COMPANY_UNALLOCATED, COMPANY_DISTRIBUTED, AGENT_FLOAT, CUSTOMER_WALLET }`
  - `enum EntryType { DEBIT, CREDIT }`
  - `record JournalLineRequest(LedgerAccountType ledgerAccountType, UUID accountRefId, EntryType entryType, BigDecimal amount)`
  - `LedgerService.postJournal(TransactionType transactionType, String description, UserEntity createdBy, List<JournalLineRequest> lines) -> TransactionJournalEntity`
  - `TransactionJournalEntity`: getters `getId()`, `getReferenceNumber()`, `getTransactionType()`, `getDescription()`, `getCreatedBy()`, `getCreatedAt()`, `getLines()` (returns `List<TransactionJournalLineEntity>`)
  - `TransactionJournalLineEntity`: getters `getLedgerAccountType()`, `getAccountRefId()`, `getEntryType()`, `getAmount()`

- [ ] **Step 1: Write the three enums**

`TransactionType`, `LedgerAccountType`, `EntryType` as plain enums (no `Entity` suffix, matching `WalletStatus`/`FinancialAccountStatus` style) with the exact values listed in Interfaces above, each in its own file under `ledger/entity/`.

- [ ] **Step 2: Write `TransactionJournalEntity` and `TransactionJournalLineEntity`**

`TransactionJournalEntity` — table `transaction_journal`, fields: `id` (UUID), `referenceNumber` (String, unique), `transactionType` (`TransactionType`, `@Enumerated(EnumType.STRING)`), `description` (String), `createdBy` (`UserEntity`, `@ManyToOne(fetch = FetchType.LAZY, optional = false)` `@JoinColumn(name = "created_by", nullable = false)`), `createdAt` (OffsetDateTime), `lines` (`@OneToMany(mappedBy = "journal", cascade = CascadeType.ALL, orphanRemoval = true)` `@Builder.Default private List<TransactionJournalLineEntity> lines = new ArrayList<>();`). `@PrePersist` sets `createdAt = OffsetDateTime.now()` only — journals are append-only, there is no `@PreUpdate`/`updatedAt`.

`TransactionJournalLineEntity` — table `transaction_journal_line`, fields: `id` (UUID), `journal` (`TransactionJournalEntity`, `@ManyToOne(fetch = FetchType.LAZY, optional = false)` `@JoinColumn(name = "journal_id", nullable = false)`), `ledgerAccountType` (`LedgerAccountType`, `@Enumerated(EnumType.STRING)`), `accountRefId` (UUID), `entryType` (`EntryType`, `@Enumerated(EnumType.STRING)`), `amount` (BigDecimal, `precision = 19, scale = 2`). No timestamps.

- [ ] **Step 3: Write `TransactionJournalRepository`**

```java
public interface TransactionJournalRepository extends JpaRepository<TransactionJournalEntity, UUID> {
    boolean existsByReferenceNumber(String referenceNumber);
    List<TransactionJournalEntity> findAllByOrderByCreatedAtDesc();
}
```

- [ ] **Step 4: Write the failing tests for `LedgerService`**

```java
@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    @Mock
    private TransactionJournalRepository transactionJournalRepository;

    @InjectMocks
    private LedgerService ledgerService;

    @Test
    void postJournalPersistsBalancedJournalWithLines() {
        UserEntity admin = UserEntity.builder().id(UUID.randomUUID()).build();
        List<JournalLineRequest> lines = List.of(
                new JournalLineRequest(LedgerAccountType.ESCROW_BANK, UUID.randomUUID(), EntryType.DEBIT, new BigDecimal("100.00")),
                new JournalLineRequest(LedgerAccountType.COMPANY_UNALLOCATED, UUID.randomUUID(), EntryType.CREDIT, new BigDecimal("100.00"))
        );
        when(transactionJournalRepository.existsByReferenceNumber(any())).thenReturn(false);
        when(transactionJournalRepository.save(any(TransactionJournalEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TransactionJournalEntity result = ledgerService.postJournal(
                TransactionType.MINT_FLOAT, "test mint", admin, lines);

        assertThat(result.getLines()).hasSize(2);
        assertThat(result.getTransactionType()).isEqualTo(TransactionType.MINT_FLOAT);
        assertThat(result.getCreatedBy()).isEqualTo(admin);
    }

    @Test
    void postJournalRejectsUnbalancedLines() {
        UserEntity admin = UserEntity.builder().id(UUID.randomUUID()).build();
        List<JournalLineRequest> lines = List.of(
                new JournalLineRequest(LedgerAccountType.ESCROW_BANK, UUID.randomUUID(), EntryType.DEBIT, new BigDecimal("100.00")),
                new JournalLineRequest(LedgerAccountType.COMPANY_UNALLOCATED, UUID.randomUUID(), EntryType.CREDIT, new BigDecimal("50.00"))
        );

        assertThatThrownBy(() -> ledgerService.postJournal(TransactionType.MINT_FLOAT, "bad", admin, lines))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(transactionJournalRepository);
    }

    @Test
    void postJournalRetriesReferenceNumberOnCollision() {
        UserEntity admin = UserEntity.builder().id(UUID.randomUUID()).build();
        List<JournalLineRequest> lines = List.of(
                new JournalLineRequest(LedgerAccountType.ESCROW_BANK, UUID.randomUUID(), EntryType.DEBIT, new BigDecimal("10.00")),
                new JournalLineRequest(LedgerAccountType.COMPANY_UNALLOCATED, UUID.randomUUID(), EntryType.CREDIT, new BigDecimal("10.00"))
        );
        when(transactionJournalRepository.existsByReferenceNumber(any())).thenReturn(true, false);
        when(transactionJournalRepository.save(any(TransactionJournalEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ledgerService.postJournal(TransactionType.MINT_FLOAT, "retry", admin, lines);

        verify(transactionJournalRepository, times(2)).existsByReferenceNumber(any());
    }
}
```

- [ ] **Step 5: Run tests to verify they fail**

Run: `mvn test -Dtest=LedgerServiceTest`
Expected: FAIL (compilation error — `LedgerService` does not exist yet)

- [ ] **Step 6: Implement `LedgerService`**

```java
@Service
@RequiredArgsConstructor
public class LedgerService {

    private final TransactionJournalRepository transactionJournalRepository;

    @Transactional
    public TransactionJournalEntity postJournal(
            TransactionType transactionType,
            String description,
            UserEntity createdBy,
            List<JournalLineRequest> lines
    ) {
        // sum DEBIT vs CREDIT amounts; if not equal, throw new IllegalStateException(...)
        // before touching the repository at all.
        // then build TransactionJournalEntity + TransactionJournalLineEntity list,
        // wire journal back-reference on each line, and save(journal) (cascades to lines).
    }

    private String generateReferenceNumber(TransactionType transactionType) {
        // retry loop: "<TYPE>-<yyyyMMdd>-<8 uppercase hex chars from a random UUID>",
        // re-roll while transactionJournalRepository.existsByReferenceNumber(candidate) is true
        // (same do-while-on-collision pattern as AgentService.generateAgentCode()).
    }
}
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `mvn test -Dtest=LedgerServiceTest`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/application/e_wallet/ledger src/test/java/com/application/e_wallet/ledger
git commit -m "feat: add ledger domain with balanced double-entry journal posting"
```

---

## Task 3: `company_wallet` entity, repository, and DTOs

**Files:**
- Create: `src/main/java/com/application/e_wallet/company_wallet/entity/CompanyMasterWalletEntity.java`
- Create: `src/main/java/com/application/e_wallet/company_wallet/repository/CompanyMasterWalletRepository.java`
- Create: `src/main/java/com/application/e_wallet/company_wallet/dto/MintFloatRequest.java`
- Create: `src/main/java/com/application/e_wallet/company_wallet/dto/AllocateFloatRequest.java`
- Create: `src/main/java/com/application/e_wallet/company_wallet/dto/AgentWithdrawRequest.java`
- Create: `src/main/java/com/application/e_wallet/company_wallet/dto/CompanyMasterWalletResponse.java`
- Create: `src/main/java/com/application/e_wallet/company_wallet/dto/TransactionJournalResponse.java`
- Create: `src/main/java/com/application/e_wallet/company_wallet/dto/JournalLineResponse.java`
- Modify: `src/main/java/com/application/e_wallet/financial_account/repository/FinancialAccountRepository.java`

**Interfaces:**
- Consumes: Task 1's `company_master_wallet` table.
- Produces (used by Task 4):
  - `CompanyMasterWalletEntity` getters/setters: `getId()`, `getSingletonKey()`, `getWalletName()`, `getCurrency()`, `getUnallocatedFloat()`/`setUnallocatedFloat(BigDecimal)`, `getTotalDistributedFloat()`/`setTotalDistributedFloat(BigDecimal)`, `getEscrowBankAccountNo()`
  - `CompanyMasterWalletRepository.findSingletonForUpdate() -> Optional<CompanyMasterWalletEntity>` (locked), `.findSingleton() -> Optional<CompanyMasterWalletEntity>` (unlocked read)
  - `FinancialAccountRepository.findByWallet_Agent_Id(UUID agentId) -> Optional<FinancialAccountEntity>`
  - `record MintFloatRequest(BigDecimal amount, String description)`
  - `record AllocateFloatRequest(UUID agentId, BigDecimal amount, String description)`
  - `record AgentWithdrawRequest(UUID agentId, BigDecimal amount, String description)`
  - `record CompanyMasterWalletResponse(UUID walletId, String walletName, String currency, BigDecimal unallocatedFloat, BigDecimal totalDistributedFloat, String escrowBankAccountNo)`
  - `record JournalLineResponse(String ledgerAccountType, UUID accountRefId, String entryType, BigDecimal amount)`
  - `record TransactionJournalResponse(UUID journalId, String referenceNumber, String transactionType, String description, UUID createdByUserId, OffsetDateTime createdAt, List<JournalLineResponse> lines)`

- [ ] **Step 1: Write `CompanyMasterWalletEntity`**

Table `company_master_wallet`. Fields: `id` (UUID), `singletonKey` (`Short`, `@Column(name = "singleton_key", nullable = false, unique = true)`), `walletName` (String), `currency` (String, length 3), `unallocatedFloat` (BigDecimal, precision 19 scale 2), `totalDistributedFloat` (BigDecimal, precision 19 scale 2), `escrowBankAccountNo` (String, nullable — no setup endpoint exists yet to populate this; this is the plan's deliberate "what's left to the builder" item from the spec, resolved as: leave it settable only via direct DB access until a future feature adds one), `createdAt`/`updatedAt` (OffsetDateTime). `@PrePersist` defaults `singletonKey = 1` if null, `unallocatedFloat`/`totalDistributedFloat` to `BigDecimal.ZERO` if null, `currency` to `"TZS"` if null, and sets both timestamps (same shape as `WalletEntity.onCreate()`). `@PreUpdate` sets `updatedAt`.

- [ ] **Step 2: Write `CompanyMasterWalletRepository`**

```java
public interface CompanyMasterWalletRepository extends JpaRepository<CompanyMasterWalletEntity, UUID> {

    @Query("SELECT c FROM CompanyMasterWalletEntity c WHERE c.singletonKey = 1")
    Optional<CompanyMasterWalletEntity> findSingleton();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM CompanyMasterWalletEntity c WHERE c.singletonKey = 1")
    Optional<CompanyMasterWalletEntity> findSingletonForUpdate();
}
```

- [ ] **Step 3: Add `findByWallet_Agent_Id` to `FinancialAccountRepository`**

Add `Optional<FinancialAccountEntity> findByWallet_Agent_Id(UUID agentId);` to the existing interface (traverses `FinancialAccountEntity.wallet` → `WalletEntity.agent` → `AgentEntity.id`).

- [ ] **Step 4: Write the six DTO records**

Exactly the record shapes listed in Interfaces above. `MintFloatRequest.amount`/`AllocateFloatRequest.amount`/`AgentWithdrawRequest.amount` each carry `@NotNull @Positive`; `description` fields carry `@NotBlank`; `agentId` fields carry `@NotNull` (same annotation style as `AgentCreationRequest`).

- [ ] **Step 5: Verify the project compiles**

Run: `mvn -q compile`
Expected: BUILD SUCCESS. (No dedicated unit test for this task — this repo has no standalone test for any other plain entity/DTO either, e.g. `WalletEntity`, `AgentCreationRequest`; Task 4's tests exercise `CompanyMasterWalletEntity` and the repository interfaces indirectly through mocks.)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/application/e_wallet/company_wallet/entity src/main/java/com/application/e_wallet/company_wallet/repository src/main/java/com/application/e_wallet/company_wallet/dto src/main/java/com/application/e_wallet/financial_account/repository/FinancialAccountRepository.java
git commit -m "feat: add company master wallet entity, repository, and request/response DTOs"
```

---

## Task 4: `CompanyMasterWalletService` — mint, allocate, withdraw

**Files:**
- Create: `src/main/java/com/application/e_wallet/company_wallet/service/CompanyMasterWalletService.java`
- Test: `src/test/java/com/application/e_wallet/company_wallet/service/CompanyMasterWalletServiceTest.java`

**Interfaces:**
- Consumes: `CompanyMasterWalletRepository` (Task 3), `FinancialAccountRepository` (Task 3), `UserRepository` (existing), `TransactionJournalRepository` (Task 2), `LedgerService.postJournal(...)` (Task 2), the six DTOs (Task 3).
- Produces (used by Task 5):
  - `CompanyMasterWalletService.getWallet() -> CompanyMasterWalletResponse`
  - `CompanyMasterWalletService.getJournalHistory() -> List<TransactionJournalResponse>`
  - `CompanyMasterWalletService.mint(MintFloatRequest request, UUID adminUserId) -> CompanyMasterWalletResponse`
  - `CompanyMasterWalletService.allocate(AllocateFloatRequest request, UUID adminUserId) -> CompanyMasterWalletResponse`
  - `CompanyMasterWalletService.withdraw(AgentWithdrawRequest request, UUID adminUserId) -> CompanyMasterWalletResponse`

- [ ] **Step 1: Write the failing tests**

```java
@ExtendWith(MockitoExtension.class)
class CompanyMasterWalletServiceTest {

    @Mock private CompanyMasterWalletRepository companyMasterWalletRepository;
    @Mock private FinancialAccountRepository financialAccountRepository;
    @Mock private UserRepository userRepository;
    @Mock private TransactionJournalRepository transactionJournalRepository;
    @Mock private LedgerService ledgerService;
    @InjectMocks private CompanyMasterWalletService companyMasterWalletService;

    private UUID adminUserId;
    private UserEntity admin;
    private CompanyMasterWalletEntity wallet;

    @BeforeEach
    void setUp() {
        adminUserId = UUID.randomUUID();
        admin = UserEntity.builder().id(adminUserId).build();
        wallet = CompanyMasterWalletEntity.builder()
                .id(UUID.randomUUID())
                .singletonKey((short) 1)
                .unallocatedFloat(new BigDecimal("1000.00"))
                .totalDistributedFloat(new BigDecimal("500.00"))
                .currency("TZS")
                .build();
        lenient().when(userRepository.findById(adminUserId)).thenReturn(Optional.of(admin));
        lenient().when(companyMasterWalletRepository.findSingletonForUpdate()).thenReturn(Optional.of(wallet));
        lenient().when(companyMasterWalletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void mintIncreasesUnallocatedFloatAndPostsBalancedJournal() {
        companyMasterWalletService.mint(new MintFloatRequest(new BigDecimal("200.00"), "bank deposit"), adminUserId);

        assertThat(wallet.getUnallocatedFloat()).isEqualTo(new BigDecimal("1200.00"));
        verify(companyMasterWalletRepository).findSingletonForUpdate();
        verify(ledgerService).postJournal(eq(TransactionType.MINT_FLOAT), any(), eq(admin), argThat(lines ->
                lines.size() == 2
                && lines.stream().anyMatch(l -> l.ledgerAccountType() == LedgerAccountType.ESCROW_BANK && l.entryType() == EntryType.DEBIT)
                && lines.stream().anyMatch(l -> l.ledgerAccountType() == LedgerAccountType.COMPANY_UNALLOCATED && l.entryType() == EntryType.CREDIT)
        ));
    }

    @Test
    void mintRejectsNonPositiveAmount() {
        assertThatThrownBy(() -> companyMasterWalletService.mint(new MintFloatRequest(BigDecimal.ZERO, "x"), adminUserId))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(ledgerService);
    }

    @Test
    void allocateMovesFloatFromUnallocatedToAgent() {
        FinancialAccountEntity agentAccount = FinancialAccountEntity.builder()
                .id(UUID.randomUUID())
                .currentBalance(new BigDecimal("50.00"))
                .status(FinancialAccountStatus.ACTIVE)
                .build();
        UUID agentId = UUID.randomUUID();
        when(financialAccountRepository.findByWallet_Agent_Id(agentId)).thenReturn(Optional.of(agentAccount));

        companyMasterWalletService.allocate(new AllocateFloatRequest(agentId, new BigDecimal("300.00"), "topup"), adminUserId);

        assertThat(wallet.getUnallocatedFloat()).isEqualTo(new BigDecimal("700.00"));
        assertThat(wallet.getTotalDistributedFloat()).isEqualTo(new BigDecimal("800.00"));
        assertThat(agentAccount.getCurrentBalance()).isEqualTo(new BigDecimal("350.00"));
        verify(companyMasterWalletRepository).findSingletonForUpdate();
    }

    @Test
    void allocateSucceedsWhenAmountEqualsAvailableUnallocatedFloat() {
        FinancialAccountEntity agentAccount = FinancialAccountEntity.builder()
                .id(UUID.randomUUID()).currentBalance(BigDecimal.ZERO).status(FinancialAccountStatus.ACTIVE).build();
        UUID agentId = UUID.randomUUID();
        when(financialAccountRepository.findByWallet_Agent_Id(agentId)).thenReturn(Optional.of(agentAccount));

        companyMasterWalletService.allocate(new AllocateFloatRequest(agentId, new BigDecimal("1000.00"), "all of it"), adminUserId);

        assertThat(wallet.getUnallocatedFloat()).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    void allocateRejectsWhenUnallocatedFloatInsufficient() {
        FinancialAccountEntity agentAccount = FinancialAccountEntity.builder()
                .id(UUID.randomUUID()).currentBalance(BigDecimal.ZERO).status(FinancialAccountStatus.ACTIVE).build();
        UUID agentId = UUID.randomUUID();
        when(financialAccountRepository.findByWallet_Agent_Id(agentId)).thenReturn(Optional.of(agentAccount));

        assertThatThrownBy(() -> companyMasterWalletService.allocate(
                new AllocateFloatRequest(agentId, new BigDecimal("1000.01"), "too much"), adminUserId))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(ledgerService);
    }

    @Test
    void allocateRejectsInactiveAgentAccount() {
        FinancialAccountEntity agentAccount = FinancialAccountEntity.builder()
                .id(UUID.randomUUID()).currentBalance(BigDecimal.ZERO).status(FinancialAccountStatus.SUSPENDED).build();
        UUID agentId = UUID.randomUUID();
        when(financialAccountRepository.findByWallet_Agent_Id(agentId)).thenReturn(Optional.of(agentAccount));

        assertThatThrownBy(() -> companyMasterWalletService.allocate(
                new AllocateFloatRequest(agentId, new BigDecimal("10.00"), "x"), adminUserId))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(ledgerService);
    }

    @Test
    void withdrawMovesFloatFromAgentToUnallocated() {
        FinancialAccountEntity agentAccount = FinancialAccountEntity.builder()
                .id(UUID.randomUUID()).currentBalance(new BigDecimal("100.00")).status(FinancialAccountStatus.ACTIVE).build();
        UUID agentId = UUID.randomUUID();
        when(financialAccountRepository.findByWallet_Agent_Id(agentId)).thenReturn(Optional.of(agentAccount));

        companyMasterWalletService.withdraw(new AgentWithdrawRequest(agentId, new BigDecimal("40.00"), "return"), adminUserId);

        assertThat(wallet.getUnallocatedFloat()).isEqualTo(new BigDecimal("1040.00"));
        assertThat(agentAccount.getCurrentBalance()).isEqualTo(new BigDecimal("60.00"));
        verify(companyMasterWalletRepository).findSingletonForUpdate();
    }

    @Test
    void withdrawSucceedsWhenAmountEqualsAgentBalance() {
        FinancialAccountEntity agentAccount = FinancialAccountEntity.builder()
                .id(UUID.randomUUID()).currentBalance(new BigDecimal("25.00")).status(FinancialAccountStatus.ACTIVE).build();
        UUID agentId = UUID.randomUUID();
        when(financialAccountRepository.findByWallet_Agent_Id(agentId)).thenReturn(Optional.of(agentAccount));

        companyMasterWalletService.withdraw(new AgentWithdrawRequest(agentId, new BigDecimal("25.00"), "all"), adminUserId);

        assertThat(agentAccount.getCurrentBalance()).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    void withdrawRejectsWhenAgentBalanceInsufficient() {
        FinancialAccountEntity agentAccount = FinancialAccountEntity.builder()
                .id(UUID.randomUUID()).currentBalance(new BigDecimal("10.00")).status(FinancialAccountStatus.ACTIVE).build();
        UUID agentId = UUID.randomUUID();
        when(financialAccountRepository.findByWallet_Agent_Id(agentId)).thenReturn(Optional.of(agentAccount));

        assertThatThrownBy(() -> companyMasterWalletService.withdraw(
                new AgentWithdrawRequest(agentId, new BigDecimal("10.01"), "x"), adminUserId))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(ledgerService);
    }

    @Test
    void withdrawRejectsInactiveAgentAccount() {
        FinancialAccountEntity agentAccount = FinancialAccountEntity.builder()
                .id(UUID.randomUUID()).currentBalance(new BigDecimal("10.00")).status(FinancialAccountStatus.CLOSED).build();
        UUID agentId = UUID.randomUUID();
        when(financialAccountRepository.findByWallet_Agent_Id(agentId)).thenReturn(Optional.of(agentAccount));

        assertThatThrownBy(() -> companyMasterWalletService.withdraw(
                new AgentWithdrawRequest(agentId, new BigDecimal("5.00"), "x"), adminUserId))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(ledgerService);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=CompanyMasterWalletServiceTest`
Expected: FAIL (compilation error — `CompanyMasterWalletService` does not exist yet)

- [ ] **Step 3: Implement `CompanyMasterWalletService`**

```java
@Service
@RequiredArgsConstructor
public class CompanyMasterWalletService {

    private final CompanyMasterWalletRepository companyMasterWalletRepository;
    private final FinancialAccountRepository financialAccountRepository;
    private final UserRepository userRepository;
    private final TransactionJournalRepository transactionJournalRepository;
    private final LedgerService ledgerService;

    public CompanyMasterWalletResponse getWallet() { ... } // companyMasterWalletRepository.findSingleton(), map to response, IllegalStateException if absent

    public List<TransactionJournalResponse> getJournalHistory() { ... } // transactionJournalRepository.findAllByOrderByCreatedAtDesc(), map each to TransactionJournalResponse

    @Transactional
    public CompanyMasterWalletResponse mint(MintFloatRequest request, UUID adminUserId) { ... }

    @Transactional
    public CompanyMasterWalletResponse allocate(AllocateFloatRequest request, UUID adminUserId) { ... }

    @Transactional
    public CompanyMasterWalletResponse withdraw(AgentWithdrawRequest request, UUID adminUserId) { ... }
}
```

Implementation notes (the algorithm the signatures above don't pin):
- Each of `mint`/`allocate`/`withdraw`: validate `request.amount().compareTo(BigDecimal.ZERO) > 0` first, else `throw new BadRequestException(...)` — before any repository call, satisfying the "non-positive amount at the service layer" Review Focus item.
- Resolve `UserEntity admin = userRepository.findById(adminUserId).orElseThrow(() -> new IllegalStateException("Authenticated user not found"));`.
- Load the wallet via `companyMasterWalletRepository.findSingletonForUpdate().orElseThrow(() -> new IllegalStateException("Company master wallet not initialized"));` — always the locked method, in all three operations.
- `allocate`/`withdraw` resolve the agent account via `financialAccountRepository.findByWallet_Agent_Id(request.agentId()).orElseThrow(() -> new BadRequestException("Agent float account not found"));` then check `agentAccount.getStatus() == FinancialAccountStatus.ACTIVE`, else `BadRequestException`.
- Balance checks use `compareTo` (`unallocatedFloat.compareTo(amount) < 0` / `currentBalance.compareTo(amount) < 0`) — not `<=` — so an amount exactly equal to the available balance succeeds.
- Debit/credit pairs exactly as the spec's table: mint = `ESCROW_BANK` debit / `COMPANY_UNALLOCATED` credit against `wallet.getId()` for both; allocate = `COMPANY_UNALLOCATED` debit (wallet id) / `AGENT_FLOAT` credit (agent account id); withdraw = reverse of allocate.
- Call `ledgerService.postJournal(...)` after updating in-memory balances but before/alongside `companyMasterWalletRepository.save(wallet)` and `financialAccountRepository.save(agentAccount)` — order doesn't matter for correctness since all three happen in the same `@Transactional` method and commit or roll back together.
- `getJournalHistory()` maps each `TransactionJournalEntity` + its `lines` to `TransactionJournalResponse`/`JournalLineResponse` using `.name()` on the enums for the response's `String` fields.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=CompanyMasterWalletServiceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/application/e_wallet/company_wallet/service src/test/java/com/application/e_wallet/company_wallet/service
git commit -m "feat: add company master wallet mint, allocate, and withdraw operations"
```

---

## Task 5: `CompanyMasterWalletController` and wiring

**Files:**
- Create: `src/main/java/com/application/e_wallet/company_wallet/controller/CompanyMasterWalletController.java`

**Interfaces:**
- Consumes: `CompanyMasterWalletService` (Task 4), `AuthenticatedUser` (`com.application.e_wallet.security.jwt.AuthenticatedUser`, existing), `ApiResponse<T>` (existing).

- [ ] **Step 1: Implement the controller**

```java
@RestController
@RequestMapping("/api/v1/company-wallet")
@RequiredArgsConstructor
public class CompanyMasterWalletController {

    private final CompanyMasterWalletService companyMasterWalletService;

    @GetMapping
    @PreAuthorize("hasAuthority('COMPANY_WALLET_VIEW')")
    public ResponseEntity<ApiResponse<CompanyMasterWalletResponse>> getWallet() { ... }

    @GetMapping("/journal")
    @PreAuthorize("hasAuthority('LEDGER_VIEW')")
    public ResponseEntity<ApiResponse<List<TransactionJournalResponse>>> getJournal() { ... }

    @PostMapping("/mint")
    @PreAuthorize("hasAuthority('FLOAT_MANAGE')")
    public ResponseEntity<ApiResponse<CompanyMasterWalletResponse>> mint(
            @Valid @RequestBody MintFloatRequest request, Authentication authentication) { ... }

    @PostMapping("/allocate")
    @PreAuthorize("hasAuthority('FLOAT_MANAGE')")
    public ResponseEntity<ApiResponse<CompanyMasterWalletResponse>> allocate(
            @Valid @RequestBody AllocateFloatRequest request, Authentication authentication) { ... }

    @PostMapping("/withdraw")
    @PreAuthorize("hasAuthority('FLOAT_MANAGE')")
    public ResponseEntity<ApiResponse<CompanyMasterWalletResponse>> withdraw(
            @Valid @RequestBody AgentWithdrawRequest request, Authentication authentication) { ... }
}
```

Implementation notes: for `mint`/`allocate`/`withdraw`, extract `((AuthenticatedUser) authentication.getPrincipal()).userId()` and pass it as the `adminUserId` argument to the matching service method (same extraction pattern as `AuthController`'s `principal.email()` usage). Build each `ApiResponse` with `ApiResponse.<T>builder().timestamp(Instant.now()).status(HttpStatus.OK.value()).message("...").data(response).build()`, wrapped in `ResponseEntity.ok(...)` (mint/allocate/withdraw return `200 OK` with the updated wallet state, not `201 Created` — these mutate a singleton rather than create a resource).

- [ ] **Step 2: Verify the project compiles and the context loads**

Run: `mvn test -Dtest=EWalletApplicationTests`
Expected: PASS (confirms the new controller bean wires up cleanly — missing dependencies or security-config errors would fail context load). This repo has no `@WebMvcTest`/`MockMvc` controller tests for any existing controller (`AgentController`, `AuthController` included) — do not introduce that test style here.

- [ ] **Step 3: Manual smoke test (not automated — matches this repo's current practice for controllers)**

Start the app locally and, as a user holding the `ADMIN` role, call:
```
GET  /api/v1/company-wallet
POST /api/v1/company-wallet/mint      {"amount": 1000.00, "description": "initial deposit"}
POST /api/v1/company-wallet/allocate  {"agentId": "<an onboarded agent's id>", "amount": 100.00, "description": "topup"}
GET  /api/v1/company-wallet/journal
POST /api/v1/company-wallet/withdraw  {"agentId": "<same agent id>", "amount": 50.00, "description": "return"}
```
Confirm the balances move as expected after each call and the journal shows four balanced entries.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/application/e_wallet/company_wallet/controller
git commit -m "feat: add company master wallet REST endpoints"
```
