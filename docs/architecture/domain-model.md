# Domain Model

Catalog of every type in `plugin/domain/`. The domain is **Minecraft-free** —
no `org.bukkit.*`, no `io.papermc.*`, no I/O, no `kotlinx.serialization`.
Konsist tests in `:tools:architecture-test` enforce this.

## Primitives

| Type | Purpose |
|---|---|
| [`Result<T, E>`](../../plugin/domain/src/main/kotlin/com/astarworks/astera/domain/Result.kt) | Sealed success/failure for expected outcomes. Astera-defined to avoid `kotlin.Result<T>`'s `Throwable` failure type. Helpers: `map`, `flatMap`, `mapError`, `fold`, `onSuccess`, `onFailure`. |
| [`MessageKey`](../../plugin/domain/src/main/kotlin/com/astarworks/astera/domain/model/i18n/MessageKey.kt) | Typed i18n lookup key. Validates `astera.<area>.<...>` form at construction. Has `div` operator for composition. |

## Geometry

| Type | Purpose |
|---|---|
| [`Vec3`](../../plugin/domain/src/main/kotlin/com/astarworks/astera/domain/model/geometry/Vec3.kt) | Immutable 3D point. Domain-defined to avoid JOML or Bukkit Location leak. |

## Animation

| Type | Purpose |
|---|---|
| [`Easing`](../../plugin/domain/src/main/kotlin/com/astarworks/astera/domain/animation/Easing.kt) | 31-entry enum of standard easing curves (easings.net). Each entry knows its math; `apply(t)` clamps `[0,1]`; `interpolate(from, to, t)` lerps. |

## Player

| Type | Purpose |
|---|---|
| [`PlayerId`](../../plugin/domain/src/main/kotlin/com/astarworks/astera/domain/model/player/PlayerId.kt) | UUID-backed identifier. Crossing the Minecraft boundary converts here. |
| [`PlayerProfile`](../../plugin/domain/src/main/kotlin/com/astarworks/astera/domain/model/player/PlayerProfile.kt) | id + displayName + locale + joinedAtEpochMs. |
| [`LoadoutSlot`](../../plugin/domain/src/main/kotlin/com/astarworks/astera/domain/model/player/LoadoutSlot.kt) | enum MAIN/SUB/MOVE/SPECIAL/ARMOR/ULTIMATE (RTM-style). |
| [`Loadout`](../../plugin/domain/src/main/kotlin/com/astarworks/astera/domain/model/player/Loadout.kt) | Slot → WeaponId map. `with(slot, weaponId)` returns new Loadout. |

## Weapon

| Type | Purpose |
|---|---|
| [`WeaponId`](../../plugin/domain/src/main/kotlin/com/astarworks/astera/domain/model/weapon/WeaponId.kt) | lower-kebab-case identifier (`example-sword`). |
| [`DamageAttribute`](../../plugin/domain/src/main/kotlin/com/astarworks/astera/domain/model/weapon/DamageProfile.kt) | enum PHYSICAL/FIRE/ELECTRIC/WIND/WATER/EARTH. |
| [`DamageProfile`](../../plugin/domain/src/main/kotlin/com/astarworks/astera/domain/model/weapon/DamageProfile.kt) | base + attribute. Invariant: base ≥ 0. |
| [`WeaponArchetype`](../../plugin/domain/src/main/kotlin/com/astarworks/astera/domain/model/weapon/WeaponSpec.kt) | enum SWORD/GUN/WAND (Phase 2). |
| [`Rarity`](../../plugin/domain/src/main/kotlin/com/astarworks/astera/domain/model/weapon/WeaponSpec.kt) | enum COMMON…LEGENDARY. |
| [`WeaponSpec`](../../plugin/domain/src/main/kotlin/com/astarworks/astera/domain/model/weapon/WeaponSpec.kt) | Immutable weapon definition. `displayNameKey` / `loreKey` are `MessageKey`s. `materialKey` is a vendor-neutral material reference (e.g. `IRON_SWORD`, `oraxen:my_blade`). |

## Domain events

| Type | Purpose |
|---|---|
| [`DomainEvent`](../../plugin/domain/src/main/kotlin/com/astarworks/astera/domain/event/DomainEvent.kt) | Sealed marker for events produced by domain rules. |
| `WeaponFired` | Player fired a weapon at a location. |

## Domain rules (pure functions)

