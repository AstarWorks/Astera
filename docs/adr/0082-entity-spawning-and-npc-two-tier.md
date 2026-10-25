---
status: Superseded
date: 2026-05-21
deciders: Astera maintainers (Wave 5l)
supersedes: null
superseded_by: 0264
---

# ADR-0082: Entity spawning + NPC two-tier strategy (Minimal core / Citizens edge)

## Status

**Superseded by [[adr/0264-external-plugin-removal-and-single-npc-backend]]** (2026-10-26, Folia migration). Body below preserved per ADR convention; do not act on the design described here.

Originally: Accepted

## Context

Astera までの core API は、entity を spawn / despawn / teleport する port を
**持っていなかった**。Wave 5a で `IProjectileService` の draft、Wave 5g で
`IShopVendor` (NPC vendor binding only) が landed したが、両者とも entity の
生成側を vendor adapter 任せにしており、application layer が "world に新しい
mob / NPC / display を出現させる" 統一手段を持っていない。

Phase 1 PoC 終盤の以下の use case がこの欠落で詰まり始めていた:

1. **Wave 5l 以降の NPC vendor** — shop / loadout / tutorial / tournament queue
   いずれも "NPC を spawn → click を intent に紐付け" の同形 (Wave 5g
   `IShopVendor` は shop 専用)。共通の port 無しに 4 種実装すると、Wave 6b
   の Paper 実装側も 4 重 hook になる。
2. **Wave 5n / 5o の anomaly + arena event** — 一時的な mob 召喚 (SpawnSurge /
   ResourceSpike) は `IEntitySpawner` が無いと anomaly logic が直接 Bukkit を
   触ることになり、application layer の純粋性が壊れる。
3. **Wave 6a Paper Sweep の projectile** — `PaperProjectileService` は
   `IEntitySpawner` の projectile branch を priority-100 で overlay する想定
   だが、port そのものが無いので overlay 先が存在しない。

加えて、NPC vendor の **実装手段** について以下のトレードオフがあった:

- **Citizens** (de facto NPC plugin) は Paper エコシステムで圧倒的に普及して
  おり、機能 (skin / pathing / 各種 trait) が豊富。但し soft-dep。
- **vanilla ArmorStand / Display entity** だけで NPC 風 UI は組めるが、skin
  自由度や pathing が無い。
- 過去の Wave 5g `IShopVendor` ADR-0071 は "WeaponMechanics 内蔵 NPC は
  使わない" のみを decide。**Citizens vs ArmorStand の問題は未解決**だった。

