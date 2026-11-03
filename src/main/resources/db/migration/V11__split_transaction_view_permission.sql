/* ========= ADD GRANULAR TRANSACTION VIEW PERMISSIONS ============*/
INSERT INTO permissions (id, name, description, created_at, updated_at)
VALUES
    (gen_random_uuid(), 'TRANSACTION_VIEW_ALL', 'View transactions across all customers', NOW(), NOW()),
    (gen_random_uuid(), 'TRANSACTION_VIEW_OWN', 'View own transactions', NOW(), NOW()),
    (gen_random_uuid(), 'TRANSACTION_VIEW_DETAILS', 'View transaction details', NOW(), NOW());

/* ========= ASSIGN TO ROLES ============*/

-- ADMIN
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'ADMIN'
  AND p.name = 'TRANSACTION_VIEW_ALL';

-- FINANCIAL
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'FINANCIAL'
  AND p.name = 'TRANSACTION_VIEW_ALL';

-- CASHIER
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'CASHIER'
  AND p.name IN ('TRANSACTION_VIEW_OWN', 'TRANSACTION_VIEW_DETAILS');

-- CUSTOMER
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'CUSTOMER'
  AND p.name IN ('TRANSACTION_VIEW_OWN', 'TRANSACTION_VIEW_DETAILS');

/* ========= RETIRE THE OLD COARSE-GRAINED PERMISSION ============*/
-- role_permissions has ON DELETE CASCADE on permission_id, so this also
-- removes the old ADMIN/FINANCIAL/CASHIER/CUSTOMER grants of TRANSACTION_VIEW.
DELETE FROM permissions WHERE name = 'TRANSACTION_VIEW';
