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
- **Limits** *(mechanism my call; amounts yours, Open question 4)*: per-transaction maximum and daily total per customer for transfer and withdraw, read from configuration (`application.properties`), enforced in one shared `LimitService`. KYC-tiered limits come later with KYC review (Phase 3).
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

## Engineering foundations

### Database (`e_walletDB`)

Generated on 23 Nov by replaying every Flyway migration from `E-wallet-backend@84f2bc1` into PostgreSQL 16. The result is 20 tables: the 19 below plus `flyway_schema_history`, which holds 46 migrations numbered V1–V47 (there is no V12). There are 175 columns and 23 foreign keys. Every table has a UUID `id` primary key except `role_permissions`, whose key is `(role_id, permission_id)`. Arrows mark foreign keys; `(null)` marks nullable columns.

#### Identity & access

- **`users`**: `id` uuid, `email` varchar(255), `phone` varchar(30), `pin_hash` varchar(255) (null), `status` varchar(30), `created_at` timestamptz, `updated_at` timestamptz, `reference` uuid, `username` varchar(100), `last_login_at` timestamptz (null), `role_id` uuid → `roles`
- **`roles`**: `id` uuid, `name` varchar(50), `description` varchar(255) (null), `created_at` timestamptz, `updated_at` timestamptz. *4 seeded: ADMIN, CUSTOMER, AGENT, SYSTEM.*
- **`role_permissions`**: `role_id` uuid → `roles`, `permission_id` uuid → `permissions`
- **`permissions`**: `id` uuid, `name` varchar(255), `description` varchar(255) (null), `created_at` timestamptz, `updated_at` timestamptz, `module_reference` uuid → `modules`. *36 seeded.*
- **`modules`**: `id` uuid, `reference` uuid, `name` varchar(50), `description` varchar(255), `created_at` timestamptz. *10 seeded.*
- **`user_sessions`**: `id` uuid, `user_id` uuid → `users`, `refresh_token_hash` varchar(255), `expires_at` timestamptz, `revoked` boolean, `created_at` timestamptz, `revoked_at` timestamptz (null)
- **`otp_verification`**: `id` uuid, `user_id` uuid → `users`, `otp_hash` varchar(255), `expires_at` timestamptz, `attempts` integer, `max_attempts` integer, `verified` boolean, `verified_at` timestamptz (null), `created_at` timestamptz
- **`pin_reset_tokens`**: `id` uuid, `user_id` uuid → `users`, `otp_hash` varchar(255), `expires_at` timestamptz, `attempts` integer, `max_attempts` integer, `used` boolean, `used_at` timestamptz (null), `created_at` timestamptz

#### Parties

- **`customers`**: `id` uuid, `user_id` uuid → `users`, `status` varchar(20), `created_at` timestamptz, `updated_at` timestamptz, `reference` uuid, `first_name` varchar(100), `middle_name` varchar(100) (null), `last_name` varchar(100), `email` varchar(255), `phone_number` varchar(30), `nida_number` varchar(30), `date_of_birth` date, `address` varchar(255)
- **`agents`**: `id` uuid, `user_id` uuid → `users`, `agent_number` varchar(30), `created_at` timestamptz, `update_at` timestamptz, `gender` varchar(10), `date_of_birth` date, `region_id` uuid → `regions`, `district_id` uuid → `districts`, `reference` uuid, `first_name` varchar(100), `middle_name` varchar(100) (null), `last_name` varchar(100), `email` varchar(255), `phone_number` varchar(30), `business_name` varchar(150), `tin_number` varchar(30), `agent_status` varchar(20), `created_by` uuid → `users` (null)
- **`staff`**: `id` uuid, `reference` uuid, `user_id` uuid → `users`, `staff_no` varchar(30), `job_title` varchar(100), `first_name` varchar(100), `middle_name` varchar(100) (null), `last_name` varchar(100), `email` varchar(255), `phone_number` varchar(30), `created_by` uuid → `users` (null), `status` varchar(20), `created_at` timestamptz, `updated_at` timestamptz

#### Money & ledger

