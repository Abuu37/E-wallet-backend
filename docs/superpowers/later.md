# WalletPesa: parked for later

Items agreed on 25 Nov 2026 to build in a later phase. Do not drop them.

## 1. Send to other mobile networks (MNOs)
- Send from a WalletPesa wallet to M-Pesa, Mixx by Yas, Airtel Money and HaloPesa by phone number, and receive from them.
- Real life: goes through TIPS (Bank of Tanzania instant payment switch).
- Ledger: Customer main → Escrow payout (TIPS) + Revenue fee. Escrow goes down on send and up on receive.
- Demo: Plaid can't play this. Use a "TIPS simulator" partner, labelled Demo.
- For now, when the number isn't a WalletPesa user, the send screen says: "This number isn't on WalletPesa yet. Sending to other networks is coming soon."

## 2. SMS provider
- Every SMS point is listed in `sms-notifications.md`. Plug the real provider into the one `SmsSender` interface.

## 3. Super-agents (proposed, not yet agreed)
- A big agent sells float to smaller agents: Agent float → Agent float. Escrow doesn't change.

## 4. Send to someone else's bank account (option B)
- Pay any bank account (for example a landlord's CRDB account) by typing the bank, account number and name. No Plaid link.
- Real life: goes through TIPS, like the MNOs.
- Demo: Plaid can't show the recipient's side; use the same TIPS simulator.
- For now (option A), customers withdraw or send only to their **own** bank, linked through Plaid, with the name matched to their verified identity.

## 5. Bills and airtime (later addition, 26 Nov)
- Bills: electricity/LUKU tokens, water, TV, government bills (GePG), through a bill aggregator.
- Airtime: Vodacom, Airtel, Yas, Halotel, usually through an aggregator.
- Ledger: Customer main → Escrow payout (to the biller or network) + Revenue fee. Escrow goes down.
- Not TIPS: they use their own partners. Demo: "Biller simulator" and "Airtime simulator" behind one `BillPaymentProvider` interface.
- Designs exist already (Phase 4 boards); no endpoints yet.
