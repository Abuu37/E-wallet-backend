-- V75: money moves that need two different staff members: sweep revenue to the company bank,
-- reverse a wrong transfer, correct escrow against revenue. One staff member asks, a different one approves.
CREATE TABLE treasury_operations (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    op_ref           VARCHAR(20) NOT NULL UNIQUE,
    op_type          VARCHAR(30) NOT NULL CHECK (op_type IN ('SWEEP', 'REVERSAL', 'ADJUSTMENT_PAY_IN', 'ADJUSTMENT_CHARGE')),
    amount           NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    reason           VARCHAR(500) NOT NULL,
    target_reference VARCHAR(60),
    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    requested_by     UUID NOT NULL REFERENCES users(id),
    decided_by       UUID REFERENCES users(id),
    decision_note    VARCHAR(500),
    result_reference VARCHAR(60),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at       TIMESTAMPTZ,
    CHECK (decided_by IS NULL OR decided_by <> requested_by)
);
CREATE INDEX idx_treasury_operations_status ON treasury_operations (status, created_at DESC);

INSERT INTO permissions (id, name, description, module_reference, created_at, updated_at)
VALUES (gen_random_uuid(), 'TREASURY_OPERATE', 'Ask for or approve a revenue sweep, a reversal or an escrow correction',
        (SELECT id FROM modules WHERE name = 'FINANCE'), NOW(), NOW());
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.name = 'ADMIN' AND p.name = 'TREASURY_OPERATE';
