---
status: Accepted
date: 2026-05-19
deciders: ryuzu
supersedes: 0017
superseded_by: null
---

# ADR-0060: DataDriven Contents — Substrate Partial Pull-Forward (Wave _infra-2a)

## Status

Accepted

## Context

ADR-0017 (DataDriven Contents Pattern, 2026-05-16) committed Astera to a
`Contents<T>` / `DynamicContents<T>` / `YamlDynamicContents<T>` /
`ReloadableContents<T>` substrate but deferred implementation until "a second
content type that needs YAML-driven loading appears". As of Wave _infra-1
close (2026-05-19) the deferral is no longer defensible:

- **The trigger condition has fired many times over.** Beyond weapons,
  Astera now ships YAML-loaded content for skills, armor, status effects,
  items, GUIs, stages, normas, languages, anomalies, generators, and shops —
  ten plus content types, each with its own bespoke `Mutable<Type>Registry`
  + `<Type>LoaderService` pair. Every Phase 1 wave since 4.5 has added one.
- **Bespoke loader pairs duplicate `ContentLoader.DEFAULT_PARSERS` and the
  atomic-swap pattern.** `WeaponLoaderService.DEFAULT_PARSERS` and
  `MutableItemRegistry.DEFAULT_PARSERS` were already identical (Wave 4.5
  copy-paste). Without a substrate, every new content type carries forward
  the same boilerplate plus the same risk of forgetting `clear()` before
  hot-reload, or swapping mid-flight rather than atomically.
- **There is no reload command in production.** All registries load once
  at `onEnable` and stay frozen until restart. Phase 1 PoC closing (Wave
  5c-5f) plans many balance YAML iterations; restarting the server for every
  damage-curve tweak is unsustainable for the upcoming PvP balance work.
- **Wave _infra-1's `AsteraTestHarness` (ADR-0059) made the duplication
  visible** by wiring 10 + `Mutable*Registry` / `InMemory*Repository` instances
  by hand and producing scenario tests that need to drop a fake `Contents`
  shape in mid-flight. The "every type's own pair" pattern is now a Claude
  PDCA bottleneck, not just a contributor papercut.

ADR-0017's trigger §2 says:
> A non-weapon content type … needs to be loaded from `content/<type>/*.yaml`.
> Build the `Contents` substrate _together with_ that new type, and migrate
> weapons into it in the same change.

That spec implies a single big-bang migration. With ten + content types
already in production, that big-bang is **larger than any single Phase 1
wave can absorb**. Wave _infra-2 instead splits the work:

- **Wave _infra-2a (this ADR)** — substrate skeleton + weapon migration +
  `/astera-weapons reload`. Ships the proof that the substrate is mergeable
  and the reload workflow is sound. Other content types stay on their legacy
  loaders.
- **Wave _infra-2b** — migrate the remaining nine content types, add the
  Core-side `/astera reload <category>` dispatcher (ServiceRegistry-backed),
  add `IntProvider` sealed types (Constant / Uniform / WeightedList) per
  ADR-0017 §IRP, add JSON Schema validation gate, add optional
  `FileWatchService` (default OFF behind `astera.dev.watch=true`).

This ADR supersedes ADR-0017 because the implementation plan diverges from
the original spec on three points (see Decision below). ADR-0017's body
stays immutable; Status flips to `Superseded` when ADR-0017 lands as a
committed file.

## Decision

### 1. Substrate API (`plugin/core/.../application/contents/`)

Three public types:

```kotlin
public interface Contents<K : Any, V : Any> {
    public fun find(key: K): V?
    public fun all(): Collection<V>
}

public interface ReloadableContents {
    public val categoryName: String
    public fun reload(dir: Path): ContentLoader.LoadReport
}

public abstract class YamlDynamicContents<K : Any, V : Any, C : Any>(
    override val categoryName: String,
    deserializer: DeserializationStrategy<C>,
    parsers: List<IContentParser> = ContentLoader.DEFAULT_PARSERS,
) : Contents<K, V>, ReloadableContents {

    protected abstract fun toEntry(config: C): Pair<K, V>?

    public fun register(key: K, value: V)              // programmatic (tests, admin)
    public fun clear()
    public fun bootstrap(dir: Path): ContentLoader.LoadReport   // best-effort
    final override fun reload(dir: Path): ContentLoader.LoadReport  // all-or-nothing
}
```

Backed by `AtomicReference<Map<K, V>>`. `bootstrap` is best-effort (partial
parse-failure does not block successful entries from registering — admins
fix the offenders out-of-band). `reload` is **all-or-nothing**: on any
`failed > 0` the new map is discarded and the live state stays intact, so a
typo in one YAML never depopulates the live registry mid-match.

`ContentLoader.DEFAULT_PARSERS` is hoisted from the per-type companions
(`WeaponLoaderService` and `MutableItemRegistry` had identical copies) into
`ContentLoader`'s own companion. Per-type companions remain only for
content types that subset the parser list (none today).

### 2. Weapon migration

`MutableWeaponRegistry.kt` and `WeaponLoaderService.kt` are **deleted**.
Replaced by a single file:

