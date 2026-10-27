-- ADR-0294: GEM (premium currency) is deleted. Currency.GEM is gone from the enum, so every
-- persisted GEM value is removed here (up-only; nothing parses it any more).

UPDATE player_attributes
SET payload = jsonb_set(payload, '{values}', (payload -> 'values') - 'wallet.gem' - 'lastmatch.gem')
WHERE payload -> 'values' ?| ARRAY['wallet.gem', 'lastmatch.gem'];

DELETE FROM wallets WHERE currency = 'GEM';
DELETE FROM currency_issuance_log WHERE currency = 'GEM';

-- Currency.valueOf() runs on these on read, so GEM rows must not survive.
DELETE FROM audit_log WHERE data ->> 'currency' = 'GEM';
DELETE FROM bet_escrows WHERE currency = 'GEM';
DELETE FROM betting_matches WHERE stake_currency = 'GEM';
DELETE FROM marketplace_listings WHERE price_currency = 'GEM';
UPDATE reward_outbox
SET payload = jsonb_set(payload, '{currency}', (payload -> 'currency') - 'GEM')
WHERE payload -> 'currency' ? 'GEM';
DELETE FROM shop_purchases WHERE currency = 'GEM';