| Function | Purpose |
|---|---|
| [`WeaponDamageRule.calculate(profile)`](../../plugin/domain/src/main/kotlin/com/astarworks/astera/domain/rule/WeaponDamageRule.kt) | Phase 1 returns base; Phase 2+ adds multipliers. |

## Match (Phase 3 substrate)

| Type | Purpose |
|---|---|
| `MatchId`, `TeamId`, `MatchPhase` (WAITING/COUNTDOWN/ACTIVE/SUDDEN_DEATH/ENDED) | Identity + lifecycle. |
| `Team` | TeamId + display + color (`MessageKey`s) + member `PlayerId`s. |
| `Score` | per-team Int. `withIncrement(team, delta)`. `isTied` helper. |
| `Match` | Aggregate of phase + teams + score + stageId + startedAtTick. |

## Stage (Phase 3 + Phase 5 substrate)

| Type | Purpose |
|---|---|
| `StageId`, `PlotId` | identifiers. |
| `Region` | axis-aligned bbox. `contains(point)`, `volume`. |
| `Plot` | id + owner (PlayerId?) + region (Phase 5 Place). |
| `Stage` | id + displayNameKey + regions list. |

## World (vendor-neutral world references)

| Type | Purpose |
|---|---|
| `WorldId` | non-blank world identifier (Bukkit world name analog). |
| `BlockType` | `namespace:identifier` key (vanilla: `minecraft:stone`; Oraxen: `oraxen:astera_brick`). Adapter resolves to concrete material. |

## Effect (vendor-neutral effect descriptors)

| Type | Purpose |
|---|---|
| `ParticleSpec` | key + count + offset Vec3. |
| `SoundSpec` | key + volume (`0..4.0`) + pitch (`0.5..2.0`). |

## Economy (Phase 4 substrate)

| Type | Purpose |
|---|---|
| `Currency` | enum STAR / GOLD / GEM. |
| `Wallet` | per-currency Long balances. `balance(c)`, `deposit(c, n)`, `withdraw(c, n) → Result<Wallet, WalletError>`. |
| `WalletError` | sealed; current case: `InsufficientFunds(currency, have, tried)`. Each carries `messageKey`. |

## Invariants (enforced at construction)

- All `*Id` value classes validate their string format (`^[a-z][a-z0-9-]*$` for kebab-case ids; `^[a-z][a-z0-9_-]*(\.[a-z0-9_-]+)*$` for `MessageKey`).
- `Vec3` has no invariant (any Doubles allowed).
- `Region`: `min ≤ max` on every axis.
- `DamageProfile`: `base ≥ 0`.
- `WeaponSpec`: `levelRequirement ≥ 1`, `cooldownTicks ≥ 0`, `materialKey` non-blank.
- `PlayerProfile`: `displayName` non-blank, `joinedAtEpochMs ≥ 0`.
- `Match`: `teams` non-empty, `startedAtTick ≥ 0`.
- `Wallet`: all balances ≥ 0; `withdraw` checks funds (Result failure on shortage); `deposit` requires non-negative amount.
- `BlockType`: `namespace:identifier` format.
- `SoundSpec`: volume in `[0, 4.0]`, pitch in `[0.5, 2.0]`.

## What's NOT in domain (deliberate)

- **Bukkit / Paper types** (`org.bukkit.*`, `io.papermc.*`)
- **I/O** (`java.io.*`, `java.nio.*`, `java.net.*`) — Konsist forbids
- **Serialization annotations** (`kotlinx.serialization`) — Konsist forbids; DTOs live in `application/config/`
- **JOML** — coordinate types are domain-defined `Vec3`
- **SLF4J** — logging is application-level
- **Koin / Dagger / Spring** — DI is platform-level
- **Persistence drivers** (PostgreSQL, Exposed, Flyway, Lettuce) — adapter-level

## Future additions (planned per phase)

| Phase | Adds |
|---|---|
| Phase 2 mid | `SkillId`, `SkillSpec`, `SkillTrigger`, `Cooldown`, `StatusEffect`, status-effect container |
| Phase 3 | Siege-specific rules: `CoreHealth`, `CoreLocation`, `SiegeRoles`, win-condition functions |
| Phase 4 | UGC marketplace: `MarketItemId`, `MarketListing`, `Bid` |
| Phase 5 | Place / SNS: `PlotPermission`, `BuildRights`, `EventSchedule` |
