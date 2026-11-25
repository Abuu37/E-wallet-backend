# WalletPesa: agreed money movement (discussion, 25 Nov 2026)

Not yet written into the plan or the design. This is the record of what was agreed.

**26 Nov:** Company master wallet and mint removed; notifications go by email for now (SMS later); agents and staff reset a forgotten PIN through an admin.

**Rule:** every shilling in a wallet is backed by a real shilling in the escrow (trust) bank account.
Daily check: total of all wallets = escrow balance.
Plaid plays "the bank side" in the demo: identity, balance, and Sandbox debit/credit lines via `/sandbox/transactions/create`.

## Customer
| Movement | Ledger | Escrow | Plaid shows | Approval | Fee |
|---|---|---|---|---|---|
| Add money from own bank | Escrow → Customer main | ↑ | −amount | none | 0 |
| Cash-in at agent | Agent float → Customer main | – | nothing | none | 0 |
| Send wallet to wallet (phone) | Customer main → Recipient main | – | nothing | none | per tariff |
| Withdraw at agent | Customer main → Agent float + Revenue | – | nothing | none | 300 |
| Withdraw to own bank (name must match) | Customer main → Escrow payout + Revenue | ↓ | +amount | none | 1,000 |

Withdraw to bank shows "Processing" then "Completed". If the bank rejects it, the full amount and the fee go back to the wallet automatically.

## Agent (no fees)
| Movement | Ledger | Escrow | Plaid shows | Approval |
|---|---|---|---|---|
| Cash-in / customer withdrawal | see Customer, plus 200 commission each | – | nothing | none |
| Float top-up from own bank (Path A) | Escrow → Agent float | ↑ | −amount | none, cap **5,000,000 TZS/day** |
| Request float: deposit to escrow with reference = agent number, upload slip (Path B) | Escrow → Agent float | ↑ | nothing | one staff member |
| Withdraw float to own bank | Agent float → Escrow payout | ↓ | +amount | none, within limits |
| Withdraw commission to own bank | Commission → Escrow payout | ↓ | +amount | none |

Path A: WalletPesa pulls the money, so the agent never types the escrow account number.
Path B: the screen shows the escrow bank, account name, account number and reference AG-xxxxx.

## Staff and system
| Movement | Ledger | Escrow | Approval |
|---|---|---|---|
| Approve float request | Escrow → Agent float | ↑ | one staff member |
| Refill commission pool | Revenue → Commission pool | – | one finance staff member |
| Sweep revenue to company bank | Revenue → Escrow payout | ↓ | two staff members |
| Reverse a wrong transaction (new opposite entry) | e.g. Recipient → Sender | – | two staff members |
| Treasury adjustment (corrections only) | Escrow ↔ Revenue | depends | two staff members |
| Daily reconciliation | no movement | – | alert on mismatch |

Fees go to Revenue automatically. Commissions go from the pool to the agent automatically.
The pool is refilled by finance (option A). The staff Overview shows a "pool low" warning.
Proposed (to confirm): if the pool is empty, the transaction still goes through, the commission is recorded as owed, and it is paid automatically after the next refill.

See also: `sms-notifications.md`, `later.md`.
