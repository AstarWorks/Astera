-- Wave _persist-1 / ADR-0068: directed friendship records, canonical-pair storage.
--
-- IFriendshipRepository's port doc states the pair `(A, B)` is unordered —
-- `find(A, B)` must return the same row as `find(B, A)`. Storing both
-- directions would double-write and require dedup on read; instead each pair
-- is normalised to `(player_low, player_high)` with
-- `player_low.compareTo(player_high) < 0`. Queries against `(B, A)`
-- canonicalise to `(A, B)` in the repository before hitting the table.
--
-- The `CHECK (player_low < player_high)` constraint backstops the
-- canonical-pair invariant at the DB layer so any future direct SQL writer
-- (admin tooling, future replication overlay) cannot accidentally insert a
-- row in the wrong order or a self-friendship (A, A).
--
-- `status` is a `VARCHAR(16)` enum-name column rather than a Postgres ENUM
-- type so new FriendshipStatus values (today: PENDING / ACCEPTED / BLOCKED /
-- DECLINED) can be added by an application-only release without an
-- `ALTER TYPE`.
CREATE TABLE friendships (
    player_low   UUID NOT NULL,
    player_high  UUID NOT NULL,
    status       VARCHAR(16) NOT NULL,
    created_at   TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (player_low, player_high),
    CHECK (player_low < player_high)
);

-- The PK already provides an index on (player_low, player_high), which covers
-- the `player_low = ?` half of `forPlayer(playerId)`. Add a secondary index on
-- `player_high` so the OR's other half is also index-scanned rather than seq-
-- scanning the table. `forPlayer` lookups happen on every social-panel open,
-- so two index probes + a small UNION is the right trade vs a scan.
CREATE INDEX idx_friendships_player_high ON friendships (player_high);
