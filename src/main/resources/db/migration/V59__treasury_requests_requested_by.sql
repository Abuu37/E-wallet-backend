ALTER TABLE treasury_requests ADD COLUMN requested_by UUID;
ALTER TABLE treasury_requests ADD CONSTRAINT fk_treasury_requests_requested_by FOREIGN KEY (requested_by) REFERENCES users(id);
