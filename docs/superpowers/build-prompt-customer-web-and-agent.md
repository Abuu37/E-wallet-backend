# Build prompt: WalletPesa customer web app and agent portal (current scope)

Paste everything below the line into your coding assistant, opened on both repos.

---

You are working on **WalletPesa**, a Tanzanian e-wallet (currency TZS, timezone Africa/Dar_es_Salaam) built as a portfolio and pitch demo. It has two repos:

- **Backend:** `Abuu37/E-wallet-backend`, Spring Boot, PostgreSQL (`e_walletDB`), Flyway migrations, API under `/api/v1`.
- **Frontend:** `Abuu37/E-wallet-frontend`, React 19 + Vite + Tailwind 4 + react-router 7 + Formik/Yup + recharts. One web app for everyone. **Keep today's routes and shells**: `/login`, `/register`, `/forgot-pin`, customer area `/app`, agent area `/agent`, staff area `/console`, and `/change-pin` for setting your own PIN. Don't rename them; the design's screens map onto them.

The source of truth for this work:
- Plan: `docs/superpowers/specs/2026-11-23-flagship-wallet-program-design.md` (backend repo), especially *Money movement model*, *Phase 0*, *Phase 1*, *Phase 1 — Plaid integration* and *Phase 2*.
- Money rules: `docs/superpowers/money-movement-agreed.md`.
- SMS points: `docs/superpowers/sms-notifications.md`.
- Parked items (do NOT build): `docs/superpowers/later.md`.
- Design (screens to match): https://claude.ai/artifact/JnMU5U4tzzVPAcsVHJwsJu, page **Customer web app** (W01–W52) and page **Agent portal** (A01–A06).

The plan files are on the backend branch **`docs/flagship-program-design`** (`git fetch && git checkout docs/flagship-program-design`, or merge it), in `docs/superpowers/`.

Read those files first. When this prompt and the plan disagree, the plan wins, **except for the notification channel: use email for now (rule 2), even where the plan and the designs say SMS.** Tell me about any other conflict.

## Ground rules (apply to every task)

1. **No username anywhere.** Everyone signs in with **phone number + 4-digit PIN** at `/login`. The backend fills the unique `users.username` column itself: the customer reference for customers, the agent ID (`AG-10234`) for agents, the staff number (`ST-0005`) for staff.
2. **Email for now, SMS later.** The system already sends email, so for now every code and message goes **by email as it does today**: registration codes (OTP), forgot-PIN codes, and the one-time PIN for new agents and staff. Money alerts (cash-in, send, receive, withdrawals, bank top-ups, float) also go by email for now.
   - Put every message behind one `NotificationSender` interface with the template keys from `sms-notifications.md` (the keys and wording stay the same; only the channel changes). Today's implementation is `EmailNotificationSender`, using the existing email setup. An `SmsNotificationSender` will replace it when the SMS provider is integrated, with no change to the callers.
   - Send after the database transaction commits, never inside it. Never put a PIN in a message except the one-time PIN itself, and never a full account number.
   - On screen, say **"email"** wherever the designs say "SMS" (for example "Check your email for the code", "You'll get an email when it's checked").
3. **Every wallet shilling is backed by escrow.** Wallet money is created only when real money enters the escrow bank account (`ESCROW_BANK`), and leaves only when escrow pays it out. Float is issued from escrow, not minted.
4. **Every money flow:** one `@Transactional` method → lock wallets in id order → check PIN, statuses, balance, limits → move balances → `postJournal` (balanced) → write the `transactions` row → store the idempotent response. Money POSTs take an `Idempotency-Key` header.
5. **Limits by KYC tier**, from configuration (one shared `LimitService`):
   - Basic (not verified): 200,000 per payment, 500,000 per day, 1,000,000 max balance.
   - Verified (Plaid IDV passed): 3,000,000 per payment, 5,000,000 per day, 10,000,000 max balance.
