
INSERT INTO tariffs (id, transaction_type, min_amount, max_amount, fee, agent_commission, active)
VALUES
    (gen_random_uuid(), 'CASH_IN',  1000.00, 100000000.00,   0.00, 200.00, true),
    (gen_random_uuid(), 'CASH_OUT', 1000.00, 100000000.00, 300.00, 200.00, true);