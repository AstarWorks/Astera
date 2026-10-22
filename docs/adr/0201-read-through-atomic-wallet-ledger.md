---
status: Accepted
date: 2026-10-22
deciders: founder + Claude (Shop & Economy domain owner)
supersedes: null
superseded_by: null
---

# ADR-0201: Read-through, DB-atomic wallet ledger

## Status

Accepted. Narrows ADR-0123 (wallet lock striping) and ADR-0058 section 5 for money only.

## Context

`PostgresCurrencyLedger` was a facade over the per-JVM `PostgresPlayerAttributeContainer`
cache with per-player JVM monitors. With Velocity, a lobby backend and one or more game
backends run concurrently on one database, so:

- a match reward credited on the game backend was invisible to the lobby (stale cache),
- two backends could spend the same balance (monitors are per JVM),
- the container writes the **whole** JSONB document, so any unrelated write (XP, stat,
  modifier) from a server holding a stale cache rolled back money earned elsewhere.

## Decision

- Ledger operations are single SQL statements on the `wallet.<currency>` entry of
  `player_attributes.payload` (same place as before: no data migration). `debit` is
  `UPDATE ... WHERE stored >= amount RETURNING`, so the row lock serialises spenders on any
  server. `transfer` locks both rows in uuid order in one transaction. Every read goes to
  the database.
- `PostgresPlayerAttributeContainer.write` keeps the `wallet.*` entries already in the row
  (database wins over cache); a wallet entry present only in the cache is stored.
- `ICurrencyLedger.wallet()` returns a detached fresh snapshot; mutations go through
  `credit` / `debit`.

## Consequences

- Balance reads cost one indexed query (HUD refreshes on currency events, GUI opens); fine at
  this scale. A short TTL cache can be added later behind the same port.
- Other attribute writers still race with each other for non-money keys (last write wins);
  that is the Progression / Inventory owners' concern.
