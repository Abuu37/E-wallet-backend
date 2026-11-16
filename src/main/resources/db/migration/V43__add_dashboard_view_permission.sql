INSERT INTO permissions (id, name, description, module_reference, created_at, updated_at)
VALUES (gen_random_uuid(), 'DASHBOARD_VIEW', 'View the admin live dashboard overview',
        (SELECT id FROM modules WHERE name = 'FINANCE'), NOW(), NOW());

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.name = 'ADMIN' AND p.name = 'DASHBOARD_VIEW';
