-- Stripe was removed; bank top-ups use Plaid. No TOPUP_STRIPE transactions exist, so its tariff band is dead.
DELETE FROM tariffs WHERE transaction_type = 'TOPUP_STRIPE';
