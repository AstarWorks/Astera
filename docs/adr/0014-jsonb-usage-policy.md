---
status: Accepted
date: 2026-05-17
deciders: ryuzu
---

# ADR-0014: JSONB usage policy — flexibility, not queryability

## Status

Accepted

## Context

[[adr/0005-postgres-not-mongo]] committed Astera to PostgreSQL (plus Exposed)
for persistence. PostgreSQL has first-class JSONB support, which raises a
recurring design question for every persisted aggregate:

> Should this field be a typed SQL column, or a JSONB blob inside a wider row?

RTM's MongoDB approach made everything a document with no schema enforcement.
Astera deliberately chose Postgres to recover schema, transactions, and
relational queries. But going pure-columns sacrifices the flexibility that
made document stores attractive in the first place: changing a half-structured
configuration without an `ALTER TABLE` migration.

JSONB is the middle ground. It needs a policy so it isn't abused.

## Decision

**Use JSONB only for flexibility. Use real columns for queryability.**

### Decision tree

For each field on a persisted aggregate, ask in order:

1. **Will the application `WHERE` / `JOIN` on this field, or aggregate it?**
   → Real column. Index it.
2. **Is this field part of the entity's identity or invariants?** (e.g. `playerId`,
   `currentMatchId`, `balance` for a wallet)
   → Real column. Constrain it with `NOT NULL` / `CHECK`.
3. **Is the shape stable, or will it grow with features?**
   - Stable → real column.
   - Will grow → JSONB.
4. **Is the field read-modify-write contention high?**
   → Real column (so PostgreSQL row-level locking is granular).
5. **Otherwise** — half-structured, append-mostly, feature-evolving — JSONB.

### Applied to Astera Phase 1-4 plans

| Persisted entity | Column fields | JSONB field |
|---|---|---|
| `players` | `id (UUID PK)`, `display_name TEXT NOT NULL`, `locale TEXT`, `joined_at TIMESTAMPTZ NOT NULL`, `current_match_id UUID NULL` | `extras JSONB` (settings, donation flags, skin selection, tutorial progress) |
| `matches` | `id (UUID PK)`, `phase TEXT NOT NULL`, `stage_id TEXT NOT NULL`, `started_at_tick BIGINT NOT NULL`, `ended_at_tick BIGINT NULL` | `score JSONB NOT NULL DEFAULT '{}'::jsonb` (per-team Map<TeamId, Int>) |
| `wallets` (1 row per (player, currency)) | `player_id`, `currency TEXT`, `balance BIGINT NOT NULL` | (none — balance is queried + aggregated) |
| `weapon_specs` (UGC marketplace, Phase 4) | `id (TEXT PK)`, `archetype TEXT NOT NULL`, `rarity TEXT NOT NULL`, `created_by UUID NULL`, `published_at TIMESTAMPTZ` | `spec JSONB NOT NULL` (the entire WeaponYamlConfig blob) |
| `ugc_listings` (Phase 4 marketplace) | `id`, `seller_id`, `price_currency`, `price_amount`, `status` | `metadata JSONB` (tags, screenshots URLs, custom attributes) |

### Indexing

- GIN index on JSONB only for keys that the application will query (`extras->'tutorial' = 'complete'`)
- Default: **no index on JSONB**. The columns next to it carry the queries.

### Constraint enforcement

- Real columns enforce types at the DB level.
- JSONB shape is enforced at the application layer by deserializing into the
  same `@Serializable` DTO that the YAML loader uses (the SSoT per [[adr/0016]]).
- A bad write that violates the DTO shape fails at the application boundary,
  not in user-visible errors at the next read.

## Consequences

### Positive

- Phase 4+ feature additions (new player settings, new wallet currency
  metadata, new UGC fields) land **without migrations** for the half-structured
  parts of each row.
- Schema migrations stay focused on the structural columns — invariants,
  foreign keys, queryable indexes.
- AstarManagement does similar Exposed + JSONB; teams can swap pattern fluency.

### Negative / Trade-offs

- JSONB content isn't visible in `SELECT *` casts or DB schema tooling without
  inspection.
- Querying inside JSONB (when occasionally needed) requires `->`, `->>`, or
  `jsonb_path_*` syntax — harder to read than column queries.
- Without DB-level constraint, a buggy application write can store
  invalid-shape JSONB. The DTO deserialization-on-read catches it, but the
  bad row sticks around until cleanup.

### Mitigations

- Every aggregate's JSONB is paired with its `@Serializable` DTO; the
  persistence adapter deserializes-on-read so application code never sees raw
  JSON.
- A `validate-jsonb` admin command (Phase 4) can re-deserialize every row and
  flag mismatches as part of regular maintenance.
- New JSONB fields require an ADR if the shape isn't already in the SSoT
  hierarchy. Don't add ad-hoc JSONB blobs.

## Alternatives Considered

- **Pure-column schema**: clean but requires migration for every feature. Phase
  4 UGC marketplace especially will iterate on `metadata` shape; column-only
  would be painful.
- **One JSONB per table** (`data JSONB NOT NULL`, all else minimal): collapses
  back to MongoDB-style. Loses the queryability we adopted Postgres *for*.
- **Per-aggregate side table for "extras"**: explicit join cost on every read;
  the JSONB approach avoids the join for the common path.

## References

- [[adr/0005-postgres-not-mongo]]
- [[adr/0016-content-schema-ssot]] (the DTO-as-SSoT principle that JSONB
  deserialization relies on)
- 計画書 §14.2
- PostgreSQL JSONB documentation: https://www.postgresql.org/docs/current/datatype-json.html
