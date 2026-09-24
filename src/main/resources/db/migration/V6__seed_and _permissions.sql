
/* ========= SEED ROLES ============*/
INSERT INTO roles (id, name, description, created_at, updated_at)
VALUES
    (gen_random_uuid(), 'ADMIN', 'System administrator', NOW(), NOW()),
    (gen_random_uuid(), 'CASHIER', 'Cashier responsible for customer cash operations', NOW(), NOW()),
    (gen_random_uuid(), 'FINANCIAL', 'Financial operations user', NOW(), NOW()),
    (gen_random_uuid(), 'CUSTOMER', 'E-wallet customer', NOW(), NOW());

/* =========== SEED PERMISSION =========*/
INSERT INTO permissions (id, name, description, created_at, updated_at)
VALUES
    -- User
    (gen_random_uuid(), 'USER_CREATE', 'Create users', NOW(), NOW()),
    (gen_random_uuid(), 'USER_VIEW', 'View users', NOW(), NOW()),
    (gen_random_uuid(), 'USER_UPDATE', 'Update users', NOW(), NOW()),

-- Customer
    (gen_random_uuid(), 'CUSTOMER_VIEW', 'View customer information', NOW(), NOW()),
    (gen_random_uuid(), 'CUSTOMER_UPDATE', 'Update customer information', NOW(), NOW()),

-- Wallet
    (gen_random_uuid(), 'WALLET_VIEW', 'View wallet', NOW(), NOW()),
    (gen_random_uuid(), 'WALLET_TRANSFER', 'Transfer money between wallets', NOW(), NOW()),

-- Transactions
    (gen_random_uuid(), 'TRANSACTION_VIEW', 'View transactions', NOW(), NOW()),

-- TopUp
    (gen_random_uuid(), 'TOPUP_CREATE', 'Create wallet top up', NOW(), NOW()),
    (gen_random_uuid(), 'TOPUP_VIEW', 'View top up transactions', NOW(), NOW()),

-- Withdrawal
    (gen_random_uuid(), 'WITHDRAWAL_CREATE', 'Create withdrawal', NOW(), NOW()),

-- Cashier
    (gen_random_uuid(), 'FLOAT_VIEW', 'View cashier float', NOW(), NOW()),
    (gen_random_uuid(), 'FLOAT_MANAGE', 'Manage cashier float', NOW(), NOW()),

-- Loan
    (gen_random_uuid(), 'LOAN_VIEW', 'View loans', NOW(), NOW()),
    (gen_random_uuid(), 'LOAN_APPLY', 'Apply for a loan', NOW(), NOW()),
    (gen_random_uuid(), 'LOAN_APPROVE', 'Approve loans', NOW(), NOW()),
    (gen_random_uuid(), 'LOAN_REPAY', 'Repay a loan', NOW(), NOW()),

-- Financial
    (gen_random_uuid(), 'RECONCILIATION_VIEW', 'View reconciliation', NOW(), NOW()),
    (gen_random_uuid(), 'RECONCILIATION_MANAGE', 'Manage reconciliation', NOW(), NOW()),
    (gen_random_uuid(), 'FINANCIAL_REPORT_VIEW', 'View financial reports', NOW(), NOW()),

-- Roles
    (gen_random_uuid(), 'ROLE_VIEW', 'View roles', NOW(), NOW()),
    (gen_random_uuid(), 'ROLE_ASSIGN', 'Assign roles to users', NOW(), NOW()),

-- Charges
    (gen_random_uuid(), 'CHARGE_VIEW', 'View charges', NOW(), NOW()),
    (gen_random_uuid(), 'CHARGE_MANAGE', 'Manage charges', NOW(), NOW()),

-- System
    (gen_random_uuid(), 'SYSTEM_CONFIG', 'Manage system configuration', NOW(), NOW());

/* ============= ASSIGNED PERMISSION TO THE ROLES ==========*/

--CUSTOMER PERMISSIONS
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'CUSTOMER'
AND P.name IN (
    'CUSTOMER_VIEW',
    'CUSTOMER_UPDATE',
    'WALLET_VIEW',
    'WALLET_TRANSFER',
    'TRANSACTION_VIEW',
    'LOAN_VIEW',
    'LOAN_APPLY',
    'LOAN_REPAY'
    );


-- CASHIER permissions
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
         CROSS JOIN permissions p
WHERE r.name = 'CASHIER'
  AND p.name IN (
                 'CUSTOMER_VIEW',
                 'WALLET_VIEW',
                 'TRANSACTION_VIEW',
                 'TOPUP_CREATE',
                 'TOPUP_VIEW',
                 'WITHDRAWAL_CREATE',
                 'FLOAT_VIEW'
    );


-- FINANCIAL permissions
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
         CROSS JOIN permissions p
WHERE r.name = 'FINANCIAL'
  AND p.name IN (
                 'CUSTOMER_VIEW',
                 'WALLET_VIEW',
                 'TRANSACTION_VIEW',
                 'LOAN_VIEW',
                 'LOAN_APPROVE',
                 'LOAN_REPAY',
                 'RECONCILIATION_VIEW',
                 'RECONCILIATION_MANAGE',
                 'FINANCIAL_REPORT_VIEW',
                 'CHARGE_VIEW'
    );


-- ADMIN permissions
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
         CROSS JOIN permissions p
WHERE r.name = 'ADMIN'
  AND p.name IN (
                 'USER_CREATE',
                 'USER_VIEW',
                 'USER_UPDATE',
                 'CUSTOMER_VIEW',
                 'CUSTOMER_UPDATE',
                 'WALLET_VIEW',
                 'TRANSACTION_VIEW',
                 'TOPUP_VIEW',
                 'FLOAT_VIEW',
                 'FLOAT_MANAGE',
                 'LOAN_VIEW',
                 'LOAN_APPROVE',
                 'RECONCILIATION_VIEW',
                 'RECONCILIATION_MANAGE',
                 'FINANCIAL_REPORT_VIEW',
                 'ROLE_VIEW',
                 'ROLE_ASSIGN',
                 'CHARGE_VIEW',
                 'CHARGE_MANAGE',
                 'SYSTEM_CONFIG'
    );