6. **Fees and commission:** withdrawal at an agent 300 TZS; withdrawal to own bank 1,000 TZS; agents pay no fees; agent commission 200 TZS per cash-in and per customer withdrawal, paid automatically from the `COMMISSION` pool. If the pool is empty, the transaction still completes, the commission is recorded as owed and paid after the next refill.
7. **Plaid Sandbox only.** Plaid is "the bank side" of the demo: Identity Verification for KYC, Link + Auth + Balance for linked banks, and `/sandbox/transactions/create` to post the matching line on the user's Sandbox bank: a debit (positive amount) when money leaves their bank, a credit (negative amount) when money arrives. Label every bank movement on screen and receipt **"Demo: Plaid Sandbox"**. Keys stay server-side in `.env`; store access tokens encrypted; never put tokens in browser storage. `/sandbox/transactions/create` works on any Sandbox Item, so linking with `user_good` is fine.
8. **Own bank only.** A bank can be used for top-ups or withdrawals only if the Plaid account-holder name matches the customer's verified name (or the agent's business name). **Sandbox override:** Sandbox test banks never carry a WalletPesa customer's name, so when `PLAID_ENV=sandbox` and `plaid.sandbox.skipNameMatch=true` the check is skipped and the screen shows "Demo: name check skipped in Sandbox". The flag must be impossible to switch on outside Sandbox.
9. **Out of scope now, do not build:** sending to other mobile networks (M-Pesa, Mixx by Yas, Airtel Money, HaloPesa) and to someone else's bank (both TIPS, later); super-agents; bills and airtime (need partners, later); savings vaults, request money/QR, statements (Phase 4). Their design screens are marked "Phase 4" or "later"; in the side menu show them with a **"Soon"** tag or hide them.
10. Work in small commits on a feature branch, with tests for every money flow (happy path, insufficient balance, over limit, wrong PIN, repeated Idempotency-Key). Do not commit secrets.

Where this prompt says "SMS C1", "SMS A3" and so on, it means the message with that template key from `sms-notifications.md`, **sent by email for now**.

## Part A — Backend (Phase 0 fixes + Phase 1 + agent float)

### A0. Remove the Company master wallet and mint *(decided 26 Nov)*
Float now comes straight from escrow, so remove `POST /treasury/mint`, its maker-checker flow, and every use of `COMPANY_MASTER_ACCOUNT` in money flows and in the staff Overview and Treasury screens ("Company master: available to issue as float" goes; the Overview shows the commission pool there instead). The commission pool is refilled from REVENUE (one finance staff member); corrections go ESCROW_BANK ↔ REVENUE with two approvers. Keep `COMPANY_MASTER_ACCOUNT` and `MINT_FLOAT` in the enums only so old rows still read. The dev database is rebuilt, so no data migration is needed.

### A1. Accounts and sign-in
- Drop `username` from `CustomerRegistrationRequest`, `AgentCreationRequest` and `StaffCreationRequest`; fill `users.username` as in rule 1.
- Login by phone + PIN. The response includes the role and `mustChangePin`.
- **Forgot PIN (customers):** they identify themselves with **phone + last 4 NIDA digits**; a 6-digit code goes **by email** to the address on file (5 minutes, 5 tries, resend after 60 s). Endpoints `POST /auth/forgot-pin {phone, nidaLast4}`, `POST /otp/verify {phone, otp}`, `POST /auth/reset-pin {phone, otp, newPin}` replace today's `{email}` versions; the sending stays the existing email code. Step 1 **always gives the same answer**, matched or not: "If these details match an account, we've sent a code to the email on file." Don't return or show the email, masked or not. On success revoke every other session and send message S3.
- **Forgot PIN (agents and staff):** no self-service, because they have no NIDA on file. Add **Reset PIN** for admins: `POST /api/v1/agents/{agentId}/reset-pin` (permission `AGENT_MANAGE`) and `POST /api/v1/staff/{staffId}/reset-pin` (ADMIN). It sets a new one-time PIN, sends it by email (S4/S5), sets `mustChangePin`, revokes every session and writes `audit_logs`. The forgot-PIN page shows a line: "Agents and staff: ask your administrator to reset your PIN."
- **First sign-in for agents and staff:** the one-time PIN goes **by email** (S4/S5), as today. The onboarding forms therefore keep the email field (required). While `mustChangePin` is true, only `/auth/change-pin`, `/auth/logout` and `/auth/status` work. Fix the change-PIN controller: it looks the user up by **email**; it must use the **user ID from the token**. Add a test. (Both PIN checks already use the same field: `getPassword()` returns `pinHash`.) New PIN rules: 4 digits, not all the same, not a sequence, different from the one-time PIN. On success set ACTIVE and revoke all sessions.

