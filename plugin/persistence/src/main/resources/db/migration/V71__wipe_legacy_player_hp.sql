-- Player max HP moved from the 20 scale to 100. The attribute container persists HP, so legacy rows would keep
-- max 20 / current 4..20 (shown as 20%). Drop both keys; the join sync re-seeds max 100 and a missing current is full.
UPDATE player_attributes
SET payload = jsonb_set(payload, '{values}', (payload -> 'values') - 'stat.max_hp' - 'stat.current_hp')
WHERE payload -> 'values' ?| ARRAY['stat.max_hp', 'stat.current_hp'];
