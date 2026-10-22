-- V60: unlocked achievements per player (progression pass, ADR-0210).
-- Backs stats.IAchievementRepository unlocks; the catalog lives in content/achievements/.
-- The composite PK makes a repeated unlock a no-op (ON CONFLICT DO NOTHING).
CREATE TABLE player_achievements (
    player_id      UUID         NOT NULL,
    achievement_id VARCHAR(128) NOT NULL,
    unlocked_at    TIMESTAMP    NOT NULL DEFAULT now(),
    PRIMARY KEY (player_id, achievement_id)
);

CREATE INDEX idx_player_achievements_player ON player_achievements(player_id);