### A2. Customer money (Phase 1)
- `GET /api/v1/wallets/lookup?phone=` → masked registered name and whether they can receive. Rate-limited. A number that isn't on WalletPesa returns "not found" (the screen says sending to other networks is coming soon).
- `GET /api/v1/tariffs/quote?type=&amount=` → fee and total, used before every confirm screen.
- `POST /api/v1/transactions/transfer` (wallet to wallet, by phone): sender DR amount + fee, receiver CR amount, revenue CR fee. SMS C3 to the sender and C4 to the receiver.
- `GET /api/v1/transactions/me` (paged; filter by type and date; search) and `GET /api/v1/transactions/{referenceNo}` (receipt; only parties to it may read it).
- **Withdraw at an agent (customer-initiated):** the customer enters the agent's **till number**, the amount and their own PIN. The agent never sees the PIN. Legs: customer DR amount + 300, agent float CR amount, revenue CR 300; commission pool → agent commission 200. Response includes a match code the agent sees. SMS C5 and A2.
- **Add money from own bank:** Plaid Link (auth) → exchange → store the token encrypted; `POST /api/v1/transactions/bank-topup`: check the balance with `/accounts/balance/get`, post ESCROW_BANK → CUSTOMER_MAIN (`BANK_TOPUP`), then post the Sandbox **debit**. If the Sandbox call fails, log and retry; never undo the wallet credit. SMS C1.
- **Withdraw to own bank (new):** `POST /api/v1/transactions/bank-withdraw {linkedBankId, amount, pin}`: CUSTOMER_MAIN DR amount + 1,000; ESCROW_BANK CR amount (payout); REVENUE CR 1,000; status `PENDING`. Then post the Sandbox **credit** and mark `COMPLETED` (SMS C6). If it fails, post a reversal journal returning amount + fee, mark `FAILED` (SMS C7). New `TransactionType.BANK_WITHDRAWAL`, `LedgerTransactionType.BANK_WITHDRAW`.
- **KYC with Plaid IDV:** keep the existing `/kyc/plaid/...` endpoints; add a **refresh status** action if it's missing. Map the Plaid status to `customers.kyc_status` (NOT_STARTED, PENDING, VERIFIED, FAILED). VERIFIED moves the customer to the Verified tier straight away. Messages S6/S7.
- `GET /api/v1/agents/nearby?region=&q=` (or reuse the agents/locations endpoints) for **Find an agent**: name, till, district, region, open status.
- Data: `customers.kyc_status` and related columns; new tables `idempotency_keys`, `linked_bank_accounts`, `plaid_webhook_events`, `beneficiaries` (DDL in the plan's *Tables to add*).

### A3. Agent portal (Phase 2)
- **Agent transactions:** `GET /api/v1/agent/transactions?type=&from=&to=&q=&page=&size=` (only rows where this agent's float is a party; phone masked in the list) and `GET /api/v1/agent/transactions/{reference}` (full name, **full phone**, wallet account number; 404 for someone else's reference).
- **Live withdrawals feed:** last 15 minutes by default (up to 24 hours), polled every 5 s: reference, amount, customer name, masked phone, created at.
- **Float top-up from own bank (Path A):** `POST /api/v1/agent/float/bank-topup {linkedBankId, amount, pin}`: balance check through Plaid, ESCROW_BANK → AGENT_FLOAT (`FLOAT_BANK_TOPUP`), Sandbox debit, SMS A3. No approval. **Daily cap 5,000,000 TZS** (`agent.float.bankTopupDailyCap`); over the cap answer with a clear error so the screen can offer *Request float*.
- **Request float (Path B):** `POST /api/v1/agent/float/requests {amount, depositReference, slip}` creates a `treasury_requests` row (`TOPUP`, `PENDING`) with the slip image (new `slip_file_key` column); SMS A4. One staff member approves (ESCROW_BANK → AGENT_FLOAT, SMS A5) or rejects with a reason (SMS A6). Expose the escrow details for the screen: bank, account name, account number, and the agent's reference = agent ID.
- **Send float or commission to own bank:** `POST /api/v1/agent/float/bank-withdraw` and `POST /api/v1/agent/commission/bank-withdraw`; same pattern as the customer bank withdraw, **no fee**; SMS A7/A8. There is no "move commission to float".
- Cash-in needs both the customer's wallet account number and phone, and checks they belong together; SMS C2 and A1.

### A4. Foundations to add first
- **Idempotency:** the frontend already sends `Idempotency-Key`, but the server ignores it. Add the `idempotency_keys` table (DDL in the plan's *Tables to add*) and server handling for every money POST: same key + same body returns the stored response; same key + different body returns 409.
- **Staff side of float requests:** Path B needs staff to approve or reject. Update the staff Treasury screen to match the design board S02/S02b: float requests with the escrow-statement check, *Refill pool* with the low-pool banner, and two-person approval for revenue sweeps, reversals and adjustments.
- **Postman:** regenerate the collections once the endpoints change.

## Suggested order (one branch each, tests for every money flow)
1. Foundations and accounts: A0, A1, A4 (idempotency), `NotificationSender`, shared `LimitService`; screens W02–W13 and W46–W49.
2. Customer money: lookup, quote, transfer, my transactions, receipts, withdraw at an agent, find an agent; screens W14–W26, W30, W40.
3. Plaid bank: Link, Auth, Balance, bank top-up and withdraw, the Sandbox override, KYC refresh; screens W27–W29, W31–W39.
4. Agent portal and staff float approvals: A3, A4 (Treasury); screens A01–A06 and S02/S02b.

## Part B — Frontend: customer web app (match the Customer web app page, W01–W40 and W46–W49)

Build the signed-in customer area under `/app` with a left side menu: Dashboard, Transactions, Send money, Withdraw, Find an agent, Add money, Verify identity, Settings (Phase 4 items tagged "Soon" or hidden).

| Screens | What to build |
|---|---|
| W01 | Public landing page (keep your existing public pages). |
| W02–W04 | `/login`: phone + PIN with Show/Hide, wrong-PIN error, account locked after too many tries. Route by role: customers to `/app`, agents to `/agent`, staff to `/console`; when `mustChangePin` is true go to `/change-pin`. |
| W05–W09 | `/register`: phone → about you (names, NIDA, date of birth, email, address; inline errors) → **email code** (the design says SMS code) → PIN rules → ready. **No username.** |
| W10–W13 | `/forgot-pin`: phone + last 4 NIDA digits → "If these details match an account, we've sent a code to the email on file" → 6-digit code (5 tries, resend timer shown as m:ss) → new PIN with rules → done ("other devices signed out"). Add the line for agents and staff to ask their administrator. |
| W14–W16 | Dashboard (balance, quick actions, recent activity, daily limit used), empty state for a new user, notifications panel. |
| W17–W22 | Send money: pick a saved contact or type a number → show the registered name → amount with fee and total → PIN → processing → success or "failed, money safe". Over-limit error. A number not on WalletPesa shows: *"Not on WalletPesa yet. Sending to other networks (M-Pesa, Mixx by Yas, Airtel Money, HaloPesa) is coming soon."* |
| W23–W24 | Transactions (filter, search, paging) and receipt (download or share). |
| W25–W26 | Withdraw › **Cash at an agent**: till number, amount (quick chips), nearby agents, summary with the 300 fee, PIN → approved screen with the match code to show the agent. |
| W27–W29 | Withdraw › **To my bank** (tab next to Cash at an agent): linked bank card with "✓ Name matches your ID", amount, summary (arrives in bank, fee 1,000, from wallet, balance after), PIN → *Sending…* → sent (with "Demo: Plaid Sandbox shows +amount arriving") or *bank rejected, refunded*. No linked bank → "Link your bank first". |
| W30 | **Find an agent**: region filter and search; cards with name, place, till, hours, open/closed; *Withdraw here* fills the till on the withdraw page; *Copy till*. |
| W31–W34 | Add money: choose bank → Plaid Link login → account and amount (limit checks) → done, labelled Demo: Plaid Sandbox. |
| W35–W39 | Verify identity with Plaid IDV: intro and limits → ID photo → selfie → verified (limits raised) / failed (try again) / pending with *Refresh status*. |
| W40 | Settings: **Account & limits** card (account number `WP-0XXX-XXXXXX`, phone, tier, per payment, max balance, linked bank, *used today* bar, *Verify to raise your limits*), profile, security (signed-in devices, sign out others), alerts (email for now, SMS later). |
| W46–W49 | `/change-pin` for agents and staff on first sign-in or after an admin reset: new PIN + confirm with the rules above → *PIN saved* → back to `/login`. |

Do not build W41–W45 (Phase 4, later) or W50–W52 (earlier drafts, replaced by the screens above).

## Part C — Frontend: agent portal changes (match the Agent portal page)

- **A01 Dashboard:** four cards at the top (Float, Commission wallet, Total transactions, Today) above **Live withdrawals**: a read-only dark panel polling every 5 s, window select (15 min / hour / today), "Updated Xs ago", NEW badge for items under 2 minutes, match code, +200 commission, *Details* opens the transaction. Below it a **7-day grouped bar chart** (cash-ins vs withdrawals, recharts). Profile dropdown shows the name only.
- **A02 Cash-in:** account number + phone, name check, amount, button **"Confirm"**; no "← Dashboard" link.
- **A03 / A03b / A03c Float** (replaces the old "Float request" page): stats row (Float balance, *Bank top-ups today* with a meter "X left of 5,000,000 a day", Commission wallet) and three tabs:
  - **From bank:** linked business bank card ("✓ Name matches your business"), amount + chips, PIN, *Top up X TZS*; over the cap shows *"Request float for the rest"*.
  - **Request float:** Step 1 shows the escrow bank, account name, account number and the reference **AG-xxxxx** with Copy; Step 2 asks for amount deposited, slip number and a slip photo; *Send float request*. The right panel tracks the open request (Sent → Staff check the escrow statement → Float added).
  - **Send to bank:** from Float or Commission, to the agent's own bank, amount, PIN, no fee.
  - A *Recent float movements* list with Done / Credited / Sent / Waiting / Rejected badges.
- **A04 Commission:** no "How you earn" section and no "Move commission to float".
- **A05 Settings:** square-cornered cards.
- **A06 Transactions** (new menu item replacing *Withdrawals*): KPIs (Today, Cash-in today, Withdrawals today, Pending transactions); table with an **Account** column after Customer; search, type and date filters, paging; side drawer with the full phone and account number, amounts, *Copy reference*, *Download receipt*. For withdrawals the drawer has no timeline, note or "where the money moved".

## Definition of done

- Every screen above matches its design board at 1440 px and works at phone width and by keyboard.
- All money flows pass their tests, ledger journals balance, and the total of all wallets equals the escrow balance after each flow.
- Every message point in this scope is sent by `EmailNotificationSender` with the right template key, and switching to SMS later only means adding `SmsNotificationSender`.
- Bank top-ups and withdrawals show the matching line on the Plaid Sandbox account.
- Nothing from the "out of scope" list is reachable as a working feature.
- Summarise what you built, what you skipped and why, and any place the plan was unclear.
