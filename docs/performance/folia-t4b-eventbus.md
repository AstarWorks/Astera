# T4b: routed events, instance strands and compute

Implements ADR-0252, ADR-0253, ADR-0254 and ADR-0257. Match state remains Global-owned until T7.

- Kernel `GameScope` distinguishes OpenWorld and Instance. `EntityRef` contains only immutable identity/world data captured by its owner. Core registers the OpenWorld resolver; the gameplay instance registry overrides it at priority 100.
- `SlotAllocator` creates a `GameInstance` for each slot, including the reserved lobby. Allocation/free bookkeeping stays on Global; lifecycle transitions run on the instance strand. A repaired warm arena receives a fresh identity, which also identifies the match using it. Lobby rebuilds pass through Free and Allocating before Building again.
- `InstanceDirectory` publishes immutable snapshots and asserts Global ownership for every write. Match admission follows successful `PlayerPortalReady.EntryReady`; leaving, lobby arrival and disconnect update the directory on Global. This adds routing only, not the full carry/handoff protocol.
- `ExecutionScope.Instance` runs through a serial mailbox on the bounded worker pool. Delays/repeats enqueue onto the mailbox. Idle mailbox removal shares the enqueue key lock, preventing overlapping strands when an instance becomes active again. Callbacks must hop to Entity or Region for live world access.
- Events declare Instance, Player or Global routes. Match events use the instance/match identity; player events resolve through the directory. `IDomainEventBus.local(id)` filters local subscriptions. SameStrand delivery runs synchronously on the owning strand, or queues there from another owner. Global and IO delivery use serial mailboxes for FIFO; Global delivery stays synchronous when already on Global. Existing subscribers explicitly select Global, preserving current match ownership.
- `IComputePool` accepts fixed chunks and returns results in input order. Paper bounds ForkJoinPool workers, including compensation workers; game-sim runs chunks inline. `fixedChunks` depends only on input size/chunk size. `castSeed` hashes scope seed, caster UUID and cast counter; counter storage and combat adoption remain with the entity-owned combat work.
- Konsist forbids GlobalScope and runBlocking in all main sources. Simulator command helpers drive their virtual scheduler instead of blocking a coroutine thread.

## Verification

Compile-only command:

```sh
./gradlew compileKotlin compileTestKotlin :plugin:core:shadowJar :plugin:items:shadowJar :plugin:combat:shadowJar :plugin:match:shadowJar :plugin:stats:shadowJar :plugin:economy:shadowJar :plugin:persistence:shadowJar
```

Result: **BUILD SUCCESSFUL**, 102 actionable tasks; no test execution.

Added `tools/game-sim/.../InstanceRoutingScenarioTest.kt` scenarios:

- `instance-routing-fifo`: two real matches, successful admission, local isolation, Instance/Player/Global routes, delivery ownership, FIFO, synchronous same-owner dispatch and cancellation.
- `slot-instance-lifecycle`: real slot allocation, lifecycle transitions, Global-only directory writes, OpenWorld fallback, free/reuse and repasted warm-slot identities.
- `compute-pool-determinism`: real inline and bounded worker adapters at different pool sizes, fixed-chunk merge, seed/counter behavior and serial worker mailbox ordering/non-overlap.

Scenarios and Konsist are compiled but deliberately not executed by this implementation worker. The manager must run these scenarios, architecture checks and the batched Paper/Folia E2E lane. No live containers were touched.
