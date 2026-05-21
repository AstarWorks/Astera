---
status: Accepted
date: 2026-05-21
deciders: Astera maintainers (Wave 5m)
supersedes: null
superseded_by: null
---

# ADR-0088: Combat AOE + environment damage source

## Status

Accepted

## Context

Astera までの combat surface は **per-target** damage (`ApplyDamageUseCase`)
と projectile (`IProjectileService`) と melee skill dispatch (Wave 5a
`SkillCastDispatcher` の `MeleeHandler` / `AoeHandler`) を提供していたが、
"world そのものに作用する AOE" を表現する port は持っていなかった:

- **Explosion** — TNT 同形の球状 AOE + block destruction。Wave 5n boss
  skill / Wave 5j hazard / Phase 4 grenade item で必要。
- **Persistent area effect** — poison cloud / fire ring / smoke screen の
  ような "一定 duration、一定 interval で範囲内 entity に作用する zone"。
  Wave 5n anomaly + Wave 5p boss skill chain で頻出。
- **Lightning** — 視覚 / 音響としての lightning strike (vanilla
  `World.strikeLightning(Effect)?`)。Wave 5n weather event + Phase 4 wand
  spell で必要。FX-only モード (`damageEntities = false`) も需要あり。
- **Fire spread control** — vanilla の fire-tick game-rule trigger + 任意
  ignition。Wave 5n arena event "burning floor" + admin / debug 用。

加えて、`DamageSource` sealed (Wave 5a 起源) の **environmental branch** が
事実上 `data object Environment` の singleton 1 つしかなく、Wave 6a の
Paper damage hijack 時に "fall vs lava vs drown vs lightning vs void vs
suffocation" を区別できない問題があった。Wave 5a 時点では `Fall(distance)`
だけ top-level に切り出され、残りは全部 `Environment` 1 個に丸められて
いた。Wave 6a は `EntityDamageEvent.DamageCause` を fully typed
`DamageSource` に変換する責務を負うので、サブ variant が無いと
adapter 側に大量の `if (cause == LAVA) … else if (cause == DROWNING) …`
分岐が残る (= 分類情報が `DamageSource` ではなく adapter に住む = 後で
analytics / status-effect listener が逆方向に問い合わせる構造)。

### 解決すべき設計問題

1. **Explosion / AOE / Lightning / FireSpread の port surface を Wave 6a
   Paper 接続の前に確定する** — Wave 5a の `IProjectileService` 同様
   "port commit → InMemory default → Wave 6a で Paper impl 上書き" の
   pattern を踏襲。
2. **`DamageSource.Environment` を sealed hierarchy 化** — Wave 6a の
   `BukkitEventAdapter` が `EntityDamageEvent.DamageCause.*` → 各
   environment variant の thin mapping だけ書けば済む形に。
3. **`Fall` を `Environment` 配下に hoisted** — `Fall` も environmental
   cause なので top-level に居続ける合理性が無い。pre-release posture が
   許す breaking rename。
4. **Konsist の vendor-neutrality 保証を AOE port にも拡張** — combat の
   既存 "application must not import Bukkit" rule で実際カバーされて
   いるが、ADR-0088 のスコープを CI failure メッセージで一目で分かる
   ようにする補助 rule を追加。

### 代替案検討

- **Alt A: AOE は既存 `MechanicDispatcher.AOE` に詰める** — Wave 5a
  `AoeHandler` は per-target damage の loop (= "skill-bound multi-hit") は
  扱うが、block destruction や duration を持つ zone は扱えない。詰め込み
  続けると `MechanicDispatcher` が "skill action runner" + "world AOE
  gateway" の 2 役を負い、Folia 移行時に分割コストが激増する。
- **Alt B: Explosion を `Block` 側に置く (block-driven AOE)** — TNT は
  block-driven だが、boss skill / grenade / spell の explosion は entity
  / position-driven。block-driven model は 6 割の use case に合わない。
- **Alt C: `DamageSource.Environment` を sealed 化せず `Environment(cause:
  String)`** — string-typed enum と等価で、`when` exhaustiveness を捨てる。
  `cause = "FALL"` を fall distance 抜きで持つと知らずに、Wave 6a の
  adapter が "fall distance を別 field で送らねば" 設計を強いられる。
- **Alt D: Vanilla `World.createExplosion` を MechanicDispatcher 内で
  直接 call** — `application.*` から `org.bukkit.*` を import することに
  なり、Konsist "combat application must not import Bukkit family" が
  fail。Phase 6 Folia 移行時に region scheduler に渡す port が無い。

## Decision

Wave 5m で以下 4 つの outbound port + `DamageSource.Environment` の sealed
化 + 5 つの domain event を commit する。

### 1. `IExplosionGateway` (球状 AOE + block destruction)

