-- Wave _persist-1 / ADR-0068: cross-server reward delivery outbox.
--
-- Backs PostgresRewardBridge — the durable replacement for Wave 5e's
-- in-memory InMemoryRewardBridge. Survives pod restarts so MatchEnded /
-- MissionCompleted rewards reach the player on next lobby login.
--
-- Classic outbox pattern:
--   enqueue(bundle)        -> INSERT row with delivered_at = NULL.
--   consumeForPlayer(p)    -> single transaction:
--                              SELECT WHERE player_id = ? AND
--                                     delivered_at IS NULL
--                                     ORDER BY created_at ASC
--                              UPDATE delivered_at = now() on each row.
--                            Atomic on commit; on retry, undelivered rows
--                            re-drain (at-least-once, idempotent on retry
--                            since RewardApplicationUseCase dedupes on
--                            RewardBundleId).
--   peek(p)                -> same SELECT, no UPDATE.
--
-- Phase 4 (Velocity proxy + Redis pub/sub) treats this table as the
-- durability layer and Redis as the cross-pod notify layer; both sides
-- read from reward_outbox, so this schema is forward-compatible with
-- the federation rollout. If the lockless SELECT becomes a contention
-- point at scale, swap the query for `SELECT … FOR UPDATE SKIP LOCKED`
-- (no schema change required).
--
-- Note: V14 is taken by player_attributes (Wave 5e). Wave _persist-1
-- carries V15 for the outbox to keep Flyway monotonic; orchestrator may
-- renumber at Phase C if the wave's other migrations are reshuffled.

CREATE TABLE reward_outbox (
    bundle_id    UUID PRIMARY KEY,
    player_id    UUID NOT NULL,
    payload      JSONB NOT NULL,
    created_at   TIMESTAMP NOT NULL,
    delivered_at TIMESTAMP NULL
);

-- Partial index for the hot path: "give me this player's undelivered rows
-- in producer order". Excludes delivered rows so the index stays tight as
-- the outbox accumulates; the column ordering (player_id, created_at)
-- matches the ORDER BY clause so Postgres can serve the query in index
-- order without an extra sort.
CREATE INDEX idx_reward_outbox_player_undelivered
    ON reward_outbox (player_id, created_at)
    WHERE delivered_at IS NULL;
