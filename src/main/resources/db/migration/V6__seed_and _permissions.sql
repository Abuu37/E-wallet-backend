
/* ========= SEED ROLES ============*/
INSERT INTO roles (id, name, description, created_at, updated_at)
VALUES
    (gen_random_uuid(), 'ADMIN', 'System administrator', NOW(), NOW()),
    (gen_random_uuid(), 'CASHIER', 'Cashier responsible for customer TopUp and Withdraw operations', NOW(), NOW()),
    (gen_random_uuid(), 'CUSTOMER', 'E-wallet customer', NOW(), NOW());

/* =========== SEED PERMISSION =========*/
INSERT INTO permissions (id, name, description, created_at, updated_at)
VALUES
    -- User and Authentications
    (gen_random_uuid(), 'USER_CREATE', 'Create users', NOW(), NOW()),
    (gen_random_uuid(), 'USER_VIEW', 'View users', NOW(), NOW()),
    (gen_random_uuid(), 'USER_UPDATE', 'Update users', NOW(), NOW()),

-- Customer and balance Management
    (gen_random_uuid(), 'CHECK_BALANCE', 'View customer balance', NOW(), NOW()),
    (gen_random_uuid(), 'CUSTOMER_VIEW', 'View customer information', NOW(), NOW()),
    (gen_random_uuid(), 'CUSTOMER_UPDATE', 'Update customer information', NOW(), NOW()),

-- Wallet and Transfers (W2W, W2B)
    (gen_random_uuid(), 'WALLET_VIEW', 'View wallet', NOW(), NOW()),
    (gen_random_uuid(), 'COMPANY_WALLET_VIEW', 'View wallet', NOW(), NOW()),
    (gen_random_uuid(), 'WALLET_TO_WALLET_TRANSFER', 'Transfer money between wallets', NOW(), NOW()),
    (gen_random_uuid(), 'WALLET_TO_BANK_TRANSFER', 'Transfer money between wallets and bank', NOW(), NOW()),

-- Transactions
    (gen_random_uuid(), 'TRANSACTION_VIEW', 'View transactions history and receipts', NOW(), NOW()),

-- Cash-In / TopUp
    (gen_random_uuid(), 'TOPUP_CREATE', 'Create wallet top up / cash-in transaction', NOW(), NOW()),
    (gen_random_uuid(), 'TOPUP_VIEW', 'View top-up transactions', NOW(), NOW()),

-- Cashout / Withdrawal
    (gen_random_uuid(), 'WITHDRAWAL_CREATE', 'Create withdrawal / cash-out transaction', NOW(), NOW()),

-- Cashier and Float Management
    (gen_random_uuid(), 'CASHIER_VIEW', 'View cashier information', NOW(), NOW()),
    (gen_random_uuid(), 'FLOAT_VIEW', 'View cashier float balance and drawer status', NOW(), NOW()),
    (gen_random_uuid(), 'REQUEST_FLOAT', 'View cashier float balance and drawer status', NOW(), NOW()),
    (gen_random_uuid(), 'FLOAT_MANAGE', 'Manage and approve cashier float top-ups', NOW(), NOW()),

-- Loan ( Application, Repayment, Disbursement )
    (gen_random_uuid(), 'LOAN_VIEW', 'View loans and loan status', NOW(), NOW()),
    (gen_random_uuid(), 'LOAN_APPLY', 'Apply for a loan', NOW(), NOW()),
    (gen_random_uuid(), 'LOAN_APPROVE', 'Approve and disburse loans', NOW(), NOW()),
    (gen_random_uuid(), 'LOAN_REPAY', 'Repay a loan', NOW(), NOW()),

-- Financial, Ledger and  Reconciliation
    (gen_random_uuid(), 'RECONCILIATION_VIEW', 'View reconciliation report and shift summaries', NOW(), NOW()),
    (gen_random_uuid(), 'RECONCILIATION_MANAGE', 'Manage reconciliation and resolve discrepancies', NOW(), NOW()),
    (gen_random_uuid(), 'FINANCIAL_REPORT_VIEW', 'View financial reports ', NOW(), NOW()),
    (gen_random_uuid(), 'LEDGER_VIEW', 'View LEDGER reports ', NOW(), NOW()),

-- Roles and Access Control
    (gen_random_uuid(), 'ROLE_VIEW', 'View roles and Permission', NOW(), NOW()),
    (gen_random_uuid(), 'ROLE_ASSIGN', 'Assign roles to users', NOW(), NOW()),

-- Charges and Fees
    (gen_random_uuid(), 'CHARGE_VIEW', 'View charges and fee models', NOW(), NOW()),
    (gen_random_uuid(), 'CHARGE_MANAGE', 'Manage charges', NOW(), NOW()),
    (gen_random_uuid(), 'FEES_MANAGE', 'Manage fees', NOW(), NOW()),

-- System Administration
    (gen_random_uuid(), 'SYSTEM_CONFIG', 'Manage system configuration and environment settings', NOW(), NOW());

/* ============= ASSIGNED PERMISSION TO THE ROLES ==========*/

--CUSTOMER PERMISSIONS
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'CUSTOMER'
AND p.name IN (
    'CUSTOMER_VIEW',
    'CUSTOMER_UPDATE',
    'WALLET_VIEW',
    'WALLET_TO_WALLET_TRANSFER',
    'WALLET_TO_BANK_TRANSFER',
    'TRANSACTION_VIEW',
    'LOAN_VIEW',
    'LOAN_APPLY',
    'LOAN_REPAY'
    );


-- CASHIER permissions --
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
         CROSS JOIN permissions p
WHERE r.name = 'CASHIER'
  AND p.name IN (
                 'CASHIER_VIEW',
                 'WALLET_VIEW',
                 'TRANSACTION_VIEW',
                 'REQUEST_FLOAT',
                 'TOPUP_CREATE',
                 'TOPUP_VIEW',
                 'WITHDRAWAL_CREATE',
                 'FLOAT_VIEW'
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