```kotlin
public data class ExplosionSpec(
    val center: Vec3,
    val world: WorldId,
    val radius: Double,
    val knockbackMultiplier: Double = 1.0,
    val blockDamage: BlockDamagePolicy,  // None / Vanilla / Astera(ratio)
    val damageProfile: DamageProfile,
    val source: DamageSource,
)
public sealed interface BlockDamagePolicy {
    public data object None : BlockDamagePolicy
    public data object Vanilla : BlockDamagePolicy
    public data class Astera(val damageRatio: Double) : BlockDamagePolicy
}
public interface IExplosionGateway {
    public fun trigger(spec: ExplosionSpec): Result<ExplosionResult, ExplosionError>
}
```

InMemory 実装 = `INearbyTargetQuery` で候補 player 列挙 → uniform damage
+ uniform knockback + `ExplosionTriggered` domain event 発火。
distance falloff は per-target position が必要なので Wave 6a Paper impl で。

### 2. `IAreaEffectGateway` (persistent zone)

```kotlin
public sealed interface AreaEffectShape {
    public data class Circle(val radius: Double) : AreaEffectShape
    public data class Box(val sizeX: Double, val sizeY: Double, val sizeZ: Double) : AreaEffectShape
    public data class Ring(val innerRadius: Double, val outerRadius: Double) : AreaEffectShape
}
public sealed interface AreaEffectAction {
    public data class ApplyStatusEffect(val effectId: StatusEffectId) : AreaEffectAction
    public data class DealDamage(val profile: DamageProfile) : AreaEffectAction
    public data class SpawnParticle(val particle: ParticleSpec) : AreaEffectAction
}
public data class AreaEffectSpec(
    val center: Vec3, val world: WorldId,
    val shape: AreaEffectShape,
    val durationTicks: Int, val tickIntervalTicks: Int,
    val effectPerTick: AreaEffectAction,
)
public interface IAreaEffectGateway {
    public fun spawn(spec: AreaEffectSpec): Result<AreaEffectHandle, AreaEffectError>
    public fun despawn(handle: AreaEffectHandle): Result<Unit, AreaEffectError>
    public fun all(): Collection<AreaEffectHandle>
}
```

InMemory 実装 = `IScheduler.runRepeating` で per-tick callback、
`AreaEffectSpawned` / `AreaEffectDespawned` で lifecycle 公開。
per-tick action 実行は `TickAction` SPI に委譲し、Wave 6a で
`ApplyDamageUseCase` / `ApplyStatusUseCase` を wire する adapter を載せる。

### 3. `ILightningGateway` + `IFireSpreadController`

```kotlin
public interface ILightningGateway {
    public fun strike(world: WorldId, position: Vec3, damageEntities: Boolean = true)
        : Result<Unit, LightningError>
}
public interface IFireSpreadController {
    public fun isFireSpreadEnabled(world: WorldId): Boolean
    public fun setFireSpread(world: WorldId, enabled: Boolean)
    public fun spreadFire(world: WorldId, source: Vec3, radius: Int): Int
}
```

両者とも InMemory 実装 + 4 つの test fixture (`FakeExplosionGateway` /
`FakeAreaEffectGateway` / `FakeLightningGateway` /
`FakeFireSpreadController`) を `plugin/combat/src/test/.../testfixtures/aoe/`
に同梱。

### 4. `DamageSource.Environment` の sealed 化

```kotlin
public sealed class DamageSource {
    // ... 既存 Item / Skill / Explosion / StatusEffectTick / Projectile / Barrier
    public sealed class Environment : DamageSource() {
        public data class Fall(val distance: Double) : Environment()
        public data class Drown(val remainingAir: Int) : Environment()
        public data class Fire(val ticksOnFire: Int) : Environment()
        public data object Lightning : Environment()
        public data object Void : Environment()
        public data object Suffocation : Environment()
        public data object Generic : Environment()  // 旧 `Environment` singleton 置換
    }
}
```

旧 `DamageSource.Fall(distance)` は **削除** (`Environment.Fall` に hoist)。
旧 `DamageSource.Environment` singleton は `Environment.Generic` に置換。
全 caller (combat / stats の test 17 files) を一括 rename。pre-release
posture (CLAUDE.md §"Pre-release posture") に従い deprecated alias は
残さない。

### 5. Domain events (5 件)

- `ExplosionTriggered(world, center, radius, source, affectedPlayers, affectedBlocks, atTick)`
- `AreaEffectSpawned(handle, world, center, shape, durationTicks, tickIntervalTicks, action, atTick)`
- `AreaEffectDespawned(handle, reason, atTick)` — `reason` は
  `ExpiredNaturally` / `ExplicitDespawn` enum。
- `LightningStrike(world, position, damageEntities, atTick)`
- `EnvironmentDamageApplied(target, source, finalDamage, atTick)` — Wave 6a
  `BukkitEventAdapter` が environmental damage を resolve した後で発火。

### 6. Konsist

新 rule:
`IExplosionGateway and IAreaEffectGateway implementations must not import
Bukkit Paper (ADR-0088)` — `combat.application.port.outbound.aoe.*` /
`combat.application.service.aoe.*` / `combat.domain.aoe.*` /
`combat.domain.event.aoe.*` の 4 package に Bukkit family import を
禁止。既存 "combat application must not import Bukkit family" rule の
specialised version として CI failure に scope を明示。