- **`wallets`**: `id` uuid, `currency` varchar(3), `status` varchar(20), `created_at` timestamptz, `updated_at` timestamptz, `user_id` uuid → `users`, `wallet_type` varchar(30), `account_number` varchar(30), `balance` numeric(19,2), `version` bigint, `reference` uuid, `account_name` varchar(150). *4 company wallets seeded.*
- **`transactions`**: `id` uuid, `reference_no` varchar(60), `sender_wallet_id` uuid → `wallets`, `receiver_wallet_id` uuid → `wallets`, `amount` numeric(19,2), `fee` numeric(19,2), `commission` numeric(19,2), `transaction_type` varchar(30), `status` varchar(20), `created_at` timestamptz
- **`transaction_journal`**: `id` uuid, `reference_number` varchar(60), `transaction_type` varchar(30), `description` varchar(255), `created_by` uuid → `users`, `created_at` timestamptz
- **`transaction_journal_line`**: `id` uuid, `journal_id` uuid → `transaction_journal`, `entry_type` varchar(10), `amount` numeric(19,2), `wallet_id` uuid → `wallets`
- **`tariffs`**: `id` uuid, `reference` uuid, `transaction_type` varchar(30), `min_amount` numeric(19,2), `max_amount` numeric(19,2), `fee` numeric(19,2), `agent_commission` numeric(19,2), `active` boolean, `created_at` timestamptz. *2 seeded: CASH_IN, CASH_OUT.*
- **`treasury_requests`**: `id` uuid, `agent_id` uuid → `agents`, `amount` numeric(19,2), `reason` varchar(500), `status` varchar(20), `decided_by` uuid → `users` (null), `decision_note` varchar(500) (null), `decided_at` timestamptz (null), `created_at` timestamptz, `updated_at` timestamptz, `request_ref` varchar(20), `request_type` varchar(20), `external_reference` varchar(100) (null)

#### Geography

- **`regions`**: `id` uuid, `external_id` varchar(100), `name` varchar(100), `slug` varchar(100). *31 seeded.*
- **`districts`**: `id` uuid, `external_id` varchar(100), `name` varchar(100), `slug` varchar(100), `region_id` uuid → `regions`. *170 seeded.*

**Enums the code relies on**

| Enum | Values |
|---|---|
| `WalletType` | COMPANY_MASTER_ACCOUNT, REVENUE, ESCROW_BANK, COMMISSION, AGENT_FLOAT, AGENT_COMMISSION, CUSTOMER_MAIN |
| `LedgerTransactionType` | MINT_FLOAT, TOPUP_FLOAT, AGENT_WITHDRAW, TOPUP_COMMISSION, CASH_IN, CASH_OUT (+ TRANSFER in Phase 1) |
| `TransactionType` | CASH_IN, CASH_OUT, SERVICE_FEE, SERVICE_COMMISSION (+ TRANSFER in Phase 1) |
| `EntryType` | DEBIT, CREDIT |
| `AgentStatus` | ACTIVE, SUSPENDED, DEACTIVATED |

**Schema rules**

- Never edit or rename a merged migration. The next file is `V48`, and each file makes one change.
- Hibernate runs with `ddl-auto=validate`: a PR that adds a migration also updates the matching entity.
- Money is `numeric(19,2)`, never floating point. Balances change only in the same DB transaction as their journal lines.
- `transactions.transaction_type` and `status` are plain `varchar`. Queries compare against enum `.name()`.
- New tables, in order: `idempotency_keys` (Phase 0), `beneficiaries` (Phase 1), `audit_logs` (Phase 3), then `devices` and `notifications` (Phases 4–5).

### Project structure

Both repos are organised by feature. Lines marked `+` are proposed by this design; `-` marks what to remove.

**Backend**: `Abuu37/E-wallet-backend` · Spring Boot 4.2 · Java 21 · Maven

