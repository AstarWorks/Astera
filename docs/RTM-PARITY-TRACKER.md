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
| ✅ full | 102 | 70.3% |
| 🟨 partial | 3 | 2.1% |
| 🔌 port-only | 9 | 6.2% |
| ❌ nothing | 31 | 21.4% |
| **Total tracked** | **145** | 100% |

(Wave 4.8 で +7 行追加: Inventory snapshot 2 + Shop expansion 4 + Gacha 1 reclassified; Wave 5b で +6 行 port-only flip; Wave 5c で +6 行 full flip; Wave 5d で +9 行 full flip; **2026-05-19 saturation で +16 行 catalog 補完** — Cat 1 combat +5 / Cat 2 match +4 / Cat 3 world +1 / Cat 7 stats +3 / Cat 8 session +3、いずれも RTM source-code + issue body deep audit 由来、Wave 5a-fix2 / 5e / 6a / 6b / 7a に分配; Wave 5a-fix2 で Cat 1 の DamageHistoryService + assist counter ❌→✅ + SkillTrigger 14 値 🟨→✅ で +3 ✅; Wave _ops で Cat 10 +2 ✅ (Observability); Wave _persist-1 で Cat 8 持続化補完 — SessionState/Friendship/Party/RewardBridge の 4 Postgres impl (V11/V12/V13/V15 + ADR-0068 Cross-server persistence boundary))

**Last audited**: 2026-05-19 (ADR-0029 §"Deep audit (2026-05-19)" methodology — 詳細は ADR 参照)

