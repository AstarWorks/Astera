-- Wave 5e / ADR-0058 §5: per-player unified attribute container.
-- Replaces V1 player_stats + V3 wallets at the application-layer read path
-- (those tables stay around but are no longer read after this migration;
-- drop deferred to Wave 5f or later).
--
-- Top-level JSONB schema (matches PlayerAttributesJson):
--   {
--     "values": {
--       "stat.kills":           { "type": "number", "value": 7 },
--       "wallet.star":          { "type": "number", "value": 1500 },
--       "resource.mana_current":{ "type": "number", "value": 50 },
--       ...
--     },
--     "modifiers": [
--       { "key": "resist.fire", "operation": "ADD", "value": 0.3, "sourceId": "armor:fire-chest" }
--     ]
--   }
--
-- Sealed discriminator (`type: number|flag|ref|duration`) mirrors
-- AttributeValue's variant set so AttributeValueJson round-trips. Backfill
-- below seeds only "number" variants (all V1 player_stats / V3 wallets columns
-- are numeric).
--
-- Backfill: single-pass INSERT...SELECT from V1 player_stats + V3 wallets.
-- Existing rows are preserved (no-op for new installs since both source
-- tables start empty there).

CREATE TABLE player_attributes (
    player_id   UUID PRIMARY KEY,
    payload     JSONB NOT NULL DEFAULT '{"values":{},"modifiers":[]}'::jsonb,
    updated_at  TIMESTAMP NOT NULL DEFAULT now()
);

INSERT INTO player_attributes (player_id, payload, updated_at)
SELECT
    ps.player_id,
    jsonb_build_object(
        'values',
        jsonb_build_object(
            'stat.kills',           jsonb_build_object('type', 'number', 'value', ps.kills),
            'stat.deaths',          jsonb_build_object('type', 'number', 'value', ps.deaths),
            'stat.assists',         jsonb_build_object('type', 'number', 'value', ps.assists),
            'stat.damage_dealt',    jsonb_build_object('type', 'number', 'value', ps.damage_dealt),
            'stat.damage_taken',    jsonb_build_object('type', 'number', 'value', ps.damage_taken),
            'stat.playtime_sec',    jsonb_build_object('type', 'number', 'value', ps.playtime_sec),
            'stat.matches_played',  jsonb_build_object('type', 'number', 'value', ps.matches_played),
            'stat.wins',            jsonb_build_object('type', 'number', 'value', ps.wins),
            'stat.losses',          jsonb_build_object('type', 'number', 'value', ps.losses)
        ) || COALESCE((
            SELECT jsonb_object_agg(
                'wallet.' || lower(w.currency),
                jsonb_build_object('type', 'number', 'value', w.balance)
            )
            FROM wallets w
            WHERE w.player_id = ps.player_id
        ), '{}'::jsonb),
        'modifiers', '[]'::jsonb
    ),
    now()
FROM player_stats ps
ON CONFLICT (player_id) DO NOTHING;