```
e-wallet-backend/
├─ pom.xml                       JPA, Security, Flyway, Mail, JJWT, Bucket4j, libphonenumber
├─ .env.example                  DB, admin seed, SMTP, JWT_SECRET
├─ + Dockerfile
├─ + .github/workflows/ci.yml
├─ docs/superpowers/{specs,plans}/
└─ src/main/java/com/application/e_wallet/
   ├─ auth/  otp/  security/          login, PIN, refresh, OTP, SecurityConfig, JWT, DataInitializer
   ├─ user/  role/  permission/  module/
   ├─ customer/  agent/  staff/
   ├─ wallet/                         7 wallet types, PESSIMISTIC_WRITE locks
   ├─ ledger/                         LedgerService.postJournal (rejects unbalanced)
   ├─ transaction/                    cash-in, cash-out (+ transfer, history, receipts)
   ├─ treasury/                       mint, commission top-up, float requests
   ├─ tariff/  location/  dashboard/{admin,agent}/
   ├─ common/                         exceptions, validation, events
   ├─ + idempotency/                  Idempotency-Key filter + idempotency_keys
   ├─ + limit/                        LimitService (per-transaction and daily)
   ├─ + audit/                        audit_logs (Phase 3)
   └─ + config/OpenApiConfig.java     springdoc
   src/main/resources/db/migration/   V1…V47, next V48
   src/test/java/…                    11 classes, 74 tests (+ TransactionServiceTest)
```

Inside each feature: `controller/ dto/ entity/ repository/ service/`. DTOs are records named `*Request`/`*Response`, errors go through `common/exception/GlobalExceptionHandler`, and every money flow posts through `LedgerService`.

**Frontend**: `Abuu37/E-wallet-frontend` · React 19 · Vite 8 · TypeScript · Tailwind 4

```
e-wallet-frontend/
├─ .env  .env.production         VITE_API_BASE_URL only (no secrets)
├─ + Dockerfile  nginx.conf
├─ + .github/workflows/ci.yml
└─ src/
   ├─ App.tsx                    routes: public, /dashboard (staff/agent), /account (customer)
   ├─ + lib/api/client.ts        base URL, bearer token, refresh-on-401 once, typed errors
   ├─ components/                Guard, context, layout, ui
   ├─ pages/                     Home, services, Fees, FAQ, Contact
   └─ features/
      ├─ auth/                   register, login, forgot PIN (live API)
      ├─ account/                customer overview, withdraw
      ├─ overview/ transactions/ customers/ ledger/ reconciliation/ fees/ financial/ wallets/ settings/
      ├─ cashiers/  → rename to agents/
      ├─ - loans/                hide until it has a backend and spec
      ├─ - */data.ts             mock data, replaced by per-feature api.ts
      ├─ + send-money/  + history/  + agent/
```

Frontend conventions to adopt: all HTTP goes through `src/lib/api`, never `fetch` inside components. Each feature owns `api.ts`, `types.ts` and `pages/`. Convert `.jsx` to `.tsx` as files are touched. Role names match the backend (`ADMIN`, `AGENT`, `CUSTOMER`).

**Mobile (Phase 5)**: a third repo, `E-wallet-mobile`, on the same `/api/v1`, sharing design tokens and validation rules.

### Docker

Neither repo has Docker today. The proposal is one Compose stack for local and staging, and the same images for production.

| Service | Image | Port | Role |
|---|---|---|---|
| `frontend` | nginx:alpine (built from node:22-alpine) | 80 | Serves `dist/` and proxies `/api/` to the backend, so there's no CORS in production |
| `backend` | eclipse-temurin:21-jre (built with maven:3.9-eclipse-temurin-21) | 8080 | Runs Flyway on start, then validates entities |
| `db` | postgres:16-alpine | 5432, volume `pgdata` | `e_walletDB`, health-checked before the backend starts |
| `mail` (dev only) | axllent/mailpit | SMTP 1025, UI 8025 | Catches OTP emails locally |

`docker-compose.yml`:

```yaml
services:
  db:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: e_walletDB
      POSTGRES_USER: ${DB_USERNAME}
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    volumes: [pgdata:/var/lib/postgresql/data]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${DB_USERNAME}"]
      interval: 5s
      retries: 10
  mail:
    image: axllent/mailpit
    ports: ["8025:8025"]
  backend:
    build: ./e-wallet-backend
    env_file: .env
    environment:
      DB_URL: jdbc:postgresql://db:5432/e_walletDB
      APP_MAIL_HOST: mail
      APP_MAIL_PORT: 1025
    depends_on:
      db: { condition: service_healthy }
    ports: ["8080:8080"]
  frontend:
    build: ./e-wallet-frontend
    depends_on: [backend]
    ports: ["80:80"]
volumes:
  pgdata:
```

`e-wallet-backend/Dockerfile`:

