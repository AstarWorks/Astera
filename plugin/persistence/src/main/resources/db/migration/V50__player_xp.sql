-- ADR-0187: lifetime XP total per player behind ILevelService (level is derived from it).
CREATE TABLE player_xp (
    player_id   UUID PRIMARY KEY,
    total_xp    BIGINT NOT NULL DEFAULT 0 CHECK (total_xp >= 0),
    updated_at  TIMESTAMP NOT NULL DEFAULT now()
);