```kotlin
public class WeaponContents : YamlDynamicContents<WeaponId, WeaponSpec, WeaponYamlConfig>(
    categoryName = "weapons",
    deserializer = WeaponYamlConfig.serializer(),
), IWeaponRegistry {

    override fun toEntry(config: WeaponYamlConfig): Pair<WeaponId, WeaponSpec> {
        val spec = config.toSpec()
        return spec.id to spec
    }

    public fun register(spec: WeaponSpec) {
        register(spec.id, spec)
    }
}
```

`IWeaponRegistry` is reduced to:

```kotlin
public interface IWeaponRegistry : Contents<WeaponId, WeaponSpec>
```

No new methods of its own — the Contents inheritance carries `find` /
`all`, which is exactly the existing port surface. Every caller
(`FireWeaponUseCase`, `GiveWeaponUseCase`, `WeaponClickListener`,
`MatchStartLoadoutApplyListener`) is unchanged. The only wiring change is in
`AsteraWeaponsPlugin.onEnable`:

```kotlin
val weaponContents = WeaponContents()
val report = weaponContents.bootstrap(core.contentDir.resolve("weapons"))
val weaponRegistry: IWeaponRegistry = weaponContents
```

Test fixtures: `WeaponLoaderServiceTest` renamed to `WeaponContentsTest`
with all five legacy cases plus three new reload-semantics cases.
`AsteraTestHarness` (Wave _infra-1) field type updates from
`MutableWeaponRegistry` to `WeaponContents` — same `register(spec)` API.

### 3. `/astera-weapons reload` subcommand

Per-plugin command (`/astera-weapons reload`), wired via the existing
vendor-neutral `CommandSpec` DSL — the Brigadier translation in
`PaperCommandHandler` is unchanged. Permission gate:
`astera.weapons.reload` (admin tier). Logs `loaded=N, failed=N (dir=…)`
and surfaces `CommandResult.Success` / `CommandResult.Failure` based on
the all-or-nothing `failed` count.