```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml mvnw ./
COPY .mvn .mvn
RUN ./mvnw -q dependency:go-offline
COPY src src
RUN ./mvnw -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd -r wallet
USER wallet
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

`e-wallet-frontend/Dockerfile` and `nginx.conf`:

```dockerfile
FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
ARG VITE_API_BASE_URL=/api/v1
RUN npm run build

FROM nginx:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
```

```nginx
server {
  listen 80;
  location /api/ { proxy_pass http://backend:8080; }
  location / {
    root /usr/share/nginx/html;
    try_files $uri /index.html;
  }
}
```

Where the Compose file lives is left to the builder. It can sit in the backend root with the frontend as a sibling folder, or in a small third repo.

### Environments and configuration

| Environment | How it runs |
|---|---|
| Local | `docker compose up`, or IntelliJ + `npm run dev` against the `db` container. The database can be dropped and rebuilt at any time. |
| Staging | Same images, deployed from `main` after CI passes. Seeded test agents and customers. Used for demos. |
| Production | Same images, tagged releases only. Managed PostgreSQL with daily backups, a real SMTP or SMS gateway, secrets from the host's secret store, HTTPS at the edge. |

| Setting | Notes |
|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | Per environment |
| `JWT_SECRET` | 256-bit random, different in every environment |
| `APP_MAIL_HOST/PORT/USER/PASS` | SMTP for OTP; Mailpit locally |
| `APP_INITIAL_ADMIN_*` | Seeds the first admin; enable only on a fresh database |
| `VITE_API_BASE_URL` | Frontend build-time; `/api/v1` behind nginx |

The backend ignores `.env` and commits `.env.example`. The frontend commits `.env` and `.env.production`, which hold only the public API URL. No secrets go in either repo.

### Git workflow

Today both repos commit straight to `main` with free-form messages and no CI.

- **`main` is protected and always deployable.** Merges require a pull request and passing CI.
- **Short-lived branches**, one story or fix each, named `feature/US-202-send-money`, `fix/…`, `docs/…` or `chore/…`. There is no long-lived `develop` branch *(my call; small team)*.
- **The flow:** branch from `main` → failing test, then code → push and open a PR that links the story and spec → CI green → one review (a person or Claude code review) → squash merge → delete the branch.
- **Conventional Commits:** `feat(transfer): …`, `fix(cash-out): …`, `test(…)`, `docs: …`, `refactor(…)`, `chore(ci): …`.
- **Releases:** tag `main` as `v0.x.y` when a phase ships. Docker images get the same tag, and release notes are generated from commit types.
- **This design** is committed on `docs/flagship-program-design`. Pushing needs the Claude GitHub App on both repos.

### CI (GitHub Actions)

Backend `.github/workflows/ci.yml`:

```yaml
on: [pull_request, push]
jobs:
  build:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: postgres:16-alpine
        env: { POSTGRES_DB: e_walletDB, POSTGRES_PASSWORD: test }
        ports: ["5432:5432"]
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 21, cache: maven }
      - run: ./mvnw -B verify
        env:
          DB_URL: jdbc:postgresql://localhost:5432/e_walletDB
          DB_USERNAME: postgres
          DB_PASSWORD: test
          JWT_SECRET: ci-only-not-a-real-secret
```

Frontend `.github/workflows/ci.yml`:

```yaml
on: [pull_request, push]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: 22, cache: npm }
      - run: npm ci
      - run: npm run lint
      - run: npx tsc --noEmit
      - run: npm run build
```

### Testing, observability and security baseline

- **Testing:**
  - Unit tests use JUnit 5 + Mockito; there are 74 today, and every money rule gets one.
  - Integration tests use Testcontainers PostgreSQL and assert that journals sum to zero.
  - Frontend tests use Vitest + Testing Library.
  - Playwright end-to-end tests cover register, send, cash-in and withdraw.
- **Observability:** Spring Boot Actuator `/health` for Docker and uptime checks; JSON logs to stdout in containers (today: `logs/app.log`); a request ID echoed in error responses; the daily reconciliation result shown on the admin dashboard.
- **Security:** containers run as a non-root user; HTTPS everywhere outside local; Bucket4j rate limits (already a dependency) on OTP, login and recipient lookup; Dependabot on both repos; no secrets in git.

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
