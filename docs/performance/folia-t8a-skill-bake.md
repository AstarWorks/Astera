# T8a — content-load baking and execution cost metrics

Base: `0201296626c1163cc2512b636e6a719057bdd36b`. Branch: `wp/folia-t8a-skill-bake`.
Implements the compute-reduction and measurement portion of ADR-0256. No scheduler
expressions, tick timing, target ordering or random draws were changed.

## Baked structures and runtime consumption

- `SkillSpec.baked: BakedSkill` holds the archetype hit shape, compiled CEL programs
  and a saturating lifetime operation estimate. `SkillContents` prepares programs
  from the serialized DTO at load/reload; serialization remains in application.
  `MechanicDispatcher` evaluates those programs. Programmatic/item override scripts
  are compiled on first use and cached separately.
- `BakedHitShape` contains closed-form cone/sphere/box parameters. Cone cosines are
  computed once for both melee params and cone targeters. Projectile boxes retain
  their existing enclosing-sphere collision radius.
- `DamageComponent.constantValue` folds literal base × multiplier once;
  `DamageProfile.base` folds the original ordered sum once. Copies used for dynamic
  per-stack bonuses build fresh values. Existing CEL base/multiplier defaults remain
  0/1; the only production CEL adapter is still the unsupported-expression stub.
  `ICelEvaluator.compile` is now the adapter contract; no new CEL language/parser
  was introduced that could activate previously unsupported content.
- `VfxDefinition.baked` owns resolved element/group tracks, sampled local transforms
  and quaternions, and caster-space formation positions/rotations. `BakedRig` owns
  swing/euler/quaternion tables; rig parts own their fixed rotation. `VfxTimeline`
  and `VfxRigMotion` read those tables. Follow overrides copy the output plan rather
  than rebuilding the definition and its tables.
- `ParticleDefinition.bakedShape` owns raw local curve points, tangents and outward
  vectors. Runtime emission applies the existing anchor quaternion and translation;
  curve distribution, trigonometry and tangent sampling no longer repeat per emit.
- Element matchups use a dense ordinal-indexed `DoubleArray`. Status interaction
  requirements use flattened integer rows and a boolean presence array, preserving
  first-declared-rule priority.

Seeded jitter, emitter trajectories, cast scale, sprite frame counts, rig hit-stop,
anchor-dependent sampling decisions and dynamic target tracking still assemble at
runtime. Transform multiplication/addition order is retained rather than combining
rotations algebraically and changing floating-point results. Out-of-lifetime authored
keyframe endpoints retain the original interpolation computation when outside a table.

## Metrics

Existing HTTP `/metrics` endpoint (`ASTERA_METRICS_ENABLED=true`, default port 9092).
`MicrometerMetricsRegistry.scrape()` appends the shared runtime `ExecutionCosts` data.

- `astera_execution_nanoseconds{kind,id,quantile="0.5|0.95|0.99"}`: p50/p95/p99,
  expressed as power-of-two histogram bucket upper bounds, in nanoseconds.
- `astera_execution_max_nanoseconds{kind,id}`: exact observed maximum.
- `astera_execution_calls_total{kind,id}` and
  `astera_execution_calls_per_second{kind,id}`: count and average rate since first call.

Kinds: `skill`, `mechanic.<Kotlin class name>`, `target.query`, `target.radius`,
`target.nearby`, `target.projectile_contact`, `target.homing`, `projectile.step`,
`vfx.play`, `vfx.tick`. Skill/mechanic/target/projectile IDs are skill IDs; VFX IDs
are effect IDs; direct spatial gateway calls use `all`. `unbound` identifies
programmatic execution without a skill context. `id="*"` pools every ID for a kind.
Durations are inclusive: nested measurements must not be summed as exclusive CPU.
Mechanic measurements include gates and delayed/repeated firings, including skips.

Recording uses LongAdder buckets and a CAS maximum. Maps register families/IDs on
first observation; warmed recording has no histogram locks or per-call key allocation.
There is no timer, scheduler job, idle polling or domain clock. Scrapes are approximate
concurrent snapshots; no runtime latency/overhead benchmark was executed.

## Content cost warnings and top ten

`-Dastera.skills.costWarningOps=100000` configures the advisory threshold (default
100000). Cost warnings do not fail content-lint, even with `--strict`. Normal schema,
semantic or real-loader failures still fail the CLI. Runtime bootstrap logs the top
ten; content-lint prints it using the same estimator/loaders. Each skill carries its
load-time estimate; final reports additionally resolve cross-skill references.

The estimator counts lifetime repetitions, branches, nested loops, particle animation
expansion, named particles, VFX slots and projectile lifetime. Arithmetic saturates.
Unconstrained candidate scans, indefinite repeats, cyclic/missing dependencies and
unmodelled world fan-out yield `Long.MAX_VALUE` (`unbounded`). This is conservative:
a finite gameplay/world-population cap is not invented or enforced by this change.
It is an operation score, not a prediction of nanoseconds or an enforced CPU budget.

The current top ten all tie at **unbounded**, so IDs break ties alphabetically:

1. bastion-greatsword-ego
2. bastion-greatsword-s1
3. bastion-greatsword-s2
4. bastion-greatsword-s3
5. couture-rapier-cutout
6. couture-rapier-def
7. couture-rapier-ego
8. couture-rapier-patterning
9. couture-rapier-s1
10. couture-rapier-s2

These are not a measured performance ranking. Finite rankings require population
bounds or a richer symbolic cost model; measured kind/skill histograms are available
for deciding where the next optimization should land.

## Verification and manager trace procedure

Passed: `./gradlew compileKotlin compileTestKotlin :plugin:core:shadowJar :plugin:combat:shadowJar`.
This includes game-sim and architecture-test compilation. Ran the compiled content-lint
CLI directly: 510 passed, zero failed; real loaders accepted 84 skills, 211 VFX and
60 particle definitions. `git diff --check` and a source comparison of scheduler
expressions passed. No tests, Konsist execution, E2E, live containers or push ran.

Trace equality is **not verified**. Existing `ScenarioDsl` writes
`tools/game-sim/build/sim-traces/*.json` (`astera-sim-trace/1`); there is no checked-in
golden comparison. The manager should:

1. Use separate baseline/current checkouts and the same JDK, content and sim seed.
   Run selected game-sim scenarios (VfxScenarioTest, SpriteVfxScenarioTest,
   ProjectileLifecycleScenarioTest, status/per-stack scenarios), then the existing
   SkillShowcaseScenarioTest for all skills. Preserve both trace directories.
2. First make baseline runs reproducible: `SimWorld.newPlayerId()` and display handles
   use `UUID.randomUUID()`. VfxMechanics derives its seed from the actor ID, so simply
   normalizing output IDs is insufficient for jitter/emitter scenarios. Replay fixed
   actor identities in an identical temporary verification fixture in both revisions;
   also control existing Random.Default paths used by random targeters/particles.
   These existing determinism gaps were left unchanged in this optimization track.
3. Canonicalize entity IDs by named identity, display IDs by spawn order and projectile
   IDs by spawn order, including references. Preserve event/target/list order, ticks,
   damage, statuses, particle counts/coordinates/velocities and every display transform.
   Compare JSON values exactly; do not round coordinates or discard varying gameplay.
4. Any baseline-vs-baseline divergence is a fixture/seed gap to resolve before judging
   before/after traces. Successful compilation/content lint alone does not prove parity.
