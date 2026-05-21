-- Wave 5h / ADR-0079 §4: server-wide moderation actions (Mute / Kick / Ban /
-- ChatRateLimit). One row per applied action; lift is in-place via the
-- `lifted_at` column (no separate `lifts` table). `kind` carries the variant
-- discriminator (matches `ModerationAction` sealed variants); `payload` JSONB
-- carries variant-specific fields (BanScope for Ban, ratePerSecond + burstSize
-- for ChatRateLimit) so additive variant fields don't require new migrations.
-- Variants without extra fields (Mute, Kick) write `'{}'::jsonb` to keep the
-- column NOT NULL — easier to query than a tri-state nullable JSONB.
--
-- `idx_mod_active` accelerates `IModerationService.activeFor(playerId)` —
-- the hot query is "active rows for this player", and the partial-active
-- predicate (`lifted_at IS NULL AND (expires_at IS NULL OR expires_at > now)`)
-- benefits from a composite ordered by the columns most often filtered on.
CREATE TABLE moderation_actions (
    id          UUID PRIMARY KEY,
    player_id   UUID NOT NULL,
    kind        VARCHAR(32) NOT NULL CHECK (kind IN ('mute','kick','ban','chat_rate_limit')),
    reason_key  VARCHAR(128) NOT NULL,
    issued_by   UUID NOT NULL,
    issued_at   TIMESTAMP NOT NULL DEFAULT now(),
    expires_at  TIMESTAMP,
    lifted_at   TIMESTAMP,
    payload     JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX idx_mod_active ON moderation_actions (player_id, lifted_at, expires_at);
