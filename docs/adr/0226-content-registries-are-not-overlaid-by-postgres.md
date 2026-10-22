---
status: Accepted
date: 2026-10-22
deciders: founder + Claude
supersedes: null
superseded_by: null
---

# ADR-0226: Content registries are not overlaid by Postgres; transfer notices cannot block the hop

## Status

Accepted. Amends [[adr/0195-persistence-overlay-registered-before-consumers]] and ADR-0065 (`PostgresStageRegistry`).

## Context

Live bots (ADR-0221) found the queue -> match loop dead on the real stack:

1. `StageNotFound(example-arena)`. Persistence registered `PostgresStageRegistry` for `IStageRegistry` at
   priority 100 in `onLoad` (ADR-0195), shadowing the YAML-backed `StageContents`. Nothing ever filled the
   `stages` table, so every lookup missed and `/astera admin stage list` showed 0.
2. `PaperPlayer.sendTitle` / `sendActionBar` were `TODO(...)` stubs. `TransferNoticeListener` ran inside
   `PlayerTransferRequested` publication, before `sendToServer`, so `NotImplementedError` aborted the
   BungeeCord `Connect` (and `LobbyReturn`).

## Decision

- Stages are content (`content/stages/*.yaml`, `StageContents`, reload via `/astera reload stages`).
  Persistence no longer overlays `IStageRegistry`; `PostgresStageRegistry`, `StagesTable`, `StageRegionsJson`
  are deleted and migration V66 drops `matches_stage_id_fkey` and the `stages` table. Postgres holds runtime
  state only. Rule: a port whose data is authored in `content/` is never overlaid by a Postgres table.
- Audit of the other Postgres overlays: achievements (catalog in memory via `registerAll`, only unlocks stored),
  permission roles (seeded from `DefaultRoles` by UPSERT at construction), shop stock / seasons / tournaments /
  ratings (runtime state) are fine. Items, skills, shops, game modes and queue lanes have no Postgres overlay.
- `PaperPlayer` implements `sendActionBar` / `sendTitle` with Adventure + `IMessageRenderer` (keys
  `astera.proxy.transfer.*` added to en/ja). Konsist rule: no `TODO(` calls in `plugin/*/src/main`.
- Notices are best effort: `TransferNoticeListener` swallows and logs per-notice failures, and
  `InMemoryServerTransferGateway.transfer` logs a failing `PlayerTransferRequested` listener and still calls
  `sendToServer`.

## Consequences

`matches.stage_id` is a plain text column. A future stage-runtime table (e.g. per-stage statistics) must
reference stage ids without owning their definition.
