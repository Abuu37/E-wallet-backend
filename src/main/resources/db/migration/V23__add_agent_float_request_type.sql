-- V23: Distinguish TOPUP vs WITHDRAWAL on agent_float_requests, so a direct
-- admin top-up/withdraw can be recorded in the same audit trail as an
-- agent-initiated request. Existing rows are all agent-initiated top-up
-- requests, so they backfill to TOPUP.

ALTER TABLE agent_float_requests ADD COLUMN type VARCHAR(20);

UPDATE agent_float_requests SET type = 'TOPUP' WHERE type IS NULL;

ALTER TABLE agent_float_requests ALTER COLUMN type SET NOT NULL;
ALTER TABLE agent_float_requests ADD CONSTRAINT chk_agent_float_requests_type CHECK (type IN ('TOPUP', 'WITHDRAWAL'));
