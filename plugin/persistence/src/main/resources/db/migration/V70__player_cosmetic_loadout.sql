-- ECO-02: the cosmetic equipped in each slot (title / name_color / kill_effect). Ownership stays in player_unlocks.
CREATE TABLE player_cosmetic_loadout (
    player_id   UUID        NOT NULL,
    kind        TEXT        NOT NULL,
    cosmetic_id TEXT        NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (player_id, kind)
);
