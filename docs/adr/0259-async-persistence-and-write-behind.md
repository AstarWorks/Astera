---
status: Accepted
date: 2026-10-26
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0259: Async persistence ports, write-behind, preload on AsyncPlayerPreLogin

## Status

Accepted. Applies [[adr/0252-folia-runtime-and-executionscope-threading-model]] to the Postgres repositories
(`plugin:persistence`, [[adr/0258-independent-jar-graph-and-skills-bundle]]).

## Context

Repository ports are synchronous and Exposed `transaction { }` blocks run on whatever thread calls them, today the Paper
main thread. On Folia a blocking JDBC call on a region thread stalls every entity in that region, and the cast budget of
[[adr/0256-same-tick-region-thread-combat]] (< 20 us) is incompatible with any DB round trip.

## Decision

1. **Async ports**: repository ports return `suspend` / `CompletableFuture`-style results typed `Result<T, E>` (no raw
   throwables in application APIs) and declare `ExecutionScope.Io` for their work.
2. **Preload on `AsyncPlayerPreLogin`**: the player's profile, wallet, stats and loadout are loaded off-thread before
   join completes and held in an in-memory entity-owned cache. Game code reads the cache synchronously.
3. **Write-behind**: mutations update the cache immediately and enqueue a write to a per-aggregate coalescing queue flushed
   by the IO pool (interval + on quit + on shutdown, with a bounded retry). Hot paths never wait for the DB.
4. **No `transaction(` on game threads**: allowed only under `Io` scope; enforced by Konsist ([[adr/0262-folia-safety-konsist-rules]]).
5. Persistence stays an optional jar: without it, in-memory repositories (priority-overridden at 100 when present) keep the
   skills bundle working.
6. Shutdown flushes the queues before the pool closes; crash loss is bounded by the flush interval and documented per aggregate
   (economy ledger entries are written through, others write-behind).

## Consequences

### Positive
- No JDBC stalls on region threads; join is gated only by the async preload.
- Fewer DB round trips through coalescing.

### Negative / Trade-offs
- Write-behind adds a small crash-loss window for non-ledger data.
- Cache coherence between backends (multi-backend, [[adr/0263-homogeneous-backends-and-optional-proxy-scale-out]]) needs care: a player is owned by one backend at a time, transfer flushes first.

### Mitigations
- Ledger and purchases are write-through; transfer protocol flushes then releases.
- Metrics expose queue depth and flush latency.

## Alternatives Considered

- **Keep synchronous calls, run them on the Io pool by callers**: leaves the API easy to misuse; Konsist cannot tell.
- **Pure write-through async**: still correct but stalls UI flows waiting on futures.
- **Move to an in-process embedded DB**: loses shared state across backends.

## References

- [[adr/0252-folia-runtime-and-executionscope-threading-model]], [[adr/0253-gamescope-gameinstance-strand-and-handoff]],
  [[adr/0262-folia-safety-konsist-rules]]
