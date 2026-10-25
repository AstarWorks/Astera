-- ADR-0271: stars are an in-match item and never persist. Currency.STAR is gone from the
-- enum, so every persisted STAR value is removed here (up-only; nothing parses it any more).

-- Wallet balances (attribute-container store + legacy wallets table).
UPDATE player_attributes
SET payload = jsonb_set(payload, '{values}', (payload -> 'values') - 'wallet.star' - 'lastmatch.star')
WHERE payload -> 'values' ?| ARRAY['wallet.star', 'lastmatch.star'];

DELETE FROM wallets WHERE currency = 'STAR';
DELETE FROM currency_issuance_log WHERE currency = 'STAR';

-- Currency.valueOf() runs on these on read, so STAR rows must not survive.
DELETE FROM audit_log WHERE data ->> 'currency' = 'STAR';
DELETE FROM bet_escrows WHERE currency = 'STAR';
DELETE FROM betting_matches WHERE stake_currency = 'STAR';
DELETE FROM marketplace_listings WHERE price_currency = 'STAR';
UPDATE reward_outbox
SET payload = jsonb_set(payload, '{currency}', (payload -> 'currency') - 'STAR')
WHERE payload -> 'currency' ? 'STAR';

-- Purchase history keeps its rows: the old STAR prices were re-denominated 1:1 into GOLD.
UPDATE shop_purchases SET currency = 'GOLD' WHERE currency = 'STAR';
