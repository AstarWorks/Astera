-- Wave 5pve-survival / ADR-0107 §Persistence: Skyblock party-shared island record.
--
-- One row per [com.astarworks.astera.core.domain.survival.PartyIsland].
--
-- # Identity column shape
--
-- island_id is stored as VARCHAR (not UUID) because [IslandId] is a
-- vendor-neutral string-wrapper value class — production may use the
-- party id stringified, a human-authored handle, or a UUID-as-string;
-- the column accepts all three. Matches the same call made for
-- match_id in V38__betting_matches.sql.
--
-- # Status as VARCHAR (not enum)
--
-- Per ADR-0014 / V38 precedent: VARCHAR + CHECK constraint keeps the
-- on-disk shape forward-compatible with new IslandStatus variants
-- without an enum-add migration. The CHECK enumerates the current
-- sealed variant set (Active / Unloaded / Archived).
--
-- # Template id as VARCHAR
--
-- template_id mirrors IslandTemplateId's lower-kebab-case constraint.
-- The registry resolves this string at load time; an unknown id is a
-- LoadFailed at the use-case layer, not a DB constraint failure (the
-- template set is reload-able at runtime).
--
-- # Visibility split (kind + payload JSONB)
--
-- visibility_kind is the discriminator (PartyOnly / FriendsAllowed /
-- Public); visibility_payload is the JSONB blob carrying the friend
-- allow-list when the kind == FriendsAllowed. The split avoids a join
-- table while keeping the discriminator queryable for admin reports.
-- ADR-0014's hybrid rule of thumb fits exactly: kind queried often,
-- friend list scanned only when a visit is challenged.
CREATE TABLE party_islands (
    island_id            VARCHAR(64) PRIMARY KEY,
    party_id             VARCHAR(128) NOT NULL,
    world_id             VARCHAR(128) NOT NULL,
    template_id          VARCHAR(64) NOT NULL,
    max_members          INT NOT NULL CHECK (max_members BETWEEN 1 AND 10),
    visibility_kind      VARCHAR(32) NOT NULL,
    visibility_payload   JSONB,
    status               VARCHAR(16) NOT NULL,
    created_at           TIMESTAMP NOT NULL DEFAULT now(),
    last_active_at       TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT party_islands_status_valid CHECK (status IN ('Active', 'Unloaded', 'Archived')),
    CONSTRAINT party_islands_visibility_valid CHECK (
        visibility_kind IN ('PartyOnly', 'FriendsAllowed', 'Public')
    ),
    CONSTRAINT party_islands_friends_payload_iff CHECK (
        (visibility_kind = 'FriendsAllowed' AND visibility_payload IS NOT NULL) OR
        (visibility_kind <> 'FriendsAllowed')
    ),
    CONSTRAINT party_islands_active_after_create CHECK (last_active_at >= created_at)
);

-- # Indexes
--
-- One island per party (current foundation contract; relaxable later by
-- dropping the UNIQUE). Status index supports the leader-offline sweeper
-- + archival job.
CREATE UNIQUE INDEX idx_party_islands_party_unique
    ON party_islands (party_id);
CREATE INDEX idx_party_islands_status
    ON party_islands (status);
CREATE INDEX idx_party_islands_last_active
    ON party_islands (last_active_at);
