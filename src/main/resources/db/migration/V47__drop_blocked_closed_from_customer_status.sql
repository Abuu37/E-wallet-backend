-- V47: CustomerStatus no longer has BLOCKED/CLOSED (trimmed to PENDING, ACTIVE, SUSPENDED
-- to match AgentStatus's shape). This column has no DB check constraint, so nothing to
-- alter there - but any existing row still holding the old string values would fail to
-- deserialize into the enum (same class of bug as the UserStatus.PENDING incident),
-- so they're remapped forward to the closest remaining equivalent.
UPDATE customers SET status = 'SUSPENDED' WHERE status IN ('BLOCKED', 'CLOSED');