**Phase parity targets** (cumulative):
- Wave 4.5: ~15% RTM parity
- Wave 5a: **~22% RTM parity** (Category 1 Combat 9 行 ❌→✅)
- Wave 5b: **~28% RTM parity** (Category 4 HUD 5 行 + Category 7 attribute read ❌/📋→🔌/🔌 = +6)
- Wave 5c: **48.4% RTM parity** (Category 2 game modes 3 行 + Category 5 generator/anomaly 4 行 ❌→✅ = +6)
- Wave 5d: **55.5% RTM parity (pre-saturation denominator)** → **49.3% (post-saturation denominator)** — 同じ 71 ✅ 行、catalog 増えただけ
- Wave 5a-fix2: **51.4% RTM parity** (Cat 1 +2 ✅ DamageHistory + assist counter, +1 partial→full SkillTrigger 20 値 — total 74/144)
- Wave 5e: **65.3% RTM parity** (Cat 1 double-jump +1, Cat 2 listener-arch refactor +1, Cat 6 ender-chest + reward-bridge +2, Cat 7 achievement/tutorial/settings/UAC-migration/levelupmission/playerpin/quickcast +7, Cat 8 spectator/friend/chat/audit/inventory-snapshot/action-lock/control-scheme/particle-hide/portal-ready +9 — total 94/144)
- **Wave _ops: 66.7% RTM parity** (Cat 10 +2 ✅ Observability: micrometer + /metrics endpoint — total 96/144)
- Wave 6 (Paper Sweep) complete: ~65-70% RTM parity (Phase 1 PoC end-to-end)
- Phase 3 Siege Warfare complete (Wave 6 + 7a content): ~75-80%
- Phase 4 (UGC + Web + Vault + Gacha): ~85-90%
- Phase 5+ (Metaverse): >95% (Astera adds beyond RTM)

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
| Skill trigger (LEFT_CLICK/RIGHT_CLICK/SNEAK_LEFT/SNEAK_RIGHT/HOLD/AUTO + 14 Wave 5a-fix2 additions) | ✅ enum 20 値 (Wave 5a-fix2) | (済 Wave 5a-fix2; Bukkit handlers Wave 6) | P0 | `SkillTrigger.kt` |
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
| **CEL expression language for skill** (RTM issue #21) | ✅ Wave 5f (`CelSkillScript` + `CelContext` + `ICelEvaluator` port + `StubCelEvaluator` impl + 77 unit tests; ADR-0035) | (済 Wave 5f) | P3 | `plugin/combat/.../domain/skill/expression/` + `plugin/combat/.../application/port/outbound/ICelEvaluator.kt` + `plugin/combat/.../application/service/StubCelEvaluator.kt` |
| **Particle definition system** (RTM issue #21) | ✅ Wave 5f (`ParticleDefinition` + `ParticleShape` sealed (Point/Sphere/Ring/Line/Cone) + `ParticleContents` + `IParticleRegistry` port + `content/particles/{example-burst,example-ring}.yaml` + `particle.schema.json` + 137 unit tests; ADR-0035) | (済 Wave 5f) | P3 | `plugin/core/.../domain/effect/{ParticleDefinition,ParticleShape}.kt` + `plugin/core/.../application/{config/ParticleYamlConfig,contents/ParticleContents,port/outbound/IParticleRegistry}.kt` |
| **DamageHistoryService** (sliding-window damage attribution + assist credit) | ✅ full (Wave 5a-fix2) — `IDamageHistoryService` + `InMemoryDamageHistoryService` (ring buffer cap 64, 5-sec default window) + `AssistAttributionUseCase` | (済 Wave 5a-fix2) | **P1** | `plugin/combat/.../application/{port/outbound/IDamageHistoryService.kt,service/InMemoryDamageHistoryService.kt,usecase/damage/AssistAttributionUseCase.kt}` + ADR-0063 |
| **assist counter on PlayerStats** (`assists: Long` event-source path) | ✅ full (Wave 5a-fix2) — `AssistCredited` event + `RecordAssistOnAssistCreditedListener` + `RecordAssistUseCase`; column / field pre-existed Wave 3 | (済 Wave 5a-fix2) | **P1** | `plugin/combat/.../domain/event/AssistCredited.kt` + `plugin/stats/.../usecase/stats/RecordAssistUseCase.kt` |
| **SkillTrigger enum 14 値追加** (LEFT_CLICK_BLOCK / RIGHT_CLICK_BLOCK / LEFT_CLICK_AIR / RIGHT_CLICK_AIR / INTERACT / SHOOT / BLOCK_BREAK / CONSUME / CROUCH / JUMP / SPRINT / DROP / STACK_DROP / PICKUP / HYPER / ATTACK / SHIELD / DAMAGED / DEATH / SYSTEM) | ✅ enum 20 値 (Wave 5a-fix2) | (済 Wave 5a-fix2 vocabulary; Bukkit handlers Wave 6) | P1 | RTM `model/skill/SkillTrigger.kt` parity |
| **Vanilla Potion ↔ custom status unified API** (Bukkit `PotionEffect` と Astera `StatusKind` を 1 model で扱う) | ❌ nothing | **Wave 6** (Paper integration boundary) | P2 | RTM issue #2 body bullets |
| **Double-jump domain** (`PlayerSettings.doubleJumpEnabled` + jump-count tracking; `JUMP_DISABLED` debuff は既存 enum で逆方向 cover) | ✅ Wave 5e (domain + JumpCountTracker skeleton; Paper jump packet listener = Wave 6a) | (済 Wave 5e) | P2 | RTM README §戦闘システム "デフォルトで二段階ジャンプ" |

---

## Category 2: Match flow & 6 game modes (Wave 5c)

| RTM 機能 | Astera 現状 | Wave 割当 | 優先度 | 根拠 |
|---|---|---|---|---|
| Match phases (WAITING / COUNTDOWN / ACTIVE / SUDDEN_DEATH / ENDED) | ✅ enum + state machine | (済) | P0 | `plugin/core/.../domain/match/MatchPhase.kt`, `MatchStateMachine.kt` |
| Phase auto-timer (COUNTDOWN → ACTIVE @ 30s, etc.) | ✅ full (Wave 4.5) | (済) | P0 | `PhaseTimerUseCase.kt` + `runRepeatingTicks(1, 20)` wire |
| Victory condition evaluator (score threshold 10) | ✅ full (Wave 4.5) | (済) | P0 | `VictoryConditionEvaluator.kt` |
| Match domain (Match / MatchId / Score / TeamId) | ✅ full | (済) | P0 | `plugin/core/.../domain/match/` |
| **6 game modes** (Battle Loyal / Break Core / Carry TNT / Carry Mini TNT / Capture Wool / Steal Jewelry) | ✅ Wave 5c (6 sealed variants + per-mode listener) | (済) | **P1** | RTM issue + README; grep → 6 listeners + domain |
| Game mode framework (sealed `GameMode` + per-mode listener) | ✅ Wave 5c | (済) | P1 | `GameMode.kt` sealed + `GameModeId` + `IGameModeRegistry` + listener dispatch |
| Sudden death mechanics (accelerated generator / no respawn / forced engagement) | 🟨 Wave 5c (generator accelerator ✅); respawn-disabled + zone-shrink Wave 5d/6 | **Wave 5c** (generator); Wave 5d/6 (respawn/zone) | P1 | `SuddenDeathAcceleratorUseCase` (MULTIPLY 0.5 modifier on PERIOD_MULTIPLIER) |
| Team domain (Team / TeamId / colorKey / members) | ✅ minimal | (済) | P0 | `Team.kt` |
| Team auto-balance | ❌ nothing | Wave 5c | P2 | (none) |
| Match command surface (`/astera-match start/end/entry/phase/list`) | ✅ full | (済) | P0 | `AsteraMatchPlugin.kt` |
| **7th game mode `BattleRoyale`** (free-for-all, no team) | ❌ nothing | **Wave 7a** (Siege Content Authoring; content-fidelity) | P2 | RTM `GameMode.kt` sealed enum + `IGamePlayer.GamePlayer.BattleRoyalePlayer` sealed variant (2026-05-19 deep audit) |
| **3rd TNT variant `CarryBigTnt`** (payload — `GuiAction.CHANGE_PAYLOAD`) | ❌ nothing | **Wave 7a** (content-fidelity) | P2 | RTM `GameMode.kt` (3 TNT variants total: Tnt / SmallTnt / BigTnt) |
| **`ItemGenerator`** (non-Star item-spawning generator) | ❌ nothing | **Wave 7a** (content alongside Wave 5c `StarGenerator`) | P2 | RTM `ConfiguredItemGenerator.kt` alongside `ConfiguredStarGenerator.kt` |
| **Listener-Service architecture refactor** (per-module event-handler ↔ business-logic separation) | ✅ Wave 5e (ADR-0064 draft → Accepted) | (済 Wave 5e) | P3 | RTM issue #22 body |

---

## Category 3: World / Stage / Geometry (Wave 5d + Wave 6)

| RTM 機能 | Astera 現状 | Wave 割当 | 優先度 | 根拠 |
|---|---|---|---|---|
| Stage domain + StageId + Region (AABB) | ✅ full | (済) | P0 | `Stage.kt`, `StageId.kt`, `Region.kt` |
| Stage YAML loader | ✅ full | (済) | P0 | `StageLoaderService.kt` + `StageYamlConfig.kt` |
| **SpawnPoint** (Attacker / Defender / Lobby / Spectator sealed) | ✅ full (Wave 4.5) | (済) | P0 | `SpawnPoint.kt`, `Stage.spawnPoints` field, `MatchSpawnUseCase.kt` |
| `IWorldGateway.teleport()` port | ✅ port + Fake (Wave 4.5) | (済 port; impl は **Wave 6**) | P0 | `IWorldGateway.kt`, `FakeWorldGateway.kt` |
| `IWorldGateway` 残メソッド (placeBlock / removeBlock / blockAt / spawnParticle / playSound) | ✅ port, impl ❌ | **Wave 6** | P0 | `IWorldGateway.kt` |
| **Block tracking domain** (BlockPlaced / BlockBroken event + per-match state) | ✅ Wave 5d (port + InMemory + journal) | **Wave 5d** | P1 | `plugin/core/.../domain/event/BlockPlaced.kt`, `BlockBroken.kt` (PROMOTED from Wave 5c stub), `IBlockStateGateway`, `InMemoryBlockStateGateway`, `BlockChangeJournal` |
| **Block durability** (max 100 HP, tool-dependent damage, NBT-backed) | ✅ Wave 5d (formula + YAML; NBT-backed is Wave 6) | **Wave 5d** | P1 | `BlockDurabilityRule.kt`, `ToolType.kt`, `BlockMaterial.kt`, `BlockDurabilityYamlConfig.kt`, `content/balance/block-durability.yaml` |
| **Block pseudo-recovery** (timestamp decay) | ✅ Wave 5d (BlockRecoveryScheduler @ 10s / +5 HP per cycle) | Wave 5d | P1 | `BlockStateAutoRecoveryUseCase.kt`, `BlockRecoveryScheduler.kt` |
| **Barrier system** (custom block enclosure + DisplayEntity visual + scheduler status changes) | ✅ Wave 5d (port + Fake; DisplayEntity visual is Wave 6) | **Wave 5d** | P1 | `Barrier.kt`, `BarrierStatus.kt`, `BarrierId.kt` (PROMOTED from Combat), `IBarrierGateway`, `InMemoryBarrierGateway`, `CreateBarrierUseCase`, `DestroyBarrierUseCase`, `BarrierStatusScheduler` |
| **Custom machines (Redstone dispenser + skill core + traps)** | ✅ Wave 5d (domain types only, P2 minimal — no use case / port / scheduler per handoff) | Wave 5d | P2 | `Machine.kt`, `SkillCoreMachine.kt`, `TrapMachine.kt`, `TriggerCondition.kt`, `TrapEffect.kt` |
| Schematic spec / WorldEdit `.schem` loader | ✅ Wave 5d (domain + port + Fake; FAWE adapter is Wave 6) | **Wave 5d** (domain) + **Wave 6** (Paper FAWE adapter) | P1 | `plugin/core/.../domain/world/Schematic.kt`, `ISchematicLoader`, `InMemorySchematicLoader` |
| Per-match world isolation (slime-world / multiverse-style) | ✅ Wave 5d (MatchWorld domain + repository + Create/Destroy use case; Paper world creation Wave 6) | **Wave 5d** (domain) + **Wave 6** (Paper) | P1 | `MatchWorld.kt`, `IMatchWorldRepository`, `InMemoryMatchWorldRepository`, `CreateMatchWorldUseCase`, `DestroyMatchWorldUseCase` |
| Capture zone / KOTH region type | ✅ Wave 5d (domain + tick use case + scheduler) | **Wave 5d** | P1 | `CaptureZone.kt`, `CaptureZoneId.kt`, `CaptureZoneTickUseCase.kt`, `CaptureZoneScheduler.kt` |
| Block restoration after match end | ✅ Wave 5d (BlockChangeJournal + RestoreBlocksOnMatchEnded listener; replays on MatchEnded) | **Wave 5d** | P1 | `RestoreBlocksOnMatchEndedUseCase.kt`, `BlockChangeJournal.kt` |
| Region type variants (protected / buildable / spawn / no-build) | ❌ Region is plain AABB | **Wave 5d** | P1 | `Region.kt` has no type tag |
| **`StructureService`** (runtime structure paste, schematic load とは別 — mid-match terrain transformation) | 🔌 port skeleton (Wave 5g) — `IStructurePaste` + `PasteHandle` + `StructurePasteError` + `FakeStructurePaste` | **Wave 5g** (port + ADR-0072) + **Wave 6b** (Paper impl over FAWE; existing Wave 5d schematic loader covers match-start のみ) | P2 | RTM `paper/.../structure/StructureService.kt` (2026-05-19 deep audit); `plugin/core/.../application/port/outbound/IStructurePaste.kt` |

---

## Category 4: HUD ports (Wave 5b + Wave 6 Paper)

| RTM 機能 | Astera 現状 | Wave 割当 | 優先度 | 根拠 |
|---|---|---|---|---|
| **`IBossBar` / `IBossBarManager` port** | 🔌 port-only (Wave 5b) | **✅ Wave 5b** | **P1** | grep `IBossBar` → core.adapter.api.hud |
| **`IScoreboard` / `ITeamScoreboard` port** | 🔌 port-only (Wave 5b) | **✅ Wave 5b** | P1 | core.adapter.api.hud |
| **`ITabList` port** | 🔌 port-only (Wave 5b) | **✅ Wave 5b** | P2 | core.adapter.api.hud |
| **`IMcPlayer.sendActionBar(...)` method** | 🔌 port-only (Wave 5b) | **✅ Wave 5b** | P1 | signature added, Paper impl Wave 6 |
| **`IMcPlayer.sendTitle(...)` method** | 🔌 port-only (Wave 5b) | **✅ Wave 5b** | P1 | signature added, Paper impl Wave 6 |
| **CT / Star / Level HUD layout** (RTM issue #14) | ❌ nothing | Wave 5b (domain) + **Wave 6** (Paper impl) | P1 | (RTM uses Scoreboard Library 2.1.3) |
| Scoreboard Library 2.1.3 dep (RTM) | (Astera self-builds via `IScoreboard` port) | Wave 6 (Paper impl chooses library) | P1 | ADR-0015 D path |
| Inventory Framework 0.10.13 dep (RTM) | (Astera self-builds via `IGuiAdapter`) | ✅ Wave 4 (full) | P0 | ADR-0021 + Wave 4 ✅ |
| Declarative GUI (Wave 4) | ✅ full | (済) | P0 | Wave 4 ✅ |
| Reactive GUI binding (`player.loadout.main`) | ✅ ItemId 系のみ (Wave 4.5) | (済) | P0 | `DefaultGuiBindingResolver.kt`; 数値 binding は **Wave 5f** で `ResolvedReactiveSlot` 拡張 |
| Reactive GUI binding (numeric / display override) | ✅ Wave 5f (`ResolvedReactiveSlot` data class + numeric/display/count binding via `DefaultGuiBindingResolver` + `PaperItemTemplateRenderer` override application + 255 unit tests; ADR-0035) | (済 Wave 5f) | P2 | `plugin/core/.../domain/gui/ResolvedReactiveSlot.kt` + `plugin/core/.../application/service/DefaultGuiBindingResolver.kt` (expansion) + `plugin/core/.../adapter/paper/gui/PaperItemTemplateRenderer.kt` (expansion) |

---

## Category 5: Star Generator + Anomaly (Wave 5c)

| RTM 機能 | Astera 現状 | Wave 割当 | 優先度 | 根拠 |
|---|---|---|---|---|
| **Star Generator domain** (BedWars-style spawner, position + currency + period + amount) | ✅ Wave 5c (`StarGenerator` + `GeneratorTier` + per-instance `IAttributeContainer`) | (済) | **P1** | `plugin/match/.../domain/generator/` (Vec3 position, Currency, Duration period, Int amount) |
| **Star Generator scheduler** | ✅ Wave 5c (`StarGeneratorScheduler` via `IScheduler.runRepeating`) | (済) | P1 | `plugin/match/.../application/service/StarGeneratorScheduler.kt` |
| **Anomaly type sealed + AnomalyScheduler** | ✅ Wave 5c (6 sealed `AnomalyType` variants + `AnomalyScheduler` weighted-random tick) | (済) | **P1** | `plugin/match/.../domain/anomaly/AnomalyType.kt` + `AnomalyScheduler.kt` |
| Anomaly random in-match events | ✅ Wave 5c (`TriggerAnomalyUseCase` + `ResolveAnomalyEffectUseCase`); **Wave 5g**: 4 new gateway ports (`IMatchTimerService` / `IPlayerMovementGateway` / `IPlayerVisionGateway` / `IStructurePaste`) + Fakes wire all 5 previously-TODO variants (StatusBurst / SpawnSurge / TimeWarp / GravityShift / FogOfWar) Fake-level; Paper impls Wave 6a/6b per ADR-0072 | (済 Wave 5c + Wave 5g) | P1 | `plugin/match/.../application/usecase/anomaly/`; `plugin/match/.../application/port/outbound/IMatchTimerService.kt`; `plugin/core/.../application/port/outbound/I{PlayerMovementGateway,PlayerVisionGateway,StructurePaste}.kt` |
| Resource decay (time-based) | ❌ nothing | Wave 5c | P2 | (none) |
| `IScheduler.runRepeating` (infra) | ✅ full | (済) | P0 | `PaperScheduler.kt`, ADR-0019 |

---

## Category 6: Economy / Shop / Marketplace (Wave 4.5 ✅ + Wave 5e + 5f)

| RTM 機能 | Astera 現状 | Wave 割当 | 優先度 | 根拠 |
|---|---|---|---|---|
| Currency / Wallet / 3 currency (STAR / GOLD / GEM) | ✅ full + Postgres | (済) | P0 | `Currency.kt`, `Wallet.kt`, `V3__wallets.sql` |
| Deposit / Withdraw / Transfer (atomic) | ✅ full | (済) | P0 | `DepositUseCase.kt`, etc. |
| Shop domain (`ShopSpec` / `ShopOffer` / `ShopPrice` / `ShopStock`) | ✅ full (Wave 4.5) | (済) | P0 | `plugin/core/.../domain/shop/` |
| `IShopRegistry` + `ShopLoaderService` + `ShopPurchaseUseCase` | ✅ full; **Wave 5g**: stock-decrement bug fixed — `IShopStockRepository` (priority-100 Postgres overlay + V19 migration `shop_stock`) wired post-`ledger.debit` so `OutOfStock` is reachable again after `Limited(N)` exhausts; `ShopLoaderService` idempotent `seed` at startup | (済 Wave 4.5 + Wave 5g) | P0 | (Wave 4.5); `plugin/core/.../application/port/outbound/IShopStockRepository.kt`; `plugin/persistence/.../repository/PostgresShopStockRepository.kt`; `plugin/persistence/.../resources/db/migration/V19__shop_stock.sql` |
| Shop access surfaces (NPC vendor / external EC / chat alias) | 🔌 port skeleton + ADR-0071 (Wave 5g) — `IShopVendor` + `IShopReferenceQuery` + `IShopAccessIntent` sealed (`OpenFromCommand / OpenFromNpc / OpenFromChat / OpenFromExternal`) + `ShopAccessIntentRouter` + `InMemoryShopVendorBinding` + `FakeShopVendorBinding` | **Phase 4 Wave 8b** (Paper impls + REST wrap) | P2 | RTM #4; `plugin/economy/.../application/port/{outbound/IShopVendor.kt,outbound/IShopReferenceQuery.kt,inbound/IShopAccessIntent.kt}`; ADR-0071 |
| `/astera-shop browse` command + intent dispatch | ✅ full | (済) | P0 | (Wave 4.5) |
| Reward listeners (kill / match-win / mission → currency) | ✅ full | (済) | P0 | (Wave 1-2) |
| **Ender Chest / Personal Vault** (RTM issue #3) | ✅ Wave 5e (`IPersonalVaultService` + Postgres) | (済 Wave 5e) | **P1** | grep `EnderChest|PersonalVault` → 0 件 |
| **Reward 2-server flow** (game→Redis→lobby、RTM issue #6) | ✅ Wave 5e (in-memory; Redis swap = Wave 6); Wave _persist-1 (V15 RewardBridge outbox durable queue) | (済 Wave 5e) | P1 | `plugin/persistence/.../adapter/repository/PostgresRewardBridgeRepository.kt` (V15 outbox + delivered_at) + ADR-0068 |
| **Player marketplace** (RTM issue #12) | ✅ Wave 5f (`MarketplaceListing` + `ListingStatus` sealed (Available/Sold/Cancelled) + `IMarketplaceRepository` port + in-memory + Postgres impl (V12) + 3 use cases (Create/Purchase/Cancel) + 137 integration test; ADR-0035) | (済 Wave 5f) | P2 | `plugin/economy/.../domain/marketplace/` + `plugin/economy/.../application/port/outbound/IMarketplaceRepository.kt` + `plugin/economy/.../application/usecase/marketplace/` + `plugin/persistence/.../repository/PostgresMarketplaceRepository.kt` |
| **Creator revenue sharing** (RTM issue #12) | ✅ Wave 5f (`CreatorRevenue` + `CreatorRevenueCalculator` (post-fee-remainder split: 1000 → 810/90/100 at default 10%/10%) + `ICreatorRevenueRepository` port + in-memory + Postgres impl (V13) + 146 unit tests + 62 integration tests; ADR-0035) | (済 Wave 5f) | P2 | `plugin/economy/.../domain/marketplace/CreatorRevenue.kt` + `plugin/economy/.../application/service/CreatorRevenueCalculator.kt` + `plugin/economy/.../application/port/outbound/ICreatorRevenueRepository.kt` + `plugin/persistence/.../repository/PostgresCreatorRevenueRepository.kt` |
| **Battle pass** (RTM issue #12) | ❌ nothing | **Phase 4 (Wave 8e)** | P2 | (none) |
| **Cosmetics (skin / emote / BGM packs)** (RTM issue #11) | ❌ nothing | **Phase 4 (Wave 8e)** | P2 | (none) |
| **JP regulatory currency compliance** (RTM issue #19: 6mo OR ¥10M cap, non-redeemable, dual-currency) | ✅ Wave 5f (`CurrencyPolicy` 4-axis sealed spec (expirable/circulationCap/redeemable/tradable) + `SourceClass` sealed + `CurrencyPolicies` hardcoded map (STAR/GOLD/GEM, JP 資金決済法 Articles 4/7/13 aligned) + `IEnforceCurrencyPolicyUseCase` + `ExpireCurrencyUseCase` daily sweep + `ICurrencyIssuanceLog` port + in-memory + Postgres impl (V11) + 391 unit tests; ADR-0035) | (済 Wave 5f) | **P2 (legal)** | `plugin/economy/.../domain/policy/{CurrencyPolicy,SourceClass,CurrencyPolicies}.kt` + `plugin/economy/.../application/usecase/policy/{EnforceCurrencyPolicyUseCase,ExpireCurrencyUseCase}.kt` + `plugin/economy/.../application/port/outbound/ICurrencyIssuanceLog.kt` + `plugin/persistence/.../repository/PostgresCurrencyIssuanceLog.kt` |
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
| **Achievement / Title / Badge** (Norma とは別) | ✅ Wave 5e | (済 Wave 5e) | P2 | (none) |
| **Tutorial system** (RTM issue #17) | ✅ Wave 5e | (済 Wave 5e) | P2 | (none) |
| **Personal settings DB** (RTM issue #15) | ✅ Wave 5e (incl. 4 typed fields: controlScheme/particleVisibility/doubleJumpEnabled/quickCastSlots) | (済 Wave 5e) | P2 | (none) |
| **Universal Attribute Container (ADR-0058)** — Player/Item/Mob 共通 attribute storage | ✅ Wave 5e (V14 + facade rewrite complete; ADR-0058 → Accepted) | (済 Wave 5e) | **P1** | ADR-0058 §5 rollout schedule。`Wallet` / `PlayerStats` / `ManaPool` / `ArmorReduction.perAttribute` を `IAttributeContainer` backing に統合。Wave 5b で attribute read API + shim 完成、Wave 5e で V14 migration + facade rewrite |
| **`LevelUpMission`** (per-level KILL vs STAR choice) | ✅ Wave 5e | (済 Wave 5e) | P1 | RTM `LevelUpMission.kt` enum + `ConfiguredLevelParameter.normaParameter: Map<Int, ConfiguredNormaParameter>` (README §レベルシステム "レベルアップには一定のスター or キル") |
| **`PlayerPin`** (small/medium/large team-comms markers) | ✅ Wave 5e | (済 Wave 5e) | P2 | RTM `ConfiguredPlayerPin.kt` |
| **QuickCast hotbar mapping** (`PlayerSettings.quickCastSlots: Map<Int, SkillId>`) | ✅ Wave 5e | (済 Wave 5e) | P2 | RTM issue #15 body checkbox |
| Leaderboard ranking | ❌ nothing | Phase 4 | P3 | (none) |
| **RTM 検定後継 (格付け / 称号)** | ❌ nothing | Phase 7 | P3 | (roadmap.md Phase 7) |

---

## Category 8: Permission / Moderation / Session (Wave 5e)

| RTM 機能 | Astera 現状 | Wave 割当 | 優先度 | 根拠 |
|---|---|---|---|---|
| RBAC (RoleDefinition / Permission / DefaultRoles) | ✅ full + Postgres | (済) | P0 | `V4__permissions.sql` |
| Permission keys (14 declared) | ✅ full | (済) | P0 | various `.requirePermission(...)` |
| Console fallback for commands | ✅ full | (済) | P0 | `CommandPermission.kt` |
| **Spectator mode** (player session state: InMatch / Lobby / Spectator / Dead) | ✅ Wave 5e (use case domain; GameMode mapping = Wave 6); Wave _persist-1 (V11 Postgres-backed SessionState) | (済 Wave 5e) | P2 | `plugin/persistence/.../adapter/repository/PostgresPlayerSessionRepository.kt` (V11) + ADR-0068 |
| **Vanish / invisibility** (admin tool) | ❌ nothing | Wave 5e | P3 | (none) |
| **Friend list / Party** (social) | ✅ Wave 5e (domain + use case + Fake); Wave _persist-1 (V12/V13 Postgres) | (済 Wave 5e) | P2 | `plugin/persistence/.../adapter/repository/PostgresFriendshipRepository.kt` (V12 canonical-pair) + `PostgresPartyRepository.kt` (V13 party_members FK) + ADR-0068 |
| **Team chat channel** | ✅ Wave 5e | (済 Wave 5e) | P2 | (none) |
| **Audit log domain + table** | ✅ Wave 5e | (済 Wave 5e) | P2 | (none) |
| **Player inventory snapshot save/restore (RTM issue #5)** | ✅ Wave 5e (domain + V10; Paper serializer = Wave 6b) | (済 Wave 5e) | **P1** | grep `InventorySnapshot|PlayerInventory` → 0 件 |
| **InventoryActionLock** (load 中 player action 制限、RTM issue #5) | ✅ Wave 5e (PlayerSession.Loading state; Paper cancel = Wave 6b) | (済 Wave 5e) | **P1** | (none) |
| **Control scheme PersonalSettings field** (右クリック式 / ボタン式) | ✅ Wave 5e | (済 Wave 5e) | P2 | RTM README §プレイヤーデータ "操作方法選択" |
| **Particle-hide visibility setting** (per-viewer team filter on `Server#spawnParticle`) | ✅ Wave 5e (domain; Paper packet filter = Wave 6a) | (済 Wave 5e) | P2 | RTM README §戦闘システム + §プレイヤーデータ "パーティクル非表示設定" |
| **`PlayerPortalReadyEvent`** (lobby↔match server entry/exit signal) | ✅ Wave 5e (publisher wired at MatchSpawnUseCase + RestoreInventoryOnPlayerJoinedListener) | (済 Wave 5e) | P2 | RTM `event/data/PlayerPortalReadyEvent.kt` |
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
| Micrometer / Prometheus export | ✅ Wave _ops (Micrometer 1.14.6 + PrometheusMeterRegistry via IMetricsRegistry port) | (済) | P2 | Micrometer setup in core plugin, `IMetricsRegistry` port + `InMemoryMetricsRegistry` + `PrometheusMetricsAdapter` wire |
| `/metrics` HTTP endpoint | ✅ Wave _ops (sun.net.httpserver `HttpMetricsServer`, port 9092, env-gated by ASTERA_METRICS_ENABLED) | (済) | P2 | `plugin/core/.../adapter/paper/metrics/HttpMetricsServer.kt` + Paper plugin adapter |
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
- Block tracking domain (BlockPlaced / BlockBroken events PROMOTED from Wave 5c stub, per-match state via IBlockStateGateway + InMemory impl)
- Block durability domain (max 100 HP + tool×block matrix YAML + pseudo-recovery scheduler @ 10s / +5 HP)
- Barrier system domain (custom enclosure + status scheduler, DisplayEntity visual is Wave 6)
- Custom machine domain (Redstone dispenser + skill core + trap types; P2 minimal — no use case)
- Capture zone / KOTH region type (domain + tick use case + scheduler)
- Schematic spec (ISchematicLoader port + InMemory Fake; FAWE adapter is Wave 6)
- Per-match world isolation (MatchWorld domain + repository + Create/Destroy use cases; Paper world creation is Wave 6)
- Block restoration after match (BlockChangeJournal + RestoreBlocksOnMatchEnded listener subscribing to MatchEnded)

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

---

## Completed waves

- Wave 5e (2026-05-19): Session + Social + Tutorial + EnderChest + Reward bridge + Inventory snapshot V10 + Attribute container migration V14 + ADR-0034 + ADR-0058 → Accepted + ADR-0064 Accepted. 9 worker streams (α/β/γ/δ/ε/ζ/ι/θ1 in Group B1; η/θ2 in Group B2). +5 Flyway migrations, +6 domain event variants, +120 unit tests, +4 Postgres ITs.
- Wave 5f (2026-05-19): Marketplace + JP currency compliance + CEL + particles + reactive GUI binding (6 RTM rows ❌→✅). Marketplace: MarketplaceListing + ListingStatus + 3 use cases (Create/Purchase/Cancel) + Postgres V12 + 137 IT. Creator Revenue: post-fee-remainder split algo + 146 unit + 62 IT. JP Compliance: CurrencyPolicy 4-axis + SourceClass + CurrencyPolicies map (STAR/GOLD/GEM per 資金決済法) + EnforceCurrencyPolicy + ExpireCurrency daily sweep + ICurrencyIssuanceLog port + Postgres V11 + 391 unit. CEL: CelSkillScript + CelContext + ICelEvaluator port + StubCelEvaluator + 77 unit. Particles: ParticleDefinition + ParticleShape sealed + ParticleContents + IParticleRegistry port + 2 example YAML + schema + 137 unit. Reactive GUI: ResolvedReactiveSlot + numeric/display/count binding via DefaultGuiBindingResolver + PaperItemTemplateRenderer override application + 255 unit + 3 Postgres migrations (V11/V12/V13). **Total parity 66.7% → 70.8%** (96→102/144 ✅). ADR-0035 → Accepted.
- Wave _ops (2026-05-19): Observability substrate — Micrometer 1.14.6 + Prometheus export via IMetricsRegistry port + HttpMetricsServer (/metrics @ 9092) + KEDA ScaledObject for game pod autoscaling + ADR-0023 Sealed-Secrets + ADR-0024 Provider 路線 (hybrid 事後記録 + Phase 4 governance)
- Wave _persist-1 (2026-05-19): Cat 8 persistence closure — 4 Postgres repositories V11/V12/V13/V15 (SessionState/Friendship/Party/RewardBridge outbox) + ADR-0068 Cross-server persistence boundary (Sealed: state holders MUST be Postgres-backed at priority=100, InMemory remains as dev/test fallback)
- Wave 5g (2026-05-19): Core hardening pre-Paper-sweep — Shop stock bug fix (`IShopStockRepository` + V19 migration + `SELECT ... FOR UPDATE` atomic decrement + idempotent seed at startup; existing `OutOfStock` death code is now live) + Shop access surfaces (`IShopVendor` + `IShopReferenceQuery` + `IShopAccessIntent` sealed 4-variant + `ShopAccessIntentRouter` + Default/InMemory/Fake impls + ADR-0071 commitment to Phase 4 Wave 8b) + Pre-Paper-sweep port commitments (`IMatchTimerService` + `IPlayerMovementGateway` + `IPlayerVisionGateway` + `IStructurePaste` + 4 Fakes + `IStatusEffectContainer.applyBulk` default + `PlayerMatchAttributes.RESPAWN_RATE_MULTIPLIER` + ADR-0072) + Anomaly 5 TODO closure (StatusBurst / SpawnSurge / TimeWarp / GravityShift / FogOfWar Fake-level wire via the new gateway ports) + Konsist +2 rules (Wave 6 Paper allow-list + IShopStockRepository priority-100 pin) → baseline 28 → 30. Net catalog +1 row (Shop access surfaces); StructureService ❌→🔌. Vanilla Potion 統一は Wave 6a Paper adapter merge layer に意図的に閉じる (domain には漏らさず、ADR-0030 §8 と整合)。**Total parity 70.8% (102/144) → 70.3% (102/145, same ✅, +2 🔌, −1 ❌)** — parity % decrease is purely catalog growth (new row added). ADR-0071 + ADR-0072 → Accepted.
