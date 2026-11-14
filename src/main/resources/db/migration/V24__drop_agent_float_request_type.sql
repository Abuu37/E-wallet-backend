-- V24: Revert V23 — the TOPUP/WITHDRAWAL type distinction was added to
-- support recording direct admin top-ups/withdrawals in the same table as
-- agent-initiated requests; that unification was undone, so this table goes
-- back to only ever representing agent-initiated top-up requests.

ALTER TABLE agent_float_requests DROP CONSTRAINT chk_agent_float_requests_type;
ALTER TABLE agent_float_requests DROP COLUMN type;
