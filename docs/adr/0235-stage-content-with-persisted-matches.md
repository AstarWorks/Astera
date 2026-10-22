---
status: Accepted
date: 2026-10-23
deciders: Codex bug hunt (existing content ownership restored)
supersedes: null
superseded_by: null
---

# ADR-0235: Stage content remains authoritative with persisted matches

## Context

`IStageRegistry` is a read-only content registry populated and reloaded by Match's
`StageContents` (ADR-0060/0065). Persistence nevertheless registered a priority-100
`PostgresStageRegistry` during onLoad. No production writer populated its table.
Fresh databases therefore hid every YAML stage, and manually populated rows also
lost world, spawn points, named regions and pillars on decoding. Removing just the
overlay still left `matches.stage_id` requiring a duplicate row in `stages`.

## Decision

Persistence does not overlay `IStageRegistry`. Delete its unused Postgres adapter.
Match wiring resolves its existing YAML registry on every backend. V66 removes the
legacy foreign key from `matches.stage_id`; keep the stage identifier on historical
match records even when the corresponding content is later removed. Existing
migrations and historical stage rows remain unchanged.

## Consequences

Fresh Postgres servers use the same stages, worlds and spawns as in-memory servers;
stage reloads reach live consumers. The content registry validates stage selection
when starting a match. Historical match rows can reference retired content, so the
DB does not enforce current content membership.

## Alternatives considered

Mirroring complete stage content into Postgres adds another synchronization and
reload path without making immutable authored content more authoritative.

## Verification

`PostgresContentStageMatchIT` persists a match with no duplicate stage row.
Existing game-sim match/spawn scenarios exercise YAML-backed stage selection.
Local test execution is prohibited for this hunt; GitHub Actions must run them.

## References

ADR-0060, ADR-0065, ADR-0195; `IStageRegistry` content contract.