Wave _infra-2b will introduce a Core-side `/astera reload <category>`
dispatcher backed by `services.get<ReloadableContents>(categoryName)`; for
the single-category case of _infra-2a, per-plugin is enough and avoids
cross-plugin coupling that has no second consumer yet (same logic as
ADR-0004's deferred Koin decision).

### 4. Three deviations from ADR-0017 §IRP

1. **No Adventure `Key` based `ContentKey<T>`.** ADR-0017 §5 chose
   `net.kyori.adventure.key.Key` to save ~30 lines of namespace validation.
   The `adventure-api` dependency is not yet on `plugin:core`'s classpath
   (it's a Paper-transitive at the Paper adapter layer only). Adding it
   solely to wrap a string is premature — the substrate stays generic over
   `K : Any` so each content type uses its own existing domain id
   (`WeaponId`, `SkillId`, `ItemId`, …). Wave _infra-2b revisits if
   cross-type unified lookup becomes necessary.

2. **No `content/weapons/astera/` namespaced subdirectory.** ADR-0017 §IRP
   step 6 implied a layout move tied to Adventure `Key`'s namespace. Without
   the Adventure adoption, the subdirectory is purely organizational and
   carries no value. `content/weapons/*.yaml` stays flat for Wave _infra-2a.

3. **No `IntProvider` sealed types yet.** ADR-0017 §IRP step 3 listed
   `Constant` / `Uniform` / `WeightedList` as part of the initial substrate.
   No live content type uses `IntProvider` today — adding it preemptively
   would be the same YAGNI violation ADR-0017 explicitly avoided in its own
   original deferral logic. Wave _infra-2b lands `IntProvider` alongside
   the first content type that actually consumes it (likely loot tables or
   damage variance for skills).

Each deviation is small enough that Wave _infra-2b can add the missing
piece without touching the substrate API. The deviations are notes in this
ADR, not invariants — a future ADR can re-introduce any of them when
justified.

## Consequences

### Positive

- **Hot reload exists in production.** `/astera-weapons reload` is the
  first balance-iteration entry point — Phase 1 PoC closing (5c-5f) gets a
  workflow that doesn't require server restart for YAML changes.
- **Substrate is reusable.** Wave _infra-2b adds nine content types to
  this same skeleton with zero changes to the API. The substrate has
  exactly one consumer today (weapons), but the migration shape is now
  fixed enough that subsequent types are mechanical.
- **All-or-nothing reload protects live sessions.** A typo in one YAML
  during an active match cannot empty the live weapon registry. Failed
  reloads log + return non-zero `failed` for the operator to fix.
- **`AtomicReference` swap means readers never see a torn map.**
  `FireWeaponUseCase` and `WeaponClickListener` hold references to
  `WeaponContents` (as `IWeaponRegistry`) — `find()` always reads from the
  latest snapshot in one volatile read.
- **`ContentLoader.DEFAULT_PARSERS` deduplication.** Two existing copies
  collapsed into one, with `WeaponLoaderService.DEFAULT_PARSERS` removed
  in the same commit.
- **Test fixture API unchanged.** `WeaponContents.register(spec)` is the
  same shape as `MutableWeaponRegistry.register(spec)` — `AsteraTestHarness`
  (Wave _infra-1) and `FakeWeaponRegistry` keep working with a one-line
  type swap.

### Negative / Trade-offs

- **Three ADR-0017 deviations carry forward.** Adventure `Key`,
  namespaced subdirectory, and `IntProvider` sealed types are all
  deferred to Wave _infra-2b. Reviewers consulting ADR-0017 will see a
  more ambitious plan than what landed; the deviation list in this ADR
  is the reconciliation, but it's an extra hop.
- **Wave _infra-2b is now load-bearing.** Nine content types still on
  legacy loaders means the codebase has two patterns in flight until
  _infra-2b lands. New content types added before _infra-2b must choose:
  follow the new substrate (orphan from the rest) or follow legacy (delay
  uniform reload further). The handoff doc for _infra-2b should be
  drafted before the next non-_infra wave to keep this window short.
- **Per-plugin reload command instead of Core-side dispatcher.**
  `/astera-weapons reload` doesn't compose with other content types. An
  admin reloading several categories runs several commands. Acceptable
  for the single-content-type case but visibly suboptimal once
  _infra-2b adds skills + armor + …
- **`IWeaponRegistry` is now a marker interface** (extends `Contents`,
  adds nothing). Linters / Konsist may want a rule guarding against
  "empty marker interface" if more content types follow this pattern.

### Mitigations

- ADR-0060 ships with this commit so the deviation list is searchable.
  ADR-0017 Status updates to `Superseded` when it lands as a committed
  file; the `supersedes: 0017` frontmatter in this ADR makes the
  relationship retrievable.
- Wave _infra-2b handoff doc draft scheduled to be authored in the same
  session that closes _infra-2a so the two-pattern window is bounded
  by user pace, not by Claude planning lag.
- The marker-interface concern is just one type today. If `IItemRegistry`
  / `ISkillRegistry` / `IArmorRegistry` / etc. all become marker
  interfaces in _infra-2b, a follow-up review can decide whether to
  collapse them into `Contents<DomainId, DomainSpec>` directly (and
  expose only the type alias).

## Alternatives Considered

### Alt A: Full big-bang migration (all 10 content types in _infra-2)

Rejected. ADR-0017 §IRP step 5 implied this shape, but with ten content
types each touching ~3-5 callers + tests, the change-set would exceed
~80 files in one commit — outside Astera's "single-commit-per-wave"
discipline (`wave-execution.md`). Splitting into 2a + 2b respects the
discipline and lets the substrate stabilize before mass-migration.

### Alt B: Adopt Adventure `Key` and the namespaced subdirectory now

Rejected. `adventure-api` is not on `plugin:core`'s direct classpath
today. Adding it just for a string wrapper, before any content type
needs cross-namespace queries, is exactly the speculative abstraction
ADR-0017 told us not to commit. The Wave _infra-2b loot-table /
spawn-table work is the first concrete need; defer until then.

### Alt C: Build the substrate but don't migrate weapons in 2a

Rejected. A substrate with no live consumer is unfinished speculative
code. Migrating one type (weapons, the most stable Phase 1 content
type with the simplest schema) proves the API shape and surfaces any
ergonomics issues before _infra-2b commits the harder migrations.

### Alt D: Core-side `/astera reload <category>` dispatcher in 2a

Rejected. With one category (`weapons`), the dispatcher's
`services.get<ReloadableContents>(categoryName)` indirection has no
second caller. `/astera-weapons reload` is one line of `CommandSpec`
inside the existing weapons command tree. Wave _infra-2b adds the
dispatcher when 2-N more categories exist to consume it.

## References

- [[adr/0017-adopt-datadriven-contents-pattern]] — superseded by this ADR
- [[adr/0001-clean-architecture-with-mc-as-edge]] — domain Paper-free
- [[adr/0002-vendor-neutral-mc-adapter-layer]] — Adventure-vs-Bukkit
  classification (Adventure is vendor-neutral; staying off-classpath in
  _infra-2a is a dependency-management choice, not an ADR-0002
  compliance question)
- [[adr/0015-soft-dep-only-paper-plugin-yml]] — `/astera-weapons reload`
  ships in core paper-plugin metadata, no external command-plugin dep
- [[adr/0016-content-schema-ssot]] — `@Serializable` DTO remains the
  schema SSoT; `YamlDynamicContents` is the loader/registry layer above
- [[adr/0021-declarative-gui-architecture]] — `CommandSpec` DSL precedent
  for vendor-neutral command authoring
- [[adr/0022-multi-plugin-portable-architecture]] — sibling-isolation
  matrix (substrate in `plugin:core`, refactor lives in `plugin:weapons`)
- [[adr/0027-phase-1-poc-closing-scope]] — forward-only posture
- [[adr/0059-fake-mc-server-and-scenario-dsl]] — Wave _infra-1 harness
  whose `AsteraTestHarness.weaponRegistry` field migrates with this ADR
- Wave _infra-2a plan: `/home/node/.claude/plans/astera-fake-minecraft-server-claude-code-twinkling-salamander.md`
