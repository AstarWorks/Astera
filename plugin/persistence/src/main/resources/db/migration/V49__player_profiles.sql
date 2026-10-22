-- ADR-0195: durable first-time-onboarding fact behind IPlayerProfileService.
-- Without it a restart re-ran createFirstTime and re-granted the starter kit / wallet.
CREATE TABLE player_profiles (
    player_id        UUID PRIMARY KEY,
    display_name     TEXT NOT NULL,
    locale           TEXT,
    joined_at_epoch_ms BIGINT NOT NULL CHECK (joined_at_epoch_ms >= 0)
);
