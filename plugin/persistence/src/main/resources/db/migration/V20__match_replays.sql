-- Wave 5v (Match recording capture) — committed RecordedReplay per match.
--
-- One row per match (latest-write-wins on `match_id`; replay_id is the
-- per-capture UUID stored alongside for forensic chains and so a future
-- migration to append-only history can flip the PK without a data-model
-- change).
--
-- `format`, `key_frames`, and `events` are JSONB blobs (ADR-0014) — the
-- domain `ReplayFormat` / `KeyFrame` / `ReplayEvent` types are mirrored by
-- `MatchReplayJson` DTOs in :plugin:persistence. JSONB beats per-event rows
-- because:
--   1. RecordedReplay is read whole (playback grabs the document, never
--      a single event in isolation).
--   2. Wave 5v size budget assumes ~5 MB per 4×1h capture — well below
--      the 1 GB JSONB row cap.
--   3. ReplayEvent variants iterate frequently (Phase 1 PoC may rename a
--      variant); JSONB tolerates additive change via the lenient decoder
--      shared by every other Astera JSONB column.
--
-- `size_bytes` mirrors the in-memory estimate from RecordedReplay (best-
-- effort, ±10%); kept columnar so admin queries can `SELECT match_id FROM
-- match_replays WHERE size_bytes > 10_000_000` without parsing JSONB.
--
-- `start_tick` / `end_tick` are kept columnar for the same reason and to
-- support time-range scans (`WHERE end_tick - start_tick > 24000`).
CREATE TABLE match_replays (
    match_id      VARCHAR(64)              NOT NULL,
    replay_id     UUID                     NOT NULL,
    start_tick    BIGINT                   NOT NULL,
    end_tick      BIGINT                   NOT NULL,
    size_bytes    INTEGER                  NOT NULL CHECK (size_bytes >= 0),
    format        JSONB                    NOT NULL,
    key_frames    JSONB                    NOT NULL,
    events        JSONB                    NOT NULL,
    saved_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    PRIMARY KEY (match_id)
);

-- Replay-id index supports findById without scanning. Sparse uniqueness
-- (each save mints a fresh ReplayId, so this index stays small).
CREATE UNIQUE INDEX idx_match_replays_replay_id ON match_replays (replay_id);

-- Time-range helper for the eventual `/astera-match replay list since=...`
-- admin command (Wave 5v ships export-by-match-id only; list lands in
-- Phase 4 with the playback UI).
CREATE INDEX idx_match_replays_saved_at ON match_replays (saved_at DESC);
