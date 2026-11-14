-- One-time cleanup, found during execution: this specific row is a dangling reference
-- from manual testing during the company-wallet feature's initial build (2026-11-08,
-- described "10M has be allocated for the agent") — its account_ref_id matches no
-- financial_accounts/wallets/agents/customers/users row at all, and there's no FK on
-- this column anywhere in the schema to have caught it. Confirmed it has zero effect on
-- any real balance (Step 3's backfill-sum check already passed without it).
--
-- Corrected during the final whole-branch review: deleting only this one leg leaves its
-- sibling COMPANY_UNALLOCATED/COMPANY_MASTER_ACCOUNT leg behind with no matching credit —
-- an unbalanced journal, confirmed live in the dev DB (10,000,000 DEBIT, 0 CREDIT) after
-- the original single-line DELETE ran. Delete the whole journal — both its remaining line
-- and its header — by exact journal id instead, so no one-legged entry survives. Still
-- surgical (exact id, not a pattern) — the Step 6 check below still hard-fails on any
-- other unresolvable row, exactly as before.
DELETE FROM transaction_journal_line WHERE journal_id = 'a012039e-57c0-4803-a6a1-f99f36e9b099';
DELETE FROM transaction_journal WHERE id = 'a012039e-57c0-4803-a6a1-f99f36e9b099';

ALTER TABLE transaction_journal_line ADD COLUMN wallet_id UUID;

-- AGENT_FLOAT-tagged lines pointed at financial_accounts.id.
UPDATE transaction_journal_line tjl
SET wallet_id = fa.wallet_id
FROM financial_accounts fa
WHERE tjl.ledger_account_type = 'AGENT_FLOAT' AND tjl.account_ref_id = fa.id;

-- ESCROW_BANK-tagged lines: redirect to the new, distinct ESCROW_BANK wallet.
UPDATE transaction_journal_line tjl
SET wallet_id = (SELECT id FROM wallets WHERE wallet_type = 'ESCROW_BANK')
WHERE tjl.ledger_account_type = 'ESCROW_BANK';

-- COMPANY_UNALLOCATED / COMPANY_DISTRIBUTED: account_ref_id already equals the
-- COMPANY_MASTER_ACCOUNT wallet's id (V26 step 5 reused it) — true zero-transform.
UPDATE transaction_journal_line tjl
SET wallet_id = tjl.account_ref_id
WHERE tjl.ledger_account_type IN ('COMPANY_UNALLOCATED', 'COMPANY_DISTRIBUTED')
  AND tjl.wallet_id IS NULL;

ALTER TABLE transaction_journal_line ALTER COLUMN wallet_id SET NOT NULL;
ALTER TABLE transaction_journal_line
    ADD CONSTRAINT fk_transaction_journal_line_wallet FOREIGN KEY (wallet_id) REFERENCES wallets (id);
ALTER TABLE transaction_journal_line DROP COLUMN ledger_account_type;
ALTER TABLE transaction_journal_line DROP COLUMN account_ref_id;
