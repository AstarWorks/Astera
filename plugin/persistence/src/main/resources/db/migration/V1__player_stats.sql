CREATE TABLE player_stats (
    player_id        UUID PRIMARY KEY,
    kills            BIGINT NOT NULL DEFAULT 0,
    deaths           BIGINT NOT NULL DEFAULT 0,
    assists          BIGINT NOT NULL DEFAULT 0,
    damage_dealt     BIGINT NOT NULL DEFAULT 0,
    damage_taken     BIGINT NOT NULL DEFAULT 0,
    playtime_sec     BIGINT NOT NULL DEFAULT 0,
    matches_played   BIGINT NOT NULL DEFAULT 0,
    wins             BIGINT NOT NULL DEFAULT 0,
    losses           BIGINT NOT NULL DEFAULT 0,
    updated_at       TIMESTAMP NOT NULL DEFAULT now()
);