Phase 1 PoC は "Citizens 無し vanilla Paper でも boot する" 不変条件
(CLAUDE.md §"Non-negotiable rules" #4) を持つので、**Citizens を hard-dep に
する選択肢は最初から無い**。一方で Citizens を完全に諦めて
ArmorStand-only にすると、production 環境で得られる機能を捨てることに
なる。

## Decision

`IEntitySpawner` を導入し、Paper 抽象 (`IPlayerGateway` / `IWorldGateway` 等)
と同じ pattern で entity 生成を vendor-neutral 化する。NPC については
**two-tier 構成** を採用する:

### 1. `IEntitySpawner` (vendor-neutral 単一前面)

```
spawn(world, position, SpawnSpec) → Result<EntityHandle, SpawnError>
despawn(handle) / teleport(handle, newPos) / all()
```

`SpawnSpec` は 5 variant の sealed interface:
- `Mob(typeKey, customNameKey?, noAi)`
- `ArmorStandNpc(displayName?, visible, itemInHand?)`
- `DisplayNpc(kind: HologramKind)`
- `CitizensNpc(typeKey, displayName?, skin?, traits)`
- `Projectile(typeKey, velocity, shooter?)`

実装: `RouterEntitySpawner` が priority-sorted `INpcAdapter` list + 内部
mob/projectile bookkeeping で各 variant を dispatch する。Wave 6a で
`PaperMobSpawner` / `PaperProjectileService` が priority-100 overlay として
入る。

### 2. `INpcAdapter` (NPC backend abstraction)

```
backendId() → NpcBackendId
supports(SpawnSpec) → Boolean
spawn(...) / applyTraits(...) / reload(handle) / despawn(handle)
```

**2 種類の adapter** を出荷:

- **`MinimalNpcAdapter`** (Core 内 always-available, `backendId = MINIMAL`):
  `ArmorStandNpc` / `DisplayNpc` のみ supports。Wave 6b で
  `PaperArmorStandSpawner` が実 entity を spawn する layer に置き換わる。
  Phase 1 PoC でも Citizens 不在環境でも **必ず動く**。
- **`CitizensNpcAdapter`** (Core skeleton, `backendId = CITIZENS`、Wave 5l
  時点で `isAvailable() = false`、Wave 6b で `PaperCitizensNpcAdapter` が
  override): 全 SpawnSpec NPC variant を supports。Citizens 不在時は
  `isAvailable() = false` で `supports()` が false を返し、router が
  Minimal にフォールバック。

**Priority**: Citizens (10) > Minimal (0)。Citizens が available なら NPC は
そちらに行き、無ければ Minimal で degrade。`CitizensNpc(traits)` のような
Citizens 必須 spec は Minimal が `supports = false` なので
`SpawnError.NoSpawnableBackend` が返る (silent fallback ではない)。

### 3. `INpcInteractionRouter` (Wave 5g `IShopVendor` 一般化)

`bindRightClick(handle, GuiIntent)` / `bindLeftClick(handle, GuiIntent)` /
`resolve(handle, click)`。Wave 5g `IShopVendor` (`VendorId → ShopId`) は
Shop 専用の薄い wrapper として保持し、generic な NPC vendor は本 router
を使う。`IShopVendor` を `INpcInteractionRouter` に refactor するのは
Wave 5o (GuiIntent surface stabilization) で再評価。

### 4. `IEntityRegistry` (vendor-neutral entity catalog)

`resolve(typeKey: String) → EntityTypeSpec?`、`all()`。Wave 5l ships
`InMemoryEntityRegistry` with starter catalog (vanilla mobs / projectiles /
display family / ArmorStand)。Wave 6a で `PaperEntityRegistry` が Bukkit
`EntityType` 全体を priority-100 で register。

### 5. Domain events

- `EntitySpawned(handle, world, position, category, atTick)`
- `EntityDespawned(handle, reason: {EXPLICIT, MATCH_LIFECYCLE, WORLD_UNLOADED, PLUGIN_DISABLE, DIED}, atTick)`
- `NpcInteracted(handle, playerId, click: {LEFT, RIGHT}, atTick)`
- `NpcBackendSwitched(oldHandle, newHandle, oldBackend, newBackend, atTick)`
  — Citizens install / uninstall 時の domain notification。

### Konsist enforcement

2 新 rule:

1. **`INpcAdapter implementations must declare backendId() uniquely`** —
   各 adapter は `companion object` に `public val BACKEND_ID:
   NpcBackendId` を declare する慣習で、Konsist が値の uniqueness を
   structural に検証する。adapter 追加時の silent collision を catch。
2. **`core.domain.entity.* must not import net.citizensnpcs.api.*`** —
   Citizens は soft-dep の edge plugin。domain に import が混ざると Citizens
   無し環境で Core が boot failure する regression を構造的に防ぐ。

## Consequences

### Positive

- **Phase 1 PoC が Citizens 不在環境で動く**: ArmorStand-backed NPC vendor
  が同じ `INpcInteractionRouter` API で透過的に動く。CI / 自動テストは
  Citizens を install せずに済む。
- **Production environment は Citizens の機能を捨てない**: Wave 6b で
  `PaperCitizensNpcAdapter` が `isAvailable=true` を返した瞬間、既存の
  `ArmorStandNpc` も自動で Citizens に格上げされる (RouterEntitySpawner の
  priority 順)。content / use case 側の書き換え不要。
- **`IShopVendor` を refactor せずに済む**: Wave 5g は Shop 専用のまま、Wave
  5l の generic NPC は本 router を使う。GuiIntent surface (Wave 5o) が
  stabilize したら一気に統合 (Wave 5o 後の re-evaluation)。
- **anomaly / arena event の spawn 経路が application 純化**: Wave 5n
  SpawnSurge / Wave 5o arena event hook が `IEntitySpawner` 経由 → Bukkit
  直接呼び出しが application layer から消える。
- **Wave 6a Paper Sweep の projectile が単一 port 置換で済む**:
  `PaperProjectileService` が `IEntitySpawner` を priority-100 で overlay
  するだけ。caller 側の変更不要。

### Negative

- **Wave 5l 単独では production 価値が薄い**: 実 entity は Wave 6a/6b の
  Paper adapter が来るまで spawn されない。Wave 5l は test layer + future
  shape commitment。
- **Citizens adapter の skeleton が dead code に見える**: `isAvailable()
  = false` が Wave 5l-6a 期間中 ずっと false。Konsist の adapter
  uniqueness test を成立させる為に必要 (両方の adapter が present でないと
  collision detection が成立しない)、と ADR で justify。
- **`NpcBackendSwitched` event が Phase 1 では fire しない**: Citizens
  install / uninstall を runtime detect する logic は Wave 6b 以降。Wave 5l
  時点では event の domain shape を確定するだけ。

### Neutral

- Wave 5g `IShopVendor` と本 wave の `INpcInteractionRouter` は coexist する。
  refactor は Wave 5o まで持ち越し (ADR 上明示)。
- `EntityHandle.backendId: String` は raw string で型は強くない。
  `NpcBackendId` value class は NPC adapter API surface のみで使う (handle 自体
  は mob / projectile 含む汎用 handle なので value class 化しない判断)。

## Alternatives considered

- **Citizens を hard-dep にして Minimal adapter を持たない** — Phase 1 PoC
  Citizens 無し boot 不変条件 (CLAUDE.md §"Non-negotiable rules" #4) と
  conflict。Reject。
- **`IShopVendor` を generic 化して `INpcInteractionRouter` を作らない** —
  Wave 5g のシンプルな `VendorId → ShopId` map が GuiIntent 表現に膨らんで
  逆に複雑化。Wave 5o の GuiIntent surface 再設計を Wave 5l に巻き込むのは
  scope creep。Reject — 二者を coexist させ Wave 5o で再評価。
- **`IEntitySpawner` を持たず、各 plugin が自前で
  `Bukkit.getServer().getWorld().spawn(...)` を呼ぶ** — Konsist の "domain
  must not import Bukkit" rule (ADR-0001) に violate。論外。Reject。
- **`SpawnSpec` を sealed にせず `SpawnRequest` data class + nullable fields
  で表現** — variant 増加時に router の `when` が exhaustive にならず
  silent miss を生む。Reject。

## References

- ADR-0001 — Clean Architecture, Minecraft as edge
- ADR-0002 — Vendor-neutral MC adapter
- ADR-0022 — Multi-plugin layout (sibling isolation)
- ADR-0072 — Pre-Paper-sweep port commitments
- ADR-0071 — Shop access surfaces (Wave 5g `IShopVendor`)
- Wave 5l handoff: `/home/node/.claude/plans/wave-5g-core-hardening-giggly-sunset.md` §"Wave 5l"
