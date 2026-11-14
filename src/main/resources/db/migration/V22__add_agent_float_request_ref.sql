-- V22: Human-readable reference code for a float request, independent of its UUID id.

ALTER TABLE agent_float_requests ADD COLUMN request_ref VARCHAR(20);

UPDATE agent_float_requests
SET request_ref = 'REQ-' || UPPER(SUBSTRING(REPLACE(id::text, '-', ''), 1, 8))
WHERE request_ref IS NULL;

ALTER TABLE agent_float_requests ALTER COLUMN request_ref SET NOT NULL;
ALTER TABLE agent_float_requests ADD CONSTRAINT uk_agent_float_requests_request_ref UNIQUE (request_ref);
