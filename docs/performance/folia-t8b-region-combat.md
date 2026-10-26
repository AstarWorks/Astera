# T8b — region combat implementation and verification handoff

Implements ADR-0256/0257 on the T8a base. No shipped skill, model or language
file changes. Accepted ADRs are unchanged.

## Runtime changes

- Input remains synchronous through CastSkillUseCase → SkillCastDispatcher.
  Caster ownership is asserted before validation/pose reads. Target ownership
  is checked before damage resolution, armor/HP reads or mutations. Foreign
  targets use ExecutionScope.Entity; accepted asynchronous damage returns
  DamageOutcome.Scheduled, and publishes actual consequences only when applied.
  Baseline melee/AoE per-target damage and hit phases travel together. Status,
  knockback, entity effects and display writes also respect their owner.
- CombatSpatialGrid indexes living entities' entire bounding boxes in world/chunk
  buckets. Disjoint arena slots naturally address disjoint buckets. Spawn,
  removal, chunk entity loading, player/entity movement and teleport events
  maintain immutable snapshots on entity owners. Local queries refresh live
  eligibility and pose; foreign queries use snapshots and cached entity handles.
  Buckets and candidates use UUID ordering. Melee/AoE retain their original
  center/height distance and cone tests. Projectile contacts retain Bukkit's
  expanded BoundingBox ray intersection and nearest-hit calculation; homing
  retains box candidate selection, nearest feet and chest aim point.
- Block collision/LOS keeps the current world raycast. Reconstructing collision
  shapes and all mutable block state from ArenaBuildPlan + journal is not a cheap
  equivalent of the existing raycast, so no approximate voxel replacement lands.
- PaperCombatPhase creates a task per active chunk anchor (the public region
  scheduler address), batching projectiles and status effects. Empty batches
  retire. Projectiles rehome as they move, with a same-tick guard; status batches
  keep a 20-tick cadence and re-read state after each side effect. A retiring
  owner cannot leave a VFX ticker holding its reservation indefinitely.
- CombatComputePool is an internal ForkJoinPool because kernel IComputePool is
  absent on this base. Fixed index chunks are joined and merged in input order,
  including joining all submitted tasks if a chunk fails. Workers receive only
  immutable projectile motion inputs or status instances. Homing, raycasts,
  impacts, state reads and writes run in the ordered owner-thread merge. The sim
  uses this same motion/map path inline; GameSim accepts an injected pool for
  integration verification with real wiring/content.
- VFX age zero still runs inline. Later frames tick on their entity/region
  directly. Dispatch kill tracking is thread-local; maps accessed by separate
  region owners are concurrent. The mob damage suppression flag is thread-local.
- With PacketEvents present, particle data maps from the existing Bukkit data
  conversion to a native-protocol packet, encoded once into a pooled buffer.
  Each viewer gets a retained duplicate with independent indices; the producer
  releases its reference after fan-out. Client/proxy protocol translation remains
  downstream. Without PacketEvents the existing Bukkit particle path remains.
- Presentation alone consults the elapsed combat budget. After overrun, emissions
  within 16 blocks remain full, 16–32 blocks get every second emission, farther
  viewers every fourth. Authored positions/velocities/counts of retained packets
  are unchanged. Both packet and Bukkit particle paths apply this LOD. Gameplay
  always completes; it is never deliberately queued due to a budget overrun.

## Cost estimation and configuration

Optional `max_targets` is decoded by kaml, validated positive and represented in
`skill.schema.json`. Omission means unlimited gameplay targeting. An explicit cap
flows through archetype selection, mechanic contexts and projectile phases.
Estimation uses that cap, otherwise the assumed crowd; it includes per-target hit
phases. Unknown CEL costs, cycles, indefinite repeats and unmodelled world fan-out
can still saturate to unbounded. This remains an advisory operation score.

| JVM property | Default | Meaning |
| --- | --- | --- |
| `astera.skills.assumedCrowdSize` | 16 | Estimation population for uncapped skills; never a gameplay cap |
| `astera.combat.parallelThreshold` | 256 | Minimum batch size to fork |
| `astera.combat.chunkSize` | 64 | Fixed work chunk size, independent of pool size |
| `astera.combat.tickBudgetNanos` | 50000000 | Elapsed owner combat budget before particle LOD |

## Metrics

The existing `/metrics` exporter includes ExecutionCosts. New counters:

- `astera_combat_cross_region_hops_total` (also initialized to zero at wiring);
  each exceptional hop is INFO logged with target UUID and tick.
- `astera_combat_grid_updates_total`
- `astera_combat_parallel_batches_total`
- `astera_combat_gameplay_overruns_total`
- `astera_combat_vfx_lod_dropped_total`
- `astera_combat_vfx_packets_encoded_total`
- `astera_combat_vfx_packet_writes_total`
- `astera_combat_vfx_owner_hops_total`

New duration histogram kinds: `combat.grid_query` and `combat.phase`. Existing
skill/mechanic/projectile/VFX histograms remain. Budget time is elapsed time from
combat entry on the chunk owner, not exclusive CPU time or a server-wide MSPT.
No latency claim or 20-us benchmark is established by compilation.

## Verification

Passed compile-only:

```
./gradlew compileKotlin compileTestKotlin :plugin:core:shadowJar :plugin:combat:shadowJar
```

`git diff --check` passed. No tests, check task, live containers, E2E or push ran.
There is no new `build/e2e-report.md`; the manager generates it in the serialized
integration lane.

Added, compiled, **not executed**:

- `RegionCombatScenarioTest`: real shipped melee damages a crowd in the input
  tick; real YAML loader accepts a target cap and actual combat honors it;
  projectile positions, contacts and damage match inline/one/four workers using
  real pistol content and production wiring. Its pool cases write
  `region-projectiles-pool-{0,1,4}.json` traces when run.
- `RegionCombatE2eTest`: real server spawn → slash → teleport out of range → slash
  → teleport back → slash → despawn lifecycle. Tests accept normal/critical hits.

Manager verification: run these plus ProjectileLifecycleScenarioTest,
VfxScenarioTest, SpriteVfxScenarioTest and status/per-stack scenarios. Follow the
T8a note's baseline-vs-baseline determinism precautions before exact trace
comparison; existing random entity/projectile IDs and unseeded jitter are not
replaced here. Run the full Paper lane and Folia lane before calling parity
verified, including a long-range foreign-target hit and checking its hop metric.

## Remaining scope and risks

**Encode-once fan-out covers particles, not native display tracking packets.**
Display entity spawn/metadata/passenger tracking still uses the existing native
server path; a full byte-buffer encode-once implementation for that traffic is
outstanding. PacketEvents remains optional under the current dependency policy.

Paper/Folia packet bytes and ref-count lifetime, region-boundary projectile
collision, target-snapshot freshness, DoT boundary cadence and exact VFX frame
ordering require runtime verification. In particular, foreign target phases and
kill/status ordering must be checked in the Folia lane; compilation cannot prove
same-thread semantics across the accepted one-tick exception. Chunk anchors are
scheduler addresses, not a stable public physical Folia-region identifier.
