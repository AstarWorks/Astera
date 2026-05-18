# Astera × RTM Parity Tracker

> Single-page master tracker for "what RTM has" × "what Astera has built" ×
> "which Wave delivers the rest" × "priority". Updated per wave; living doc.

**Baseline**: Wave 5a (Combat depth) land — SkillCast hollow gap 解消 + Armor + DamageRule pipeline + rich damage events + ADR-0030 Bukkit hijack 方針コミット。
**RTM reference**: [ADR-0029 RTM Empirical Catalog](adr/0029-rtm-empirical-catalog.md) +
[architecture/rtm-divergence.md](architecture/rtm-divergence.md).
**Roadmap structure**: [ADR-0028 Roadmap Realignment](adr/0028-roadmap-realignment-paper-as-edge.md).
**Implementation order (master)**: [IMPLEMENTATION-ROADMAP.md](IMPLEMENTATION-ROADMAP.md) —
12 tier leverage-ordered, phase-by-phase, with cumulative parity milestones
and parallelization windows.

> **How to read**: this tracker says **what** is left; IMPLEMENTATION-ROADMAP
> says **in what order**. Use the master roadmap to decide which wave to
> trigger next; use this tracker to verify "did the wave land everything in
> its column".

## Wave 5a Phase A prep — Tier 0 foundational primitives

The next session (Wave 5a Combat depth) folds **Tier 0 foundational primitives**
into its Phase A (me solo, ~40min before workers spawn):

- `Vec3` / `MutableVec3` math operators: `normalize` / `dot` / `cross` /
  `length` / `distance` (knockback formula needs these)
- `IMcEvent` 6 new variants: `PlayerJoined`, `PlayerQuit`, `EntityDamaged`,
  `EntityDied`, `BlockPlaced`, `BlockBroken` (consumed by Wave 5a damage
  attribution + Wave 5c game-mode listeners + Wave 5d block tracking)

## Wave 5f Phase A prep — CEL library dependency

Wave 5f introduces CEL (Common Expression Language) for skill extensibility.
Phase A decides between:

1. **JCEL** (org.projectnessie:cel-jackson + cel-tools) — mature, JVM-friendly
2. **proxima JCEL** — alternate
3. **Self-built mini-parser** — domain-fit DSL, no upstream dep risk

Decision recorded in ADR-0035. Library dep added to `gradle/libs.versions.toml`
in Wave 5f Phase A.

## Wave 6 Phase A prep — Lettuce + HTTP client

Wave 6b extends `IBroadcaster` to real Redis + Discord webhook impls. Phase A
adds to `gradle/libs.versions.toml`:

- **Lettuce** (Redis client) — for pub/sub fan-out
- **okhttp3** or `java.net.http.HttpClient` (built-in) — for Discord webhook POST

## Legend

- **状態**:
  - ✅ **full** — domain + application + persistence + (if applicable) Paper impl all done
  - 🟨 **partial** — some layer done, some missing
  - 🔌 **port-only** — interface declared, in-memory or Fake impl exists, **no Paper impl yet**
  - ❌ **nothing** — not in the codebase
- **優先度**:
  - **P0** — critical for Phase 1 PoC (1 weapon fires end-to-end)
  - **P1** — required for Phase 3 Siege Warfare gameplay
  - **P2** — Phase 4-5 economic / social enrichment
  - **P3** — Phase 6-7 long-tail (Gen AI, certification, etc.)
- **Wave 割当**:
  - **(済)** = already landed (Wave 1-4.5)
  - **5a** = Combat depth (skill dispatch + Armor + DamageRule depth)
  - **5b** = HUD ports (BossBar / Scoreboard / TabList / ActionBar / Title)
  - **5c** = Game mode framework + 6 game modes + Star Generator + Anomaly
  - **5d** = Stage geometry + Block tracking + Barrier + Schematic
  - **5e** = Session + Social + Tutorial + EnderChest + Reward bridge
  - **5f** = Marketplace + JP currency compliance + CEL expression
  - **6** = Paper Sweep (all Bukkit impls at once)
  - **Phase 4+** = post-Siege roadmap

## Summary metrics

| Status | Count | % |
|---|---|---|
| ✅ full | ~20 | 22% |
| 🟨 partial | ~6 | 6% |
| 🔌 port-only | ~6 | 6% |
| ❌ nothing | ~62 | 66% |
| **Total tracked** | **~94** | 100% |

(Wave 4.8 で +7 行追加: Inventory snapshot 2 + Shop expansion 4 + Gacha 1 reclassified)

