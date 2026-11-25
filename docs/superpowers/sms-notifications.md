# WalletPesa: where SMS is needed

**26 Nov: send these by email for now** (the system already sends email); SMS comes later.
Every point below calls one `NotificationSender` interface with its template key. Today's implementation
sends email; an SMS implementation replaces it when the provider is integrated, with no change to callers.
The keys and wording stay the same; on screen say "email" until SMS is live.

Rules for every SMS:
- Never include a PIN or a full account number (mask: ••4421, 0712 ••• 678).
- Codes are 6 digits, expire in 5 minutes, max 5 tries.
- Amounts in TZS with thousands separators; always end with the new balance where there is one.
- Include the transaction reference so support can find it.

## 1. Sign-up, sign-in and security

| # | Event | To | Example message |
|---|---|---|---|
| S1 | Registration: verify phone | Customer | WalletPesa code: 482913. Expires in 5 min. Don't share it. |
| S2 | Forgot PIN: reset code | Customer (agents and staff get S4/S5 after an admin reset) | WalletPesa PIN reset code: 482913. Expires in 5 min. |
| S3 | PIN changed / reset done | Any user | Your WalletPesa PIN was changed. All devices were signed out. Not you? Call 0800 000 000. |
| S4 | Agent onboarded: one-time PIN | Agent | Welcome to WalletPesa, Mrema Shop (AG-10234). One-time PIN: 7391. Sign in and set your own PIN. |
| S5 | Staff added: one-time PIN | Staff | You were added to WalletPesa staff. One-time PIN: 7391. Sign in and set your own PIN. |
| S6 | Identity verified, tier raised | Customer | Your identity is verified. New limits: 3,000,000 per payment, 5,000,000 per day. |
| S7 | Identity check failed / needs review | Customer | We couldn't verify your ID. Open the app to try again. |
| S8 | Account frozen / unfrozen by staff | Customer or agent | Your WalletPesa account is on hold. Call 0800 000 000. |
| S9 | Too many wrong PINs, account locked | Any user | Too many wrong PINs. Your account is locked for 30 min. |

## 2. Customer money movements

| # | Event | To | Example message |
|---|---|---|---|
| C1 | Add money from bank | Customer | You added TZS 50,000 from CRDB ••4421. Ref BTU-1042. New balance 50,000. |
| C2 | Cash-in at agent | Customer | You received TZS 50,000 cash-in from Mrema Shop (AG-10234). Ref CIN-2207. New balance 100,000. |
| C3 | Send wallet to wallet | Sender | You sent TZS 20,000 to Juma Ali 0754 ••• 210. Ref SND-3301. New balance 80,000. |
| C4 | Receive wallet to wallet | Receiver | You received TZS 20,000 from Amina Said 0712 ••• 678. Ref SND-3301. New balance 45,000. |
| C5 | Withdraw at agent | Customer | You withdrew TZS 30,000 at Mrema Shop (till 220 341). Fee 300. Code 4821. Ref WDR-5120. New balance 49,700. |
| C6 | Withdraw to own bank: sent | Customer | TZS 30,000 is on its way to CRDB ••4421. Fee 1,000. Ref BWD-6011. New balance 18,700. |
| C7 | Withdraw to own bank: failed, refunded | Customer | Your transfer to CRDB ••4421 failed. TZS 31,000 is back in your wallet. Ref BWD-6011. |

## 3. Agent money movements

| # | Event | To | Example message |
|---|---|---|---|
| A1 | Cash-in done | Agent | Cash-in TZS 50,000 to Amina Said. Ref CIN-2207. Commission +200. Float 250,000. |
| A2 | Customer withdrawal done | Agent | Withdrawal TZS 30,000 by Amina Said. Code 4821. Hand over cash. Commission +200. Float 280,000. |
| A3 | Float top-up from own bank (Path A) | Agent | Float topped up TZS 2,000,000 from NMB ••7710. Ref FTU-0881. Float 2,300,000. |
| A4 | Float request received (Path B) | Agent | We got your float request of TZS 3,000,000 (Ref FRQ-0412). We'll SMS you once it's checked. |
| A5 | Float request approved | Agent | Float request FRQ-0412 approved. TZS 3,000,000 added. Float 3,300,000. |
| A6 | Float request rejected | Agent | Float request FRQ-0412 was not approved: deposit not found. Call 0800 000 000. |
| A7 | Withdraw float to own bank | Agent | TZS 1,000,000 sent from float to NMB ••7710. Ref FWD-0155. Float 2,300,000. |
| A8 | Withdraw commission to own bank | Agent | Commission TZS 45,600 sent to NMB ••7710. Ref CWD-0098. |
| A9 | Low float warning (optional) | Agent | Your float is TZS 40,000. Top up to keep serving customers. |

## 4. Staff

Staff work in the console, so most staff alerts stay in the console. SMS only for:

| # | Event | To | Example message |
|---|---|---|---|
| T1 | One-time PIN (same as S5) | New staff, or after an admin PIN reset | see S5 |
| T2 | Reconciliation mismatch (optional) | Finance staff on duty | Reconciliation alert: wallets and escrow differ by TZS 120,000 for 25 Nov. Check the console. |

## Later (with the parked items in later.md)
- Send to other networks (MNOs): sent / received / failed.
- Send to someone else's bank: sent / failed.
- Super-agents selling float: buyer and seller SMS.
