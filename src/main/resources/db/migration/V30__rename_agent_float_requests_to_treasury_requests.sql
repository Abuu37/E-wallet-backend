ALTER TABLE agent_float_requests RENAME TO treasury_requests;
ALTER INDEX idx_agent_float_requests_agent_id RENAME TO idx_treasury_requests_agent_id;
ALTER INDEX idx_agent_float_requests_status RENAME TO idx_treasury_requests_status;
ALTER TABLE treasury_requests RENAME CONSTRAINT fk_agent_float_requests_agent TO fk_treasury_requests_agent;
ALTER TABLE treasury_requests RENAME CONSTRAINT fk_agent_float_requests_decided_by TO fk_treasury_requests_decided_by;
ALTER TABLE treasury_requests RENAME CONSTRAINT chk_agent_float_requests_status TO chk_treasury_requests_status;
ALTER TABLE treasury_requests RENAME CONSTRAINT chk_agent_float_requests_amount_positive TO chk_treasury_requests_amount_positive;
ALTER TABLE treasury_requests RENAME CONSTRAINT uk_agent_float_requests_request_ref TO uk_treasury_requests_request_ref;

ALTER TABLE treasury_requests ADD COLUMN request_type VARCHAR(20) NOT NULL DEFAULT 'TOPUP';
ALTER TABLE treasury_requests ADD CONSTRAINT chk_treasury_requests_request_type CHECK (request_type IN ('TOPUP', 'WITHDRAWAL'));
ALTER TABLE treasury_requests ALTER COLUMN request_type DROP DEFAULT;
