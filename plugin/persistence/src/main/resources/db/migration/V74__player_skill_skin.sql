-- engine-gaps-v2 2.3: skins a player owns per base skill, and which one is selected (at most one per player and skill).
CREATE TABLE player_skill_skin (
    player_id  UUID        NOT NULL,
    skill_id   TEXT        NOT NULL,
    skin_id    TEXT        NOT NULL,
    selected   BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (player_id, skill_id, skin_id)
);
CREATE UNIQUE INDEX player_skill_skin_one_selected ON player_skill_skin (player_id, skill_id) WHERE selected;
