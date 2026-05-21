---
status: Accepted
date: 2026-05-20
deciders: Wave 5k orchestrator
supersedes: null
superseded_by: null
---

# ADR-0080: Player physiology layer + Resource pool 3 種 (Annihilation vision)

## Status

Accepted

## Context

Astera は Phase 1 PoC closing 中で、Wave 5j で Custom HUD foundation が、Wave 5l で
Entity spawning が並走中。Wave 5k はその次の核心:

- **Bukkit runtime player-state mutation** が必要 (admin / system / respawn / heal /
  skill effect / debug 系 — combat pipeline と別の path)
- **Annihilation server vision** (`.claude/plans/idea-annihilation-vision.md` 確定 2026-05-20)
  に従って **per-weapon resource 三次元** (銃 Ammo / 剣 Stamina / 魔法 Mana) を
  Phase 1 で commit する。Class system は採らず、武器選択で role 表現する Astera 独自色

既存の状態管理 layer は 3 層存在し、新 layer の位置付けを明確化しないと「damage は
どこを通る?」が曖昧化する:

| 層 | 担当 | 起源 |
|---|---|---|
| Combat pipeline | `IDamageGateway` + status / armor / projectile / knockback resolution | Wave 5a (ADR-0030) |
| Astera attribute layer | `IAttributeContainer` — abstract `max_hp` / `crit_chance` etc. + modifier stacking (SET/ADD/MULTIPLY) | Wave 5e (ADR-0058) |
| Bukkit runtime mutation | **本 wave** — `AttributeInstance.GENERIC_MAX_HEALTH` / `Player.walkSpeed` / `Player.foodLevel` 等の direct setter wrap | Wave 5k (本 ADR) |
| Resource pool (depletable) | **本 wave** — per-tick consumable `Ammo` / `Stamina` / `Mana` counters | Wave 5k (本 ADR) |

両者を ADR で同時に commit するのは:

1. 物理的に隣接する場所に touch する (両方とも player-id-indexed runtime state)
2. 「健康・速度・食料は health gateway、attribute は IAttributeContainer、ammo/stamina/mana
   は resource pool」の三角形を 1 つの ADR で book-keeping したい
3. Annihilation vision の確立日 (2026-05-20) と同日

Wave _items-1 (ADR-0073) で導入された `ItemSpec` を per-weapon resource 三次元の
declaration site にする (各武器が `resourceType: Ammo|Stamina|Mana` を宣言)。

## Decision

### §1. Three-tier player state layer

**Combat pipeline ≠ Bukkit runtime mutation ≠ Astera attribute layer.** 三層分離を
sealed source variant で type-system 強制する:

- `IDamageGateway` (Wave 5a) は combat / status / armor / projectile resolution
- `IPlayerHealthGateway` (本 wave) は admin / heal / respawn / scripted death の
  **direct mutation**。`HealthChangeSource` sealed で source variant を発行
- `IAttributeContainer` (Wave 5e) は abstract numeric attributes + modifier 体系。
  Wave 6a `PaperPlayerHealthGateway` adapter が `IAttributeContainer` を read 後に
  Bukkit に write-through する単一統合点

`IPlayerSpeedGateway` (walk / fly / sprint / flight / noClip with TTL) と
`IPlayerFoodGateway` (suppressHungerHud / setHungerAsMana / setFoodLevel) は
同じ "Bukkit runtime mutation" tier に属する併設 port。

### §2. Resource pool 3 種 (Annihilation vision)

```kotlin
public sealed interface ResourceType {
    public data object Ammo : ResourceType      // 銃: 有限、reload で full
    public data object Stamina : ResourceType   // 剣: swing 消費、待機 regen
    public data object Mana : ResourceType      // 魔法: per-tick regen pool
}

public data class PlayerResourcePool(
    val playerId: PlayerId,
    val activeWeapon: ItemId?,
    val ammo: Int,
    val stamina: Int,
    val mana: Int,
)
```

3 種を player ごとに **常に track**。`activeWeapon` で HUD render 対象を選ぶ
が、weapon swap で counter が消えるわけではない。Multi-weapon hot-bar shifting
が安全に行える。

`ItemSpec` (Wave _items-1) を拡張:

```kotlin
val resourceType: ResourceType? = null,    // 武器のみ declare
val resourceCostPerUse: Int = 0,
val resourceMaxPool: Int = -1,              // -1 = use type default
val resourceRegenPerSecond: Double = -1.0,  // -1.0 = use type default
val resourceReloadable: Boolean = false,
```

`IResourcePoolService` が spend / reload / regen API を提供、
`IResourceRegenScheduler` が `IScheduler.runRepeatingTicks(1, 1)` から driven。
Sub-integer regen rate (e.g. Mana = 5/sec = 0.25/tick) は per-(player × type)
double accumulator で whole-integer flushing。

### §3. Konsist enforcement

新 rule:

- `Resource domain must not import Bukkit Paper Adventure (ADR-0080)` —
  `core.domain.resource.*` から Bukkit family + `net.kyori.adventure.*` を禁止。
  `org.joml.*` は依然 OK (Wave 5h-fix の math-library 例外)
