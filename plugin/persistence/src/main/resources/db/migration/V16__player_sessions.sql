-- Wave _persist-1: per-player session state.
-- One row per player; latest-write-wins. session JSONB carries SessionState
-- (Lobby/InMatch/Spectator/Dead/Disconnected/Loading) + currentMatch.
-- Cross-server traversal preserves player state via this table.
CREATE TABLE player_sessions (
    player_id   UUID PRIMARY KEY,
    session     JSONB NOT NULL,
    updated_at  TIMESTAMP NOT NULL DEFAULT now()
);
