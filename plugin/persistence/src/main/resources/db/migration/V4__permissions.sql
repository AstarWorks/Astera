-- Wave 3 (R2) / ADR-0020 — permission persistence (worker δ).
-- role_definitions  : built-in roles seeded from DefaultRoles.builtin() on startup,
--                     plus content-provided roles. `permissions` is JSONB list of
--                     dot-separated keys, "*" wildcard supported.
-- role_assignments  : player_id -> role_id (composite PK, FK on role_id).

CREATE TABLE role_definitions (
    id            TEXT PRIMARY KEY,
    display_name  TEXT NOT NULL,
    permissions   JSONB NOT NULL DEFAULT '[]'::jsonb
);

CREATE TABLE role_assignments (
    player_id  UUID NOT NULL,
    role_id    TEXT NOT NULL REFERENCES role_definitions(id),
    granted_at TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (player_id, role_id)
);

CREATE INDEX idx_role_assignments_player ON role_assignments(player_id);
