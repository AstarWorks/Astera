-- Wave _persist-1 / ADR-0068: pre-match party grouping with members.
--
-- Players group up in the lobby and queue/spawn together. The Wave 5e
-- in-memory contract had parties disband on server restart; persistence
-- promotes the aggregate to "survives restart and cross-server hop" so
-- the multi-server traversal pattern (PlayerSession, V11) preserves
-- party membership too. Disbands still happen explicitly via remove().
--
-- Two-table layout (see ADR-0014):
--   * parties        — party-level metadata (leader, capacity)
--   * party_members  — one row per (party, member); indexed by member_id
--                      so forPlayer(playerId) is an O(log N) lookup instead
--                      of scanning every JSONB roster.
--
-- party_id is VARCHAR(64) because PartyId is a String-backed value class
-- (currently a stringified UUID at 36 chars, with margin for future
-- schemes). leader_id / member_id are UUID, matching PlayerId.uuid.
--
-- The FK on party_members(party_id) has ON DELETE CASCADE so PartyRepository
-- can issue a single DELETE FROM parties and let the DB clean up the roster
-- — the repository relies on this for remove(id).

CREATE TABLE parties (
    party_id   VARCHAR(64) PRIMARY KEY,
    leader_id  UUID NOT NULL,
    max_size   INT NOT NULL CHECK (max_size >= 1)
);

CREATE TABLE party_members (
    party_id   VARCHAR(64) NOT NULL REFERENCES parties(party_id) ON DELETE CASCADE,
    member_id  UUID NOT NULL,
    PRIMARY KEY (party_id, member_id)
);

CREATE INDEX idx_party_members_member ON party_members (member_id);