- `Player physiology domain must not import Bukkit Paper (ADR-0080)` —
  `core.domain.player.physiology.*` も同じ Bukkit-free。Source variant は
  pure value object

### §4. Phase 1 scope freeze

- Resource type は **3 種以外追加しない** (`Heat` / `Charge` / `SpellPoint` 等は
  Phase 5+ で別 ADR + supersede)
- Reload action UX (R key? 自動?) は Wave 5l / 6a / 6b で別途決定 — 本 ADR は
  service surface のみ
- Per-weapon resource pool **independent** (各武器が独立 counter ではなく、
  player ごとに 3 種 1 セット)。Multi-weapon switching でも resource は連続的に
  保たれる
- Spectator-mode toggle は別 port (`ISpectatorGateway`, Wave 5h-related); 本
  ADR の `IPlayerSpeedGateway.setNoClip` は "admin walks through walls without
  changing gamemode" 用途のみ

## Consequences

### Positive

- **Combat / admin / attribute 三層の明確化** — listener tier が source
  variant で exhaustive switch、reflection-based discrimination を avoid
- **Annihilation server identity が type-system に commit** された。Wave 5j HUD
  example が 3 種 (Ammo/Stamina/Mana) どれかを active weapon の `resourceType`
  から自動選択できる
- **Phase 1 PoC で銃剣魔法 hybrid のコア loop が走る**:
  `player.spendResource(Mana, 20)` → cast skill →
  `IResourceRegenScheduler.tick()` で `5/sec` 戻り → next cast 可
- **Wave 6a Paper adapter の境界が単純** — Bukkit attribute write は
  `PaperPlayerHealthGateway` 1 箇所のみ
- **YAML schema が clean** — 武器作者は `resource_type: ammo` + `resource_cost_per_use: 1`
  を書くだけ。`resource_max_pool: -1` で type default 享受

### Negative / Trade-offs

- **`ItemSpec` field 5 個増** — 全 item は 5 新 field を必須 carry (defaults はあるが
  schema 表面積は増える)。後で `WeaponSpec` を separate 化する選択肢は失う
- **3 種 hard-coded** — `ResourceType.all` は immutable list。新 type 追加には
  本 ADR の supersede + Konsist test 更新 + balance YAML 拡張が要る
- **Per-player 3 counter 常 alloc** — 1000 player でも 1000 × 3 = 3000 Int の
  hot map (~24KB)、無視できる
- **Wave 5j HUD と Wave 5l Entity 並走の foundation 重なり** — RestoreInventory
  listener tier が膨らむ (per-wave hook を増やしている)。Wave 6a で
  PlayerInteractEntityEvent listener 統合の機会に refactor 候補

### Mitigations

- **後方互換性は要求しない** (CLAUDE.md §Pre-release posture)。設計が悪ければ
  Phase 1 完了前に supersede ADR で書き換える
- **Capture-only Fake** (`FakeResourcePoolService` / `FakePlayer*Gateway`) を
  test-fixtures に置き、use-case test は scheduler / eventBus 抜きで書ける
- **balance YAML loader はまだ未配線** (`content/balance/resource-defaults.yaml`
  はコンパイル defaults を mirror)。Wave 5n の per-match overlay と同時に landing
  予定

## Alternatives Considered

- **Alt A: Single `IPlayerStateGateway` umbrella port** — health / speed / food を
  1 ポートに統合。**却下**: 24 method の swiss-army interface になる + Bukkit
  attribute と FoodLevelChangeEvent は意味的に独立なので mock 単位も別が良い
- **Alt B: Resource pool を `IAttributeContainer` の特殊 modifier として実装** —
  「Stamina = AttributeKey("stamina_current")」。**却下**: attribute は static-ish
  max with modifier stacking、resource は per-tick depleting counter で挙動が
  完全に違う (modifier の SET/ADD/MULTIPLY semantics は spend に noise)
- **Alt C: per-weapon independent counters** — 各 ItemId ごとに pool を持つ。
  **却下**: Multi-weapon switching でリロード状態が分散、メモリ overhead が
  scope に対して過大。player-level 3 種 + activeWeapon binding が最小 viable
- **Alt D: ADR-0079 番号** — 当初 Wave 5l (Entity spawning) 用に reserved だったが、
  parallel session 進行中に "studio extract" 用途に 0079 が reassigned された
  ため 0080 に slot。本 ADR の決定内容は番号変更で影響しない

## References

- 関連 ADR: [[adr/0030-combat-pipeline]], [[adr/0058-player-attribute-container]],
  [[adr/0072-pre-paper-sweep-port-commitments]], [[adr/0073-item-centric-content-model-and-skill-binding]]
- 関連 docs: `.claude/plans/idea-annihilation-vision.md`,
  `.claude/plans/wave-5g-core-hardening-giggly-sunset.md` §"Wave 5k"
- 関連テスト: `tools/architecture-test/.../ArchitectureTest.kt` (本 ADR で
  2 rule 追加)
