ALTER TABLE users
    ADD COLUMN reference UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    ADD COLUMN username VARCHAR(100),
    ADD COLUMN last_login_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN role_id UUID;

-- Correction, found during execution: `users` is NOT empty on the freshly recreated database —
-- V26 (already-existing, replayed as part of rebuilding today's shape) seeds one real row: the
-- system/treasury user (id '11111111-1111-1111-1111-111111111111', BLOCKED, null pin_hash,
-- can never log in) that owns the company-level wallets. It needs a real username and role_id
-- before either column can be locked down. None of ADMIN/CASHIER/CUSTOMER/AGENT semantically fit
-- a non-interactive internal principal, so give it its own role rather than forcing a wrong one:
INSERT INTO roles (id, name, description, created_at, updated_at)
VALUES (gen_random_uuid(), 'SYSTEM', 'Internal, non-interactive system principal — cannot log in', NOW(), NOW());

UPDATE users
SET username = 'system_treasury',
    role_id = (SELECT id FROM roles WHERE name = 'SYSTEM')
WHERE id = '11111111-1111-1111-1111-111111111111';

-- Now safe: that was the only existing row, and it's populated.
ALTER TABLE users ALTER COLUMN username SET NOT NULL;
ALTER TABLE users ADD CONSTRAINT uk_users_username UNIQUE (username);
ALTER TABLE users ALTER COLUMN role_id SET NOT NULL;
ALTER TABLE users ADD CONSTRAINT fk_users_role FOREIGN KEY (role_id) REFERENCES roles(id);

DROP TABLE user_roles;

ALTER TABLE users
    DROP COLUMN first_name,
    DROP COLUMN middle_name,
    DROP COLUMN last_name;
