ALTER TABLE treasury_requests DROP CONSTRAINT chk_treasury_requests_request_type;
ALTER TABLE treasury_requests ADD CONSTRAINT chk_treasury_requests_request_type CHECK (request_type IN ('TOPUP', 'WITHDRAWAL', 'MINT', 'COMMISSION_TOPUP'));
ALTER TABLE treasury_requests ADD CONSTRAINT chk_treasury_requests_agent_for_float CHECK (request_type IN ('MINT', 'COMMISSION_TOPUP') OR agent_id IS NOT NULL);
ALTER TABLE treasury_requests ADD CONSTRAINT chk_treasury_requests_requester_for_staff_types CHECK (request_type NOT IN ('MINT', 'COMMISSION_TOPUP') OR requested_by IS NOT NULL);
