-- Permission to read the audit log (module SYSTEM), granted to ADMIN.
INSERT INTO permissions (id, name, description, module_reference, created_at, updated_at)
VALUES (gen_random_uuid(), 'AUDIT_VIEW', 'View the audit log of staff actions',
        (SELECT id FROM modules WHERE name = 'SYSTEM'), NOW(), NOW());

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.name = 'ADMIN' AND p.name = 'AUDIT_VIEW';