## Consequences

### Positive

- **Wave 6a Paper sweep の port surface 確定**: `PaperExplosionGateway` /
  `PaperAreaEffectGateway` / `PaperLightningGateway` /
  `PaperFireSpreadController` の 4 impl を priority 100 で register する
  だけで実装可能。
- **Folia 互換性の維持**: 全 4 port が region-aware scheduler を持たない
  ので、Phase 4 で `runRepeating(..., region, …)` 系 overload に置換しても
  caller は無変更で済む。
- **DamageSource exhaustive when の表現力向上**: `BukkitEventAdapter` は
  `DamageCause.LAVA` → `Environment.Fire(ticksOnFire = entity.fireTicks)`
  などの thin mapping だけ書けばよい。downstream listener (assist /
  achievement / analytics) は `is Environment.Lightning` のような
  pattern match で意図を表現できる。
- **`Fall` の概念的整理**: fall は environmental cause なので
  `Environment.Fall` の方が type taxonomy として正しい。

### Negative / Trade-offs

- **breaking rename 17 file**: `DamageSource.Environment` → `.Generic`、
  `DamageSource.Fall` → `Environment.Fall`。CLAUDE.md §"Pre-release
  posture" 下では許容範囲だが、Wave 5 並列 branch (5k / 5n / 5o / 5r /
  5t / 5u) に merge する際 conflict 確実。
- **InMemory impl の distance falloff 欠如**: `INearbyTargetQuery` が
  positions を返さないため、InMemory `IExplosionGateway` は uniform
  damage のみ。Wave 6a で per-target distance を計算して falloff 入れる
  予定。Phase 1 test では gateway lifecycle / event 発火 / Result 形を
  検証することに集中、damage curve の正確さは Paper 側で。
- **`AreaEffectAction.DealDamage` の applier wiring が Wave 5m では noop**:
  `TickAction` SPI は callable だが、Wave 5m 段階では combat
  `ApplyDamageUseCase` への配線がまだ無く、InMemory impl は `TickAction.Noop`
  default。Wave 6a で `ApplyDamageUseCase`-backed `TickAction` を bind。
- **`Environment` sealed 化により sub-variant の `when` 全網羅必要**:
  Wave 5m 時点では 7 variant (Generic + Fall + Drown + Fire + Lightning
  + Void + Suffocation)。新 environmental cause が増えると全 `when` を
  更新する必要 — compiler が catch するので safe だが churn コスト。

### Mitigations

- breaking rename の conflict は **Wave 5m を main に merge してから
  parallel branch を rebase** で解消 (parallel branch 側はまだ unmerged)。
- distance falloff 欠如は `ExplosionDamageApplier` SPI を `InMemoryExplosionGateway`
  に inject 可能にしてあり、必要なら test が flat-damage 以外の closure
  を渡せる。Wave 6a Paper impl は内部で per-target distance を引いて
  独自 applier を構築。
- `TickAction` の wire-up は Wave 6a Phase B-AOE 担当 worker の必須
  scope (handoff doc 側に explicit list 化予定)。
- exhaustive `when` の churn は Phase 1 内で acceptable; Phase 6 で
  sealed hierarchy の安定化が来たら slow down。

## Alternatives Considered

- **Alt A: AOE は既存 `MechanicDispatcher.AOE` 内で完結** — 上記 §Context
  参照。Folia 移行コストで却下。
- **Alt B: `DamageSource` を string-typed cause + payload map に変更** —
  Wave 5a 設計 (sealed) を覆す + exhaustive `when` を捨てる。pre-release
  でも明確に劣る選択肢。
- **Alt C: Explosion を `IBlockGateway` 経由の thin wrapper** — Wave 5d
  `IBlockGateway` は per-block CRUD で AOE 概念を持たない。multi-block の
  atomic な vanilla explosion algorithm を re-implement することになる。
- **Alt D: Lightning を Explosion の特殊 case として吸収** — vanilla
  `EntityDamageEvent.DamageCause.LIGHTNING` は別 cause なので Wave 6a の
  damage hijack 側で別 path に乗る。strike 自体に block damage 概念が
  無いため Explosion とは API shape が違う。

## References

- Wave 5m handoff: `/home/node/.claude/plans/wave-5g-core-hardening-giggly-sunset.md` §"Wave 5m"
- [ADR-0001](./0001-clean-architecture-with-minecraft-as-an-edge.md) — application API surface
- [ADR-0022](./0022-multi-plugin-portable-architecture.md) — sibling-isolation matrix
- [ADR-0030](./0030-pluggable-damage-rule-pipeline.md) — Combat damage pipeline (Wave 5a)
- [ADR-0063](./0063-damage-attribution-window.md) — Wave 5a-fix2 attribution
- [ADR-0077](./0077-mechanic-targeter-phase-skill-architecture.md) — Wave _skills-2 dispatcher
- [ADR-0082](./0082-entity-spawning-and-npc-two-tier.md) — Wave 5l prior port pattern
