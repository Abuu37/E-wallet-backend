/* ========= RENAME THE CASHIER ROLE/PERMISSION (seeded in V6) TO AGENT ============*/
-- V6 and V11 are already-applied migrations and must not be edited in place,
-- so the rename is done here as a data update instead.

UPDATE roles
SET name        = 'AGENT',
    description = 'Agent responsible for customer TopUp and Withdraw operations'
WHERE name = 'CASHIER';

UPDATE permissions
SET name        = 'AGENT_VIEW',
    description = 'View agent information'
WHERE name = 'CASHIER_VIEW';

UPDATE permissions
SET description = 'View agent float balance and drawer status'
WHERE name = 'FLOAT_VIEW';

UPDATE permissions
SET description = 'View agent float balance and drawer status'
WHERE name = 'REQUEST_FLOAT';

UPDATE permissions
SET description = 'Manage and approve agent float top-ups'
WHERE name = 'FLOAT_MANAGE';