**Phase parity targets** (cumulative):
- Wave 4.5: ~15% RTM parity
- Wave 5a (now): **~22% RTM parity** (Category 1 Combat 9 行 ❌→✅)
- Wave 5a-5f complete: ~45% RTM parity
- Wave 6 (Paper Sweep) complete: ~65% RTM parity (Phase 1 PoC end-to-end)
- Phase 3 Siege Warfare complete (Wave 6 + content): ~75%
- Phase 4 (UGC + Web + Vault + Gacha): ~85%
- Phase 5+ (Metaverse): >100% (Astera adds beyond RTM)

---

## Category 1: Combat (Wave 5a + Wave 6 Paper)

| RTM 機能 | Astera 現状 | Wave 割当 | 優先度 | 根拠 |
|---|---|---|---|---|
| 6 loadout slots (MAIN/SUB/MOVE/SPECIAL/ARMOR/ULTIMATE) | ✅ full + Postgres 永続化 (Wave 4.5) | (済) | P0 | `plugin/core/.../domain/player/Loadout.kt`, `LoadoutSlot.kt`, `V5__loadouts.sql` |
| 31 easing curves | ✅ full | (済) | P0 | `plugin/core/.../domain/animation/Easing.kt` |
| Weapon archetype (sword / gun / wand) | ✅ enum full | (済) | P0 | `plugin/weapons/.../domain/weapon/WeaponSpec.kt` |
| Rarity tiers | ✅ enum full | (済) | P0 | same |
| Damage attribute / element (6 種 PHYSICAL/FIRE/ELECTRIC/WIND/WATER/EARTH) | ✅ full | (済) | P0 | `plugin/core/.../domain/damage/DamageAttribute.kt` |
| Element matrix (6 種 rock-paper-scissors) | ✅ full | (済) | P0 | `content/balance/element-matrix.yaml` |
| Damage source sealed (Environment / Weapon / Skill / Fall / Explosion) | ✅ full | (済) | P0 | `plugin/combat/.../domain/damage/DamageSource.kt` |
| Damage application via `IDamageGateway` | 🔌 port-only | **Wave 6** | P0 | `plugin/combat/.../application/port/outbound/IDamageGateway.kt` (no Paper impl) |
| **Bukkit damage + death hijack policy (ADR-0030 §8)** | 📋 ADR commitment (Wave 6 impl) | Wave 6 | P0 | ADR-0030 §8 — EntityDamageEvent / PlayerDeathEvent 全 hijack方針 |
| Status effect kinds (16 enum incl. STAR_GAIN/HEAL_AMP/GRAVITY/CT_RATE/SPEED/PARALYSIS/BURN/JUMP_DISABLED + more) | ✅ enum + spec | (済) for domain | P0 | `plugin/combat/.../domain/status/StatusKind.kt` |
| StatusEffectInstance | ✅ full | (済) | P0 | `plugin/combat/.../domain/status/StatusEffectInstance.kt` |
| Status effect application (`IStatusEffectContainer`) | 🔌 port-only | **Wave 6** | P0 | `plugin/combat/.../application/port/outbound/IStatusEffectContainer.kt` |
| Status effect tick loop | ❌ nothing | **Wave 6** | P0 | (no scheduler tick driving it) |
| Cooldown (per-weapon + per-skill) | ✅ in-memory full | (済) | P0 | `InMemoryCooldownTracker.kt` |
| `TickCooldownUseCase` scheduler 配線 | 🔌 wired in plugin, scheduler 未起動 | **Wave 6** | P0 | use case exists, `runRepeatingTicks` 未配線 |
| `CastSkillUseCase` (publish-only) | ✅ publishes `SkillCast` | (済) | P0 | `plugin/combat/.../application/usecase/skill/CastSkillUseCase.kt` |
| **Skill effect dispatch (`SkillCast` subscriber)** | ✅ full (Wave 5a) — `SkillCastDispatcher` (6 archetype handler) | (済 Wave 5a) | **P0** | `plugin/combat/.../application/usecase/skill/dispatch/SkillCastDispatcher.kt` |
| Skill archetype (PROJECTILE/MELEE/AOE/BUFF/TELEPORT/SUMMON) | ✅ enum + 5 dispatch impl + 1 stub (SUMMON Wave 6) | (済 Wave 5a; SUMMON Wave 6) | P0 | `plugin/combat/.../application/usecase/skill/dispatch/` |
| Skill trigger (LEFT_CLICK/RIGHT_CLICK/SNEAK_LEFT/SNEAK_RIGHT/HOLD/AUTO) | ✅ enum | (済) | P0 | `SkillTrigger.kt` |
| **Armor domain (ArmorSpec / ArmorSlot / damage reduction / set bonus)** | ✅ full (Wave 5a) — 4 slot + 加算 stack + 80% cap + set bonus | (済 Wave 5a) | **P1** | `plugin/combat/.../domain/armor/` + `ArmorReductionModifier` |
| **Knockback formula** | ✅ Wave 5a (`KnockbackModifier` + `IKnockbackGateway` port、Vec3-direction) | (済 Wave 5a; Paper impl Wave 6) | P1 | `plugin/combat/.../domain/rule/KnockbackModifier.kt` |
| **Critical strike (chance + multiplier)** | ✅ Wave 5a (`CriticalStrikeModifier` + `IDamageRandomizer` deterministic test) | (済 Wave 5a) | P1 | `plugin/combat/.../domain/rule/CriticalStrikeModifier.kt` |
| **Friendly-fire toggle** | ✅ Wave 5a (`FriendlyFirePolicy` + `IMatchTeamLookup` port) | (済 Wave 5a) | P1 | `plugin/combat/.../domain/rule/FriendlyFirePolicy.kt` |
| **Skill resource cost (mana / energy)** | ✅ Wave 5a (`ManaPool` domain + `ConsumeManaUseCase` + `SkillSpec.manaCost`) | (済 Wave 5a) | P2 | `plugin/combat/.../domain/resource/ManaPool.kt` |
| **Charged skill / hold-to-cast** | ✅ Wave 5a (`ChargedSkillVariant` sealed + `ChargedSkillCastUseCase`) | (済 Wave 5a) | P2 | `plugin/combat/.../domain/skill/ChargedSkillVariant.kt` |
| Projectile motion (5 sealed: Linear / Ballistic / Homing / Curved / Beam) | ✅ enum, impl ❌ | **Wave 6** | P0 | `plugin/weapons/.../domain/projectile/MotionProfile.kt` |
| Hit profile (3 sealed: Sphere / Box / Sweep) | ✅ enum, impl ❌ | **Wave 6** | P0 | `HitProfile.kt` |
| OnHitEffect (6 sealed: DealDamage / Explode / InflictStatus / SpawnChild / PlaySound / SpawnParticle) | ✅ enum, dispatch ❌ | **Wave 6** | P0 | `OnHitEffect.kt` |
| Vec3 math (normalize / dot / cross / length / distance) | ✅ Wave 5a Tier 0 prep + `MutableVec3` in-place 同等 | (済 Wave 5a) | P0 | `plugin/core/.../domain/geometry/Vec3.kt` |
| **CEL expression language for skill** (RTM issue #21) | ❌ nothing | **Wave 5f** | P3 | (none) |
| **Particle definition system** (RTM issue #21) | ❌ nothing | **Wave 5f** | P3 | (none) |

---

## Category 2: Match flow & 6 game modes (Wave 5c)

| RTM 機能 | Astera 現状 | Wave 割当 | 優先度 | 根拠 |
|---|---|---|---|---|
| Match phases (WAITING / COUNTDOWN / ACTIVE / SUDDEN_DEATH / ENDED) | ✅ enum + state machine | (済) | P0 | `plugin/core/.../domain/match/MatchPhase.kt`, `MatchStateMachine.kt` |
| Phase auto-timer (COUNTDOWN → ACTIVE @ 30s, etc.) | ✅ full (Wave 4.5) | (済) | P0 | `PhaseTimerUseCase.kt` + `runRepeatingTicks(1, 20)` wire |
| Victory condition evaluator (score threshold 10) | ✅ full (Wave 4.5) | (済) | P0 | `VictoryConditionEvaluator.kt` |
| Match domain (Match / MatchId / Score / TeamId) | ✅ full | (済) | P0 | `plugin/core/.../domain/match/` |
| **6 game modes** (Battle Loyal / Break Core / Carry TNT / Carry Mini TNT / Capture Wool / Steal Jewelry) | ❌ **all nothing** | **Wave 5c** | **P1** | RTM issue + README; grep → 0 件 |
| Game mode framework (sealed `GameMode` + per-mode listener) | ❌ nothing | **Wave 5c** | P1 | (none) |
| Sudden death mechanics (accelerated generator / no respawn / forced engagement) | 🟨 phase 名のみ、auto-timer ✅、特殊挙動 ❌ | **Wave 5c** | P1 | only timer transition implemented |
| Team domain (Team / TeamId / colorKey / members) | ✅ minimal | (済) | P0 | `Team.kt` |
| Team auto-balance | ❌ nothing | Wave 5c | P2 | (none) |
| Match command surface (`/astera-match start/end/entry/phase/list`) | ✅ full | (済) | P0 | `AsteraMatchPlugin.kt` |

---

## Category 3: World / Stage / Geometry (Wave 5d + Wave 6)

| RTM 機能 | Astera 現状 | Wave 割当 | 優先度 | 根拠 |
|---|---|---|---|---|
| Stage domain + StageId + Region (AABB) | ✅ full | (済) | P0 | `Stage.kt`, `StageId.kt`, `Region.kt` |
| Stage YAML loader | ✅ full | (済) | P0 | `StageLoaderService.kt` + `StageYamlConfig.kt` |
| **SpawnPoint** (Attacker / Defender / Lobby / Spectator sealed) | ✅ full (Wave 4.5) | (済) | P0 | `SpawnPoint.kt`, `Stage.spawnPoints` field, `MatchSpawnUseCase.kt` |
| `IWorldGateway.teleport()` port | ✅ port + Fake (Wave 4.5) | (済 port; impl は **Wave 6**) | P0 | `IWorldGateway.kt`, `FakeWorldGateway.kt` |
| `IWorldGateway` 残メソッド (placeBlock / removeBlock / blockAt / spawnParticle / playSound) | ✅ port, impl ❌ | **Wave 6** | P0 | `IWorldGateway.kt` |
| **Block tracking domain** (BlockPlaced / BlockBroken event + per-match state) | ❌ nothing | **Wave 5d** | P1 | (RTM issue #9) |
| **Block durability** (max 100 HP, tool-dependent damage, NBT-backed) | ❌ nothing | **Wave 5d** | P1 | (RTM `BlockState.md` Writerside doc) |
| **Block pseudo-recovery** (timestamp decay) | ❌ nothing | Wave 5d | P1 | (same) |
| **Barrier system** (custom block enclosure + DisplayEntity visual + scheduler status changes) | ❌ nothing | **Wave 5d** | P1 | (RTM issue #8) |
| **Custom machines (Redstone dispenser + skill core + traps)** | ❌ nothing | Wave 5d | P2 | (RTM issue #10) |
| Schematic spec / WorldEdit `.schem` loader | ❌ nothing | **Wave 5d** (domain) + **Wave 6** (Paper FAWE adapter) | P1 | `IWorldDirManager.kt` vacant port |
| Per-match world isolation (slime-world / multiverse-style) | ❌ nothing | **Wave 5d** (domain) + **Wave 6** (Paper) | P1 | (none) |
| Capture zone / KOTH region type | ❌ nothing | **Wave 5d** | P1 | (no `CaptureZone` domain type) |
| Block restoration after match end | ❌ nothing | **Wave 5d** | P1 | depends on block tracking |
| Region type variants (protected / buildable / spawn / no-build) | ❌ Region is plain AABB | **Wave 5d** | P1 | `Region.kt` has no type tag |

---

## Category 4: HUD ports (Wave 5b + Wave 6 Paper)

| RTM 機能 | Astera 現状 | Wave 割当 | 優先度 | 根拠 |
|---|---|---|---|---|
| **`IBossBar` / `IBossBarManager` port** | ❌ nothing | **Wave 5b** | **P1** | grep `IBossBar` → 0 件 |
| **`IScoreboard` / `ITeamScoreboard` port** | ❌ nothing | **Wave 5b** | P1 | grep `IScoreboard` → 0 件 |
| **`ITabList` port** | ❌ nothing | Wave 5b | P2 | (none) |
| **`IMcPlayer.sendActionBar(...)` method** | ❌ nothing | **Wave 5b** | P1 | signature missing in `IMcPlayer.kt` |
| **`IMcPlayer.sendTitle(...)` method** | ❌ nothing | **Wave 5b** | P1 | same |
| **CT / Star / Level HUD layout** (RTM issue #14) | ❌ nothing | Wave 5b (domain) + **Wave 6** (Paper impl) | P1 | (RTM uses Scoreboard Library 2.1.3) |
| Scoreboard Library 2.1.3 dep (RTM) | (Astera self-builds via `IScoreboard` port) | Wave 6 (Paper impl chooses library) | P1 | ADR-0015 D path |
| Inventory Framework 0.10.13 dep (RTM) | (Astera self-builds via `IGuiAdapter`) | ✅ Wave 4 (full) | P0 | ADR-0021 + Wave 4 ✅ |
| Declarative GUI (Wave 4) | ✅ full | (済) | P0 | Wave 4 ✅ |
| Reactive GUI binding (`player.loadout.main`) | ✅ ItemId 系のみ (Wave 4.5) | (済) | P0 | `DefaultGuiBindingResolver.kt`; 数値 binding は **Wave 5f** で `ResolvedReactiveSlot` 拡張 |
| Reactive GUI binding (numeric / display override) | ❌ nothing | Wave 5f | P2 | (none) |

---

## Category 5: Star Generator + Anomaly (Wave 5c)

| RTM 機能 | Astera 現状 | Wave 割当 | 優先度 | 根拠 |
|---|---|---|---|---|
| **Star Generator domain** (BedWars-style spawner, position + currency + period + amount) | ❌ nothing | **Wave 5c** | **P1** | grep `StarGenerator|Generator` → comments only |
| **Star Generator scheduler** | ❌ nothing | Wave 5c | P1 | (would use existing IScheduler) |
| **Anomaly type sealed + AnomalyScheduler** | ❌ nothing | **Wave 5c** | **P1** | grep `Anomaly` → 0 件 |
| Anomaly random in-match events | ❌ nothing | Wave 5c | P1 | (none) |
| Resource decay (time-based) | ❌ nothing | Wave 5c | P2 | (none) |
| `IScheduler.runRepeating` (infra) | ✅ full | (済) | P0 | `PaperScheduler.kt`, ADR-0019 |

---

## Category 6: Economy / Shop / Marketplace (Wave 4.5 ✅ + Wave 5e + 5f)

| RTM 機能 | Astera 現状 | Wave 割当 | 優先度 | 根拠 |
|---|---|---|---|---|
| Currency / Wallet / 3 currency (STAR / GOLD / GEM) | ✅ full + Postgres | (済) | P0 | `Currency.kt`, `Wallet.kt`, `V3__wallets.sql` |
| Deposit / Withdraw / Transfer (atomic) | ✅ full | (済) | P0 | `DepositUseCase.kt`, etc. |
| Shop domain (`ShopSpec` / `ShopOffer` / `ShopPrice` / `ShopStock`) | ✅ full (Wave 4.5) | (済) | P0 | `plugin/core/.../domain/shop/` |
| `IShopRegistry` + `ShopLoaderService` + `ShopPurchaseUseCase` | ✅ full | (済) | P0 | (Wave 4.5) |
| `/astera-shop browse` command + intent dispatch | ✅ full | (済) | P0 | (Wave 4.5) |
| Reward listeners (kill / match-win / mission → currency) | ✅ full | (済) | P0 | (Wave 1-2) |
| **Ender Chest / Personal Vault** (RTM issue #3) | ❌ nothing | **Wave 5e** | **P1** | grep `EnderChest|PersonalVault` → 0 件 |
| **Reward 2-server flow** (game→Redis→lobby、RTM issue #6) | ❌ nothing | **Wave 5e** | P1 | (none — Astera's current flow is single-server in-process) |
| **Player marketplace** (RTM issue #12) | ❌ nothing | **Wave 5f** | P2 | (none) |
| **Creator revenue sharing** (RTM issue #12) | ❌ nothing | Wave 5f | P2 | (none) |
| **Battle pass** (RTM issue #12) | ❌ nothing | **Phase 4 (Wave 8e)** | P2 | (none) |
| **Cosmetics (skin / emote / BGM packs)** (RTM issue #11) | ❌ nothing | **Phase 4 (Wave 8e)** | P2 | (none) |
| **JP regulatory currency compliance** (RTM issue #19: 6mo OR ¥10M cap, non-redeemable, dual-currency) | ❌ nothing | **Wave 5f** | **P2 (legal)** | (none) |
| Bank / multi-currency account view | ❌ nothing | Phase 4 | P3 | (none) |
| **Gacha / Roll system (50% pull-up per RTM README)** | ❌ nothing | **Phase 4 (Wave 8e)** | P2 | (none — RTM README documented "天井確率") |
| **ShopCategory** (タブ式 / カテゴリ別 browse) | ❌ nothing | **Phase 4 (Wave 8e)** | P2 | Wave 4.5 Shop は flat ShopSpec のみ |
| **Shop rotation / limited-time offers** | ❌ nothing | **Phase 4 (Wave 8e)** | P2 | `ShopOffer.availableUntil` 不在 |
| **Multi-currency price** (e.g., 100 STAR + 1 GEM) | ❌ nothing | **Phase 4 (Wave 8e)** | P2 | `ShopPrice` → `MultiPrice(List<ShopPrice>)` sealed 拡張 |
| **Per-player purchase limits** | ❌ nothing | **Phase 4 (Wave 8e)** | P3 | (none — RTM limited-offer 表現に必要) |

---

## Category 7: Stats / Progression / Tutorial (Wave 5e)

| RTM 機能 | Astera 現状 | Wave 割当 | 優先度 | 根拠 |
|---|---|---|---|---|
| PlayerStats domain (K/D/A/damage/playtime/matches/wins/losses) | ✅ full + Postgres | (済) | P0 | `PlayerStats.kt`, `V1__player_stats.sql` |
| XP / Level / XpCurve | ✅ full | (済) | P0 | `Xp.kt`, `Level.kt`, `XpCurve.kt` |
| Norma (mission) domain + 4 NormaTarget variant + reward | ✅ full | (済) | P0 | `plugin/core/.../domain/norma/` (Wave 4.5 で core 移動) |
| Norma progress 永続化 | ✅ full (Wave 4.5) | (済) | P0 | `V6__norma_progress.sql` |
| `MissionProgressOnKillListener` 配線 | ✅ full | (済) | P0 | (Wave 2) |
| **Achievement / Title / Badge** (Norma とは別) | ❌ nothing | **Wave 5e** | P2 | (none) |
| **Tutorial system** (RTM issue #17) | ❌ nothing | **Wave 5e** | P2 | (none) |
| **Personal settings DB** (RTM issue #15) | ❌ nothing | **Wave 5e** | P2 | (none) |
| **Universal Attribute Container (ADR-0058)** — Player/Item/Mob 共通 attribute storage | 📋 ADR-0058 commitment (Wave 5a-fix); Wave 5b read shim → Wave 5e migration → Wave 5d/6b mob/item-instance | **Wave 5b** (shim) / **Wave 5e** (本実装+migration) / **Wave 6b** (item-instance PDC) | **P1** | ADR-0058 §5 rollout schedule。`Wallet` / `PlayerStats` / `ManaPool` / `ArmorReduction.perAttribute` を `IAttributeContainer` backing に統合。Wave 5a-fix で commitment 確定、Wave 5e で V14 migration + facade rewrite |
| Leaderboard ranking | ❌ nothing | Phase 4 | P3 | (none) |
| **RTM 検定後継 (格付け / 称号)** | ❌ nothing | Phase 7 | P3 | (roadmap.md Phase 7) |

---

## Category 8: Permission / Moderation / Session (Wave 5e)

| RTM 機能 | Astera 現状 | Wave 割当 | 優先度 | 根拠 |
|---|---|---|---|---|
| RBAC (RoleDefinition / Permission / DefaultRoles) | ✅ full + Postgres | (済) | P0 | `V4__permissions.sql` |
| Permission keys (14 declared) | ✅ full | (済) | P0 | various `.requirePermission(...)` |
| Console fallback for commands | ✅ full | (済) | P0 | `CommandPermission.kt` |
| **Spectator mode** (player session state: InMatch / Lobby / Spectator / Dead) | ❌ nothing | **Wave 5e** | P2 | (none) |
| **Vanish / invisibility** (admin tool) | ❌ nothing | Wave 5e | P3 | (none) |
| **Friend list / Party** (social) | ❌ nothing | **Wave 5e** | P2 | (none) |
| **Team chat channel** | ❌ nothing | Wave 5e | P2 | (none) |
| **Audit log domain + table** | ❌ nothing | **Wave 5e** | P2 | (none) |
| **Player inventory snapshot save/restore (RTM issue #5)** | ❌ nothing | **Wave 5e (domain + V10)** + **Wave 6b (Paper serializer)** | **P1** | grep `InventorySnapshot|PlayerInventory` → 0 件 |
| **InventoryActionLock** (load 中 player action 制限、RTM issue #5) | ❌ nothing | **Wave 5e (PlayerSession.Loading state)** + **Wave 6b (Bukkit cancel)** | **P1** | (none) |
| Ban / Mute lists | ❌ nothing | **Phase 4 (Wave 8f)** | P2 | (none) |
| Login history table | ❌ nothing | Phase 4 | P3 | (none) |

---

## Category 9: External / Network / Broadcaster (Wave 6)

| RTM 機能 | Astera 現状 | Wave 割当 | 優先度 | 根拠 |
|---|---|---|---|---|
| `IBroadcaster` port | ✅ port | (済) | P0 | `IBroadcaster.kt` |
| `PaperBroadcaster` log-only impl | 🟨 skeleton (log only) | **Wave 6** で fan-out 拡張 | P1 | `PaperBroadcaster.kt` |
| Redis pub/sub broadcaster | ❌ nothing | **Wave 6** (stub) → Phase 4 (real) | P2 | Lettuce dep 追加予定 |
| Discord webhook broadcaster | ❌ nothing | **Wave 6** (stub) → Phase 4 (real) | P2 | (none) |
| Web admin UI (Nuxt 4) | ❌ nothing | Phase 4 | P2 | (roadmap.md Phase 4) |
| REST API endpoints | ❌ nothing | Phase 4 | P2 | (none) |
| WebSocket real-time events | ❌ nothing | Phase 4 | P3 | (none) |

---

## Category 10: Observability (Wave _ops, parallel to 5x)

| RTM 機能 | Astera 現状 | Wave 割当 | 優先度 | 根拠 |
|---|---|---|---|---|
| Structured logs (slf4j) | ✅ partial | (済 baseline) | P1 | `LoggerFactory.getLogger(...)` everywhere |
| Micrometer / Prometheus export | ❌ nothing | **Wave _ops** | P2 | (planned) |
| `/metrics` HTTP endpoint | ❌ nothing | Wave _ops | P2 | (planned) |
| Grafana dashboards | ❌ nothing | Wave _ops | P3 | (planned) |
| Distributed tracing (OpenTelemetry) | ❌ nothing | Phase 1.5+ | P3 | (none) |
| Audit log emission (cross-plugin) | ❌ nothing | Wave 5e | P2 | (with audit table) |

---

## Category 11: External plugin deps (ADR-0015 governance)

| RTM 必要 plugin | Astera 戦略 | 完成度 | 根拠 |
|---|---|---|---|
| WeaponMechanics 3.3.0 | 自前 `IProjectileService` + `GunSpec` (Decline) | 🔌 port full、impl Wave 6 | ADR-0015 D |
| Oraxen 1.170.0 | 自前 `IItemRegistry` default + optional provider Phase 4 | ✅ port full | ADR-0015 D |
| ModelEngine R4.0.4 | Phase 5/6 ADR (BetterModel vs ModelEngine) | ❌ nothing | ADR-0015 Compare |
| MythicMobs 5.3.5 | 自前 mob API (Phase 5+ 再評価) | ❌ nothing | ADR-0015 Compare |
| ProtocolLib 5.1.0 | PacketEvents when needed Phase 3+ | ❌ nothing | ADR-0015 D |
| WorldGuard 7.0.9 | 自前 `Plot` + `Region` Phase 5 | 🟨 Region 型のみ | ADR-0015 D |
| PlaceholderAPI 2.11.5 | `MessageKey` + placeholder Map | ✅ full (Wave 1) | ADR-0015 D |
| FAWE (FastAsyncWorldEdit) | `WorldCreator` + 自前 schematic loader Phase 3 | ❌ nothing | ADR-0015 D |
| Multiverse-Core | Paper `WorldCreator` API | ❌ nothing | ADR-0015 D |
| Scoreboard Library 2.1.3 | 自前 `IScoreboard` port | 🔌 port-only (Wave 5b) | ADR-0021 + Wave 5b |
| Inventory Framework 0.10.13 | 自前 `IGuiAdapter` (ADR-0021) | ✅ full (Wave 4) | ADR-0021 ✅ |
| UltimateAdvancementAPI | Phase 4+ adopt | ❌ nothing | ADR-0015 Adopt |
| CommandAPI | Paper Brigadier + 自前 `ICommandHandler` | ✅ full (Wave 1) | ADR-0015 D |
| PacketEvents 2.6.0 | When needed Phase 3+ | ❌ nothing | (deferred) |
| KAML / kotlinx-serialization | Same versions or newer | ✅ full | (Wave 1) |

---

## Category 12: i18n / Content authoring (Wave 1 ✅ + ongoing)

| RTM 機能 | Astera 現状 | Wave 割当 | 優先度 | 根拠 |
|---|---|---|---|---|
| YAML-driven content | ✅ full | (済) | P0 | `ContentLoader<T>` + multi-format parsers |
| Element matrix YAML | ✅ full | (済) | P0 | `content/balance/element-matrix.yaml` |
| Schema auto-gen (`schema-gen`) | ✅ full, 9 schemas | (済) | P0 | `tools/schema-gen/` |
| i18n keys (en + ja) | ✅ partial, ~150 keys | (済 baseline) | P1 | `content/languages/{en,ja}.yaml` |
| MiniMessage placeholder | ✅ full | (済) | P0 | `MessageKey` + MiniMessage |
| ICU MessageFormat (plural / gender) | ❌ nothing | Phase 2 mid (now Wave 5a context) | P2 | (planned) |
| Hot reload (edit YAML → reload without restart) | ❌ nothing | Phase 2+ | P3 | (planned) |
| LLM translation (real-time chat → multilingual) | ❌ nothing | Phase 6 | P3 | RTM Writerside `コンテンツ案一覧.md` GPT-4o-mini |

---

## Aggregate: what Wave 5a〜5f + Wave 6 will deliver

### Wave 5a (Combat depth, Phase A→B 6 worker→C, ~5-6h)
- SkillCast 6 archetype handler (PROJECTILE/MELEE/AOE/BUFF/TELEPORT/SUMMON)
- Armor domain (ArmorSpec / ArmorSlot / set bonus)
- DamageRule depth (armor reduction + crit chance/multiplier + knockback + friendly-fire)
- Skill resource cost (mana/energy)
- Charged skill / hold-to-cast variant
- Death attribution → respawn flow
- **Unblocks P0 "skill dispatch hollow" gap**

### Wave 5b (HUD ports, Phase A→B 4-5 worker→C, ~3-4h)
- `IBossBar` / `IBossBarManager` port + Fake impl
- `IScoreboard` / `ITeamScoreboard` port + Fake impl
- `ITabList` port + Fake impl
- `IMcPlayer.sendActionBar()` / `sendTitle()` API extension
- (Paper impls land in Wave 6)

### Wave 5c (Game modes + Star Generator + Anomaly, Phase A→B 5-6 worker→C, ~5-6h)
- Sealed `GameMode` framework + per-mode listener pattern
- 6 game mode domain implementations (Battle Loyal / Break Core / Carry TNT / Carry Mini TNT / Capture Wool / Steal Jewelry)
- Star Generator domain + scheduler config
- Anomaly type sealed + AnomalyScheduler
- Sudden death special mechanics

### Wave 5d (Stage geometry + Block tracking + Barrier, Phase A→B 6-7 worker→C, ~6-7h)
- Block tracking domain (BlockPlaced / BlockBroken event + per-match state)
- Block durability domain (max 100 HP + NBT spec + pseudo-recovery formula)
- Barrier system domain (custom enclosure + DisplayEntity hooks)
- Custom machine domain (Redstone dispenser + skill core)
- Capture zone / KOTH region type domain
- Schematic spec (vendor-neutral path)
- Per-match world domain
- Block restoration after match

### Wave 5e (Session + Social + Tutorial + EnderChest + Reward bridge + Inventory snapshot, Phase A→B 6 worker→C, ~6h)
- Player session state (InMatch / Lobby / Spectator / Dead / **Loading** — RTM #5 action-lock)
- Spectator mode domain
- Friend list / Party domain
- Team chat channel domain
- Achievement / Title / Badge domain
- Tutorial system domain
- EnderChest / Personal Vault (`IPersonalVaultService` + Postgres)
- Personal settings DB
- Reward 2-server flow (game-server side; Redis-bridge spec)
- Audit log domain + table
- **PlayerInventorySnapshot domain + V10 + Postgres + Save/Restore use case + 2 listener** (RTM #5)

### Wave 5f (Marketplace + JP currency + CEL expression, Phase A→B 4-5 worker→C, ~4-5h)
- Player marketplace + creator revenue share
- JP regulatory currency compliance (`CurrencyPolicy` domain with expiry / cap / non-redeemable flags)
- CEL expression language for skill extensibility
- Particle definition system
- Reactive GUI binding (`ResolvedReactiveSlot` extension for numeric / display override)

### Wave 6 (Paper Sweep, Phase A→B 8+ worker→C, 1-2 sessions)
- PaperDamageGateway
- PaperStatusEffectContainer + tick loop
- PaperProjectileService (all 5 motion variants)
- PaperWorldGateway (placeBlock / removeBlock / spawnParticle / playSound / full teleport)
- PaperBossBar / PaperScoreboard / PaperTabList
- PaperSchematicLoader (FAWE adapter)
- PaperSpectator + Bukkit GameMode mapping
- BukkitEventAdapter extensions (EntityDamage / EntityDeath / PlayerJoin / PlayerQuit / BlockPlace / BlockBreak / InventoryClick already done)
- IBroadcaster Redis + Discord real impls
- Vec3 math (normalize / dot / cross / length)
- **PaperInventorySnapshotSerializer (Bukkit ↔ JSON via BukkitObjectOutputStream)** + auto save/restore wire (RTM #5)
- **InventoryActionLockEnforcer** (BukkitEventAdapter で Loading state 中 InventoryClick/PickupItem/Interact cancel)
- All Paper-side wire-up

---

## Living doc — update protocol

Each Wave 5x / Wave 6 / Phase 4+ session updates this tracker as part of
**Phase C (me 統合)** alongside the wave's commit:

1. Mark delivered rows: `❌ → ✅` (full) or `❌ → 🔌` (port-only when Paper-impl
   待ち)
2. Update `Summary metrics` table
3. Add new rows discovered during the wave (RTM issue / Writerside doc not yet
   tracked)
4. Update aggregate "Wave X delivers" section if scope shifted

The tracker is **single source of truth** for "what's left". When in doubt,
consult this doc before re-reading RTM upstream — and only re-fetch RTM if a
specific issue / file needs verification.
