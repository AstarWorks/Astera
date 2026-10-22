---
status: Accepted
date: 2026-10-22
deciders: founder + Claude
supersedes: null
superseded_by: null
---

# ADR-0178: Upgrade to Paper 26.3

## Status

Accepted. Moves the Paper pin set by earlier Wave ADRs (26.1.x) to 26.3.

## Context

The running server reported being 4 releases behind the latest stable (26.3).
Paper API `26.3.build.211-stable` and server build 211 (channel STABLE) exist on
the Fill API v3 and the PaperMC Maven repo.

## Decision

- `paper` = `26.3.build.211-stable`; Docker / k8s `VERSION` = `26.3`.
- `plugin.yml` `api-version` = `'26.3'` (major.minor string; the old `'1.21'` was stale).
- `packetevents` 2.12.2 -> 2.14.0 (2.14.0 adds MC 26.3 support; 2.12.x only covers 26.1).
- `joml` 1.10.8 -> 1.10.9 (matches the version Paper API 26.3 pulls).
- PacketEvents `PlayerManager.getUser` is now nullable; senders use `?.sendPacket`
  (no packet for a viewer PE has not tracked yet).

## Consequences

Clients must be Java Edition 26.3. No shims or deprecated aliases kept.
