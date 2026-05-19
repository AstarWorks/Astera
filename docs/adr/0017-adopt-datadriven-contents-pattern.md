---
status: Accepted
date: 2026-05-16
deciders: ryuzu
---

# ADR-0017: Adopt DataDriven Contents Pattern (Implementation Deferred)

## Status

Accepted

## Context

While surveying prior art for Astera's content layer, three Kotlin libraries
from AzisabaNetwork were evaluated:

- [DataDriven](https://github.com/AzisabaNetwork/DataDriven) — "Lightweight
  library for data-driven Minecraft development." Provides `Contents<T>` /
  `ContentKey<T>` / `DynamicContents<T>` / `YamlDynamicContents<T>` /
  `ReloadableContents<T>` plus Mojang-data-pack-style `IntProvider` /
  `FloatProvider` / `OneOf<T>` / `IntPredicate` sealed-interface DSLs, plus
  Paper-side Brigadier `ArgumentType<T>` synthesis and `/reload` / `/get`
  command factories.
- [MinecraftExposed](https://github.com/AzisabaNetwork/MinecraftExposed) —
  Exposed ORM column types for Adventure (`Component`, `Key`, `Sound`,
  `TextColor`) and Paper (`ItemStack`).
- [MinecraftSerialization](https://github.com/AzisabaNetwork/MinecraftSerialization)
  — `KSerializer` implementations for Paper registry values (`ItemType`,
  `EntityType`, `BlockType`, `Enchantment`, …), Adventure types, and JOML
  vectors.

### Why the DataDriven concepts matter for Astera

Astera's content authoring already follows the same general direction
(`content/weapons/*.yaml`, ADR-0016 SSoT pattern), but each content type
currently requires its own `Registry` + `Loader` pair (today: `MutableWeaponRegistry`
+ `WeaponLoaderService`). As Phase 3-5 introduces equipment / skills /
stages / enemies / PvP rules, that pattern multiplies into bespoke
Registry/Loader pairs per type and a divergent reload story.

DataDriven generalizes the Mojang data-pack `Registry<T>` concept into a
type-parameterized `Contents<T>`:

- `namespace/value.yaml` directory layout produces typed `ContentKey<T>`
- `byKey` / `keyOf` bidirectional indexing with `IdentityHashMap` reverse map
- `AtomicReference`-based hot reload via `bootstrap()` / `reload()`
- One Brigadier argument-type factory works for every content type
- `IntProvider` sealed polymorphism (`Constant` / `Uniform` / `WeightedList` /
  `BiasedToBottom` / `ClampedNormal`) makes damage variance, drop tables,
  spawn rates expressible in YAML without code changes — the Mojang vanilla
  loot-table design ported to Kotlin

### Why the libraries themselves are not adoptable today

- **No LICENSE file** in any of the three repositories. Commercial use is
  undefined.
- All releases are `1.0.0-R0.x-SNAPSHOT`. No stable artifact, no semantic
  versioning commitment.
- Stars/forks at 0; recently written; long-term maintenance is unknown.
- Distribution via private Maven (`repo.azisaba.net`) requires repository
  configuration in every consumer build.
- Kotlin 2.3 / JDK 21 / Paper 1.21.11 — Astera is on Kotlin 2.3 / JDK 25 /
  Paper 26.x. Largely compatible, but version skew exists.

### Why this is not the right moment to implement, even in-house

Astera is in early Phase 1: the Clean Architecture skeleton was assembled
last week, content amounts to three example weapons, manual DI is still in
use (Koin deferred), and persistence is in-memory. Adding a generalized
`Contents<T>` substrate for a single content type would be speculative
abstraction — a YAGNI violation against the current Phase 1 focus
(architecture, DI, persistence, vendor-neutral adapter discipline). The
second content type has not yet appeared, so the substrate would be
designed from imagination rather than from observed duplication.

## Decision

1. **Adopt the DataDriven contents pattern as Astera's eventual content
   substrate.** Specifically: `Contents<T>` / `ContentKey<T>` /
   `DynamicContents<T>` / `YamlDynamicContents<T>` / `ReloadableContents<T>`,
   plus an `IntProvider` sealed interface, plus the Brigadier
   `ArgumentType<T>` synthesis / `/reload` / `/get` command factories.

2. **Implement in-house, not as a library dependency.** Re-implement the
   handful of source files (~800 lines total) under Astera's own packages,
   driven by the LICENSE / SNAPSHOT / stars-0 risk profile above. Astera
   keeps the ability to fork the design freely.

3. **Defer the implementation until a second content type is needed.** The
   trigger (see *Trigger Condition* below) is the appearance of any non-weapon
   content type that needs YAML-driven loading.

4. **When implementation begins, target two new modules:**
   - `plugin/adapter/minecraft-api/contents/` — vendor-neutral substrate
     (`Contents` / `ContentKey` / `DynamicContents` / `YamlDynamicContents` /
     `IntProvider` / Paper registry serializers).
   - `plugin/adapter/minecraft-impl-paper/contents/` — Brigadier
     `ContentArgumentType` and command factories (Paper-only, isolated).

5. **Use `net.kyori.adventure.key.Key` as the `ContentKey` underlying type.**
   Adventure is a Kyori-published library independent of any specific server
   core (Paper / Spigot / Velocity / Sponge all consume it). Using it from
   `minecraft-api` does not violate ADR-0002 vendor neutrality.

6. **Do nothing in Phase 1 beyond writing this ADR.** Specifically: do not
   pre-add the `adventure-api` library coordinate, do not refactor
   `WeaponLoaderService` / `MutableWeaponRegistry` to "prepare" for Contents.
   Both would be different YAGNI violations and the eventual Contents
   migration will replace these wholesale in one focused change.

## Trigger Condition for Implementation

This ADR's Status is `Accepted` but implementation is deferred. When **any
of the following** becomes true, change Status to `Implementing` (or
supersede with a new ADR per the second bullet) and execute the
*Implementation Reference Plan* below:

- A non-weapon content type (equipment, skill, stage, enemy, PvP rule, etc.)
  needs to be loaded from `content/<type>/*.yaml`. Build the `Contents`
  substrate *together with* that new type, and migrate weapons into it in
  the same change.
- A LICENSE is added to the upstream Azisaba repositories and they reach
  1.0.0 with stable artifacts. In that case, supersede this ADR with a new
  one re-evaluating direct library adoption versus the in-house path.

## Consequences

### Positive

- Phase 1 stays focused on Clean Architecture / DI / persistence work
  without scope creep into a substrate that has no second consumer yet.
- The eventual `Contents<T>` design will be informed by *two* concrete
  content types (weapons + the trigger type), not just speculative
  generalization from one.
- The decision and its rationale are preserved in an ADR rather than living
  only in chat history, so the future implementer (or future-me) does not
  have to re-derive it.
- Choosing Adventure `Key` over a hand-rolled `ContentId` saves ~30 lines of
  duplicate string-validation code and keeps the door open to interop with
  MinecraftSerialization-style libraries should the LICENSE situation
  change.

### Negative / Trade-offs

- `MutableWeaponRegistry` and `WeaponLoaderService` will be discarded when
  the migration happens; their continued maintenance until then is sunk
  cost. The cost is small (both files are short and stable).
- Contributors arriving between now and trigger may build a second
  `Registry` + `Loader` pair following the existing weapon pattern, missing
  the chance to introduce the Contents substrate. PR review must catch this.
- The Azisaba upstream's bug fixes and improvements will not flow into
  Astera's in-house copy automatically.

### Mitigations

- The trigger condition is concrete ("non-weapon content type that needs
  YAML loading"), not vague ("when the time is right"). Reviewers can check
  it mechanically.
- Link this ADR from `architecture/module-map.md` and from the per-type
  contribution guide (analogous to `contributing/add-a-weapon-yaml.md`) so
  that anyone adding a second content type encounters the decision.
- If/when LICENSE is added upstream, this ADR's second trigger bullet
  forces a re-evaluation rather than letting the in-house copy diverge
  indefinitely.

## Alternatives Considered

- **Adopt the libraries directly (full dependency).** Rejected: no LICENSE,
  all SNAPSHOT, stars 0, private Maven required. Commercial use is
  undefined and SNAPSHOT churn would block reproducible builds.
- **Fully bespoke design (ignore DataDriven entirely).** Rejected: the
  `IntProvider` / `Contents` abstractions are Mojang-data-pack-derived and
  battle-tested; re-deriving them would be wheel-reinvention with no
  upside.
- **Hand-rolled `ContentId` value class instead of Adventure `Key`.**
  Rejected: 30 lines of duplicate namespace/value validation, plus weaker
  interop with the Adventure-based ecosystem (kyori-text, MiniMessage,
  PaperMC plugin conventions) that Astera consumes anyway.
- **Implement the Contents substrate in Phase 1 now.** Rejected: YAGNI, the
  second content type has not appeared, and Phase 1 has its own focus that
  this work would dilute. See [[adr/0011-phase-1-implementation-deviations]]
  for the precedent of explicitly deferring non-essential Phase 1 work.

## Implementation Reference Plan

When the trigger fires, this is the starting outline. Re-evaluate against
upstream state and Astera's current shape before executing — do not treat
it as fixed contract.

1. Add `adventure-api` to `gradle/libs.versions.toml` (`[versions]` and
   `[libraries]`). Pick a version compatible with the active `paper-api`.
2. Add two modules to `settings.gradle.kts`:
   - `:plugin:adapter:minecraft-api:contents`
   - `:plugin:adapter:minecraft-impl-paper:contents`
3. Port DataDriven's core files into
   `plugin/adapter/minecraft-api/contents/src/main/kotlin/com/astarworks/astera/adapter/minecraftapi/contents/`:
   - `ContentKey.kt`, `Contents.kt`, `DynamicContents.kt`, `YamlDynamicContents.kt`
   - `provider/IntProvider.kt` (start with `Constant` / `Uniform` /
     `WeightedList`; defer the rest)
   - `serializer/PaperRegistrySerializers.kt` (start with `ItemType` /
     `EntityType` / `BlockType` / `Enchantment` / `PotionEffectType`; defer
     the rest until needed)
4. Port the Brigadier integration into
   `plugin/adapter/minecraft-impl-paper/contents/`: `ContentArgumentType.kt`,
   `ContentCommands.kt`, `CommandColors.kt`.
5. Introduce `WeaponContents : YamlDynamicContents<WeaponYamlConfig>` and
   the trigger type's analogous `Contents` subclass *in the same change*.
   Delete `MutableWeaponRegistry.kt` and `WeaponLoaderService.kt`. Update
   `AsteraPlugin.onEnable()` accordingly.
6. Migrate `content/weapons/*.yaml` to
   `content/weapons/astera/*.yaml`; drop the redundant `id:` field (key
   derived from path).
7. Update Konsist tests: `minecraft-api/contents` may depend on
   `adventure-api`; `minecraft-impl-paper/contents` may depend on
   `paper-api`; neither may be referenced from `:plugin:domain`.

Scope at trigger time: full `Contents` API, three `IntProvider` variants,
five registry serializers. `FloatProvider` / `OneOf<T>` / `IntPredicate` /
`ItemStackProvider` and the remaining registry serializers follow as Phase 3
or later content demands them.

The MinecraftExposed column-type pattern (`Component` / `Key` / `ItemStack`
in Exposed columns) is **out of scope for this ADR**. Re-evaluate when
Phase 2's PostgreSQL adapter is built; if adopted, it would warrant its own
ADR.

## References

- [[adr/0001-clean-architecture-with-mc-as-edge]] — Minecraft as edge,
  domain MC-non-dependent
- [[adr/0002-vendor-neutral-mc-adapter-layer]] — two-stage adapter (api +
  impl-paper); justifies the `contents/` module split
- [[adr/0011-phase-1-implementation-deviations]] — precedent for explicit
  Phase 1 deferral
- [[adr/0016-content-schema-ssot]] — Contents pattern composes cleanly with
  the `@Serializable` SSoT (the DTO remains canonical; `Contents` is the
  loader/registry layer above it)
- [DataDriven (AzisabaNetwork)](https://github.com/AzisabaNetwork/DataDriven)
- [MinecraftExposed (AzisabaNetwork)](https://github.com/AzisabaNetwork/MinecraftExposed)
- [MinecraftSerialization (AzisabaNetwork)](https://github.com/AzisabaNetwork/MinecraftSerialization)
- Mojang data-pack number providers: https://minecraft.wiki/w/Number_provider
