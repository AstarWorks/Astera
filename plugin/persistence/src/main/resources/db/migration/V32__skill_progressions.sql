-- Wave 5multi-skill (ADR-0101): multi-axis skill progression.
-- One row per (player_id, skill_kind) tuple; each axis carries its own
-- cumulative XP + derived level. The derived level is stored alongside the
-- XP so common read paths (HUD, leaderboards) don't re-walk the curve.
--
-- skill_kind values are SkillKind.token strings (kebab-/snake-case lower).
-- Reordering / renaming SkillKind.token is a breaking change that requires
-- a follow-up migration; ADR-0101 §"Wire format" calls this out explicitly.
--
-- ADR-0020 priority-100 overlay: PostgresSkillProgressionRepository is
-- registered by AsteraPersistencePlugin and overrides the in-memory default
-- registered by AsteraStatsPlugin at priority 0.

CREATE TABLE skill_progressions (
    player_id   UUID NOT NULL,
    skill_kind  VARCHAR(32) NOT NULL,
    xp          BIGINT NOT NULL DEFAULT 0,
    level       INT NOT NULL DEFAULT 1,
    updated_at  TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (player_id, skill_kind),
    CHECK (xp >= 0),
    CHECK (level >= 1)
);

CREATE INDEX idx_skill_progressions_player ON skill_progressions(player_id);
CREATE INDEX idx_skill_progressions_kind_level ON skill_progressions(skill_kind, level DESC);
