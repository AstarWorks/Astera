# Astera Implementation Roadmap

**Single source of truth** for "what gets built next, in what order, and why."
Living doc — updated at the end of every wave.

> **Last updated**: 2026-05-17 (Wave 4.8 roadmap refinement — RTM #5 inventory persist + Phase 4-7 detail)
> **Current baseline**: commit `d26f869` (Wave 4.7 IMPLEMENTATION-ROADMAP.md land) + Wave 4.8 refinement
> **Current RTM parity**: ~15%
> **Critical-path target**: Wave 7c (Phase 3 Siege complete) → ~75% parity

---

## Reading order

This doc coordinates 4 other living references:

- [RTM-PARITY-TRACKER.md](RTM-PARITY-TRACKER.md) — feature × state matrix
  (the empirical "what's done" board)
- [architecture/rtm-divergence.md](architecture/rtm-divergence.md) — Keep /
  Redesign / Add policy (ADR-0013) with empirical 6 game modes + 22 issues
  layered in (ADR-0029)
- [adr/0028-roadmap-realignment-paper-as-edge.md](adr/0028-roadmap-realignment-paper-as-edge.md) —
  WHY this ordering (Paper-as-edge re-affirmation)
- [adr/0029-rtm-empirical-catalog.md](adr/0029-rtm-empirical-catalog.md) —
  WHAT RTM has empirically (6 game modes + 22 issues + Writerside docs)
- Per-session handoff docs in `/home/node/.claude/plans/wave-*-handoff.md` —
  HOW each wave executes (Phase A → B → C, worker territories, completion criteria)

If reading order is unclear: **start here**, then drill into the parity tracker
for the wave you're about to ship.

---

## Leverage principle

**Tier N depends on Tier 0..N-1.** Within a tier, items can run in parallel.
Paper / Bukkit code is the LAST tier (Tier 7+) per ADR-0001 / ADR-0002 (Clean
Architecture with MC as edge). Domain + application + ports + Fake adapters
all come first.

```
Tier 0  Foundational primitives (Vec3 math, IMcEvent expansion, dep additions)
   ↓   = folded into Wave 5a Phase A (no separate wave)
Tier 1  Combat depth                                   = Wave 5a
   ↓
Tier 2  UI ports                                       = Wave 5b (Tier 1 と並列可)
   ↓
Tier 3  Match flow (6 game modes + Star Gen + Anomaly) = Wave 5c
   ↓
Tier 4  World geometry (Block / Barrier / Schematic)   = Wave 5d
   ↓
Tier 5  Session + Social + Tutorial + Vault + Reward   = Wave 5e
   ↓
Tier 6  Economy expansion + Long-tail extensibility    = Wave 5f
   ↓
Tier 7  Paper Sweep (all Bukkit impl, finally)         = Wave 6a + 6b
   ↓ ← Phase 1 PoC end-to-end complete here
Tier 8  Phase 3 Siege Warfare content + tournament     = Wave 7a-c
   ↓ ← Phase 3 Siege complete (~75% RTM parity)
Tier 9  Phase 4 UGC / Web admin / Cosmetics            = Wave 8a-f
Tier 10 Phase 5 Metaverse Place                        = Wave 9a-c
Tier 11 Phase 6 Generative AI                          = Wave 10a-f
Tier 12 Phase 7 Ops automation + Wave _ops 並列        = Wave 11a-d
```

### Critical path to RTM-equivalent

```
Wave 5a → 5b (並列) → 5c → 5d → 5e → 5f → 6a → 6b → 7a → 7b → 7c
                                                          ↑
                                              Phase 3 Siege 完了 ≒ 75% RTM
```

**11 sessions** from current state to Phase 3 Siege Warfare playable.
**~22 sessions** to full RTM + beyond parity.

---

## Phase 0: Infrastructure ✅ (Wave 1-4)

Multi-plugin + ServiceRegistry + Konsist + Clean Architecture baseline.

| Wave | Theme | Commit | Status |
|---|---|---|---|
| 1 | Multi-plugin + ServiceRegistry + Konsist + 6 plugin | `7a025fe` | ✅ |
| 2 | Application 成熟 (Stage/Norma loader + cross-plugin events + permission) | `1cdde97` | ✅ |
| 3 | Postgres persistence (5 Repositories + Exposed + HikariCP + Flyway) | `c845649` | ✅ |
| 4 | Declarative GUI runtime (Paper adapter + intent router + example loadout) | `537fdeb` | ✅ |

---

## Phase 1 PoC: closing (Wave 4.5 ✅ + 4.6 ✅ + 5a-f + 6a-b)

End state: **"1 weapon fires end-to-end"** — `/astera-shop browse` → 武器購入 →
`/astera-loadout save` → match start → spawn teleport → click fire → mob HP 減
→ status tick expire → kill 10 → MatchEnded.winningTeam → 報酬 → Norma persist。

### Wave 4.5 ✅ — Phase 1 PoC closing gap fill

`68cef2e` — Shop / Loadout 永続化 / Norma 永続化 / SpawnPoint / Phase auto-timer /
IGuiBindingResolver / ADR-0027

### Wave 4.6 ✅ — Docs realignment

`25147c8` — ADR-0028 (Roadmap Realignment) + ADR-0029 (RTM Empirical Catalog) +
RTM-PARITY-TRACKER.md + rtm-divergence.md 更新 + 7 sub-wave handoff doc 起草 +
旧 wave-5-handoff deprecated rename

### Wave 4.7 ✅ — Master roadmap

`d26f869` — `docs/IMPLEMENTATION-ROADMAP.md` (本ドキュメント) 起草、12 tier
leverage-ordered roadmap + cumulative milestone table + critical path 文書化

### Wave 4.8 ✅ — RTM #5 inventory persist + Phase 4-7 detail expansion

(本セッション) — Wave 5e に `PlayerInventorySnapshot` domain + V10 migration
追加 (RTM issue #5 永続保存データ)、Wave 6b に `PaperInventorySnapshotSerializer` +
`InventoryActionLockEnforcer` 追加、Phase 4-7 (Wave 8a-f / 9a-c / 10a-f / 11a-d) を
各 wave 3-5 bullet detail + 成功基準 + automation rule example で expansion、
Wave _ops 7 sub-item 詳細化、ADR-0048〜0057 番号予約

### Wave 5a ✅ — Combat depth (Tier 1)

`ca6ddab` — SkillCastDispatcher (6 archetype, SUMMON stub) + Armor domain
(ArmorSpec/Slot/Set/Bundle + 80% cap + set bonus) + DamageRule pipeline
refactor (HeadshotModifier / ArmorReductionModifier / CriticalStrikeModifier /
KnockbackModifier pluggable) + DamageResolution rich return type + rich
sub-events (CriticalHit / KnockbackApplied / DamageMitigated /
FriendlyFireBlocked / ManaConsumed) + ManaPool combat domain +
ChargedSkillVariant + ADR-0030 (Combat Depth + Wave 6 Bukkit damage+death
hijack policy).

Tier 0 prep folded in: Vec3 math (normalize/dot/cross/length/distance),
MutableVec3 in-place equivalents, IMcEvent +4 variants
(PlayerJoined/PlayerQuit/BlockPlaced/BlockBroken). EntityDamaged/EntityDied
**deliberately omitted** per ADR-0030 §8 (Wave 6 hijacks vanilla damage events).

### Wave 5a-fix ✅ — Damage path consolidation + Armor attribute-aware + ADR-0058

(本セッション) — `WeaponDamageRule` deleted (single canonical damage path,
ADR-0030 §1). `ArmorSpec.damageReduction: Double` →
`reduction: ArmorReduction(default, perAttribute: Map<DamageAttribute, Double>)`
(per-attribute resist capable; flat form preserved via
`ArmorReduction.flat(...)`). `MitigationReason.Armor` gains `attribute`
field so `DamageMitigated` events carry which element was resisted.
**ADR-0058 (Universal Attribute Container)** drafted as forward commitment
— Wave 5b reads, Wave 5e migrates PlayerStats/Wallet/ManaPool + armor
resists, Wave 5d/6b add mob/item-instance containers.

### Wave 5a-fix2 — Damage attribution + SkillTrigger enum saturation (Pending)

| Property | Value |
|---|---|
| Depends on | Wave 5a ✅ (Wave 5e と並列可) |
| Unlocks | Assist-credit reward path / Wave 7a content authoring fidelity (20-trigger taxonomy) |
| Complexity | 軽 (2-3 worker, ~2-3h、半セッション) |
| ADR | 0063 (Damage Attribution Window) |

**Scope** (2026-05-19 deep RTM audit per ADR-0029 §"Deep audit (2026-05-19)"):
- `DamageHistoryService` (sliding-window 攻撃元 attribution) + `IDamageHistoryService` port + `InMemoryDamageHistoryService` (RingBuffer per target)
- `AssistAttributionUseCase` — `PlayerKilled` event 購読 → `recentAttackers` から `AssistCredited` event publish
- `PlayerStats.assists: Long` field + V2 migration + `PostgresPlayerStatsRepository` 更新
- `RecordAssistOnAssistCreditedListener` 配線 (stats plugin)
- `SkillTrigger` enum 6 → 20 (RIGHT_CLICK_BLOCK / LEFT_CLICK_BLOCK / SHOOT / BLOCK_BREAK / CONSUME / CROUCH / JUMP / SPRINT / DROP / etc.) + dispatcher routing
- ADR-0060 (Damage Attribution Window) Draft

Trigger: 「Wave 5a-fix2 を実装して」

### Wave 5b ✅ — HUD ports (Tier 2) [Wave 5a と並列可]

`078f6e1` — 5 vendor-neutral HUD ports (`IBossBar` / `IBossBarManager`,
`IScoreboard` / `ITeamScoreboard` / `IScoreboardManager`, `ITabList`) +
`IMcPlayer.sendActionBar` / `sendTitle` 拡張 + 3 `InMemory*` impls wired
in `AsteraCorePlugin` (priority 0) + `FakeBossBarManager` / `FakeScoreboardManager` /
`FakeTabList` in test-fixtures + `FakeMcServer` promotion from weapons
test to shared test-fixtures.

**ADR-0058 §6 read-path established**: `plugin/core/.../domain/attribute/`
minimum read API (`AttributeKey` / sealed `AttributeValue` /
`IAttributeContainer` get/set/remove/keys / `Attributes` registry /
`number`/`flag`/`ref`/`durationTicks` ext helpers) + `ShimPlayerAttributeContainer`
in `plugin/combat/.../application/attribute/` wrapping `PlayerStats` +
`ManaPool`. The no-op-default modifier surface on `IAttributeContainer`
was layered in by the parallel Wave 5c track (`SimpleAttributeContainer`
ships in 5c's own commit).

ADR-0031 (HUD Ports) Draft. 6 paired i18n keys reserved under `astera.hud.*`
(en/ja). 58 new tests green (12 BossBar + 15 Scoreboard + 10 TabList +
7 IMcPlayerActionBarTitle + 7 AttributeContainer + 7 Shim). RTM parity
22% → 28%.

**Parallel-wave lesson** (harness commit `6f4f5c6`): Wave 5b ran in the
same working tree as the parallel Wave 5c session and produced build
breaks + commit scope bleed. `.claude/rules/wave-execution.md` +
`.claude/skills/wave-start/SKILL.md` now require `git worktree add ../astera-wave-{N}{y} -b wave-{N}{y}`
for parallel waves; the wave-start skill detects multiple-wave-ID /
"並列" / "parallel" triggers in Step 0 and proposes the worktree before
reading the four canonical docs.

### Wave 5c — Game modes + Star Generator + Anomaly (Tier 3)

| Property | Value |
|---|---|
| Depends on | Wave 5a (combat dispatch needed) |
| Unlocks | 6 game modes playable in domain, Match progression rich |
| Complexity | 中 (5-6 worker, ~5-6h) |
| ADR | 0032 |

**Scope**:
- sealed `GameMode` framework + 6 mode impls:
  - `BattleLoyal` (small-scale arena)
  - `BreakCore` (敵 core 破壊、primary)
  - `CarryTNT` (TNT 輸送)
  - `CarryMiniTNT` (10 mini collect into 1)
  - `CaptureWool` (area 制圧)
  - `StealJewelry` (敵 gem 3 個奪取)
- per-mode listener subscribing to `BlockBroken` / `EntityDied` / `TNTPickup` etc.
- `IGameModeRegistry` port + `InMemoryGameModeRegistry`
- Star Generator domain + `IScheduler.runRepeating` scheduler
- AnomalyType sealed + AnomalyScheduler (random tick triggering)
- Sudden death accelerator (generator 速度 2x + respawn disabled + zone shrink)

### Wave 5d — World geometry (Tier 4)

| Property | Value |
|---|---|
| Depends on | Wave 5c (mode 連携) |
| Unlocks | Siege map で実機ブロック破壊 / 建築 / バリア / Schematic load |
| Complexity | 大 (6-7 worker, ~6-7h、必要なら 5d-1 + 5d-2 分割) |
| ADR | 0033 |
| New ports | `IBlockStateGateway` / `IBarrierGateway` / `ISchematicLoader` |

**Scope**:
- Block tracking domain (`BlockPlaced` / `BlockBroken` event in
  `plugin/core/domain/event/`)
- Block durability (max 100 HP NBT + tool×block matrix YAML + pseudo-recovery
  scheduler RTM `BlockState.md` 流儀)
- `Barrier` system (region + DisplayEntity 視覚 + IBarrierGateway port + Fake)
- Custom machines (`SkillCoreMachine` + `TrapMachine` domain、P2 minimal)
- `CaptureZone` / KOTH (region + capture progress + control timer)
- `Schematic` spec + `ISchematicLoader` port + Fake
- `MatchWorld` domain + Create/Destroy use case
- `RestoreBlocksOnMatchEnded` listener (`MatchEnded` → per-match revert)

### Wave 5e — Session + Social + Tutorial + EnderChest + Reward + Inventory snapshot (Tier 5)

| Property | Value |
|---|---|
| Depends on | Wave 5d (world for spawn) + Wave 5b (HUD for tutorial UI) |
| Unlocks | 観戦 / フレンド / Tutorial / Vault / 試合報酬 lobby 配送 / Bukkit インベントリ永続化 |
| Complexity | 中-大 (6 worker, ~6h) |
| ADR | 0034 |
| New migrations | V7 personal_vaults / V8 player_settings / V9 audit_log / **V10 player_inventory_snapshots** |
| RTM issues addressed | #3 (EnderChest) / #5 (永続保存データ) / #6 (Reward 2-server) / #15 (個人設定) / #17 (Tutorial) |

**Scope**:
- `PlayerSession` state sealed: InMatch / Lobby / Spectator / Dead / Disconnected /
  **Loading** (RTM #5 action-lock 用 — `MatchSpawnUseCase` / `RestoreInventoryUseCase`
  実行中はこの state、Wave 6b で Paper enforce する)
- Spectator mode use case
- `Friendship` + `Party` domain + 8 use case (SendRequest / Accept / Decline /
  Remove / Create / Join / Leave / Disband)
- `ChatChannel` sealed (Global / Team / Party / Private) + `SendChatMessage` event
- Achievement / Title / Badge domain + 3 milestone listener (kill / damage / match win)
- TutorialStep sealed (SendMessage / TeleportTo / WaitForAction / GiveItem) +
  `TutorialScript` runner + 1 example YAML (RTM #17)
- `PersonalVault` + V7 migration + Postgres impl + 1 IT (RTM #3)
- `PlayerSettings` + V8 + Postgres (RTM #15)
- `RewardBundle` + `IRewardBridge` port + In-memory impl + 2 listener
  (MatchEnded / MissionCompleted) (RTM #6)
- `AuditEvent` sealed + V9 + `AuditLogListener` (全 domain event 購読)
- **`PlayerInventorySnapshot` domain** (RTM #5):
  - `PlayerInventorySnapshot(playerId, hotbar/main/offhand/armor 各 ItemStack list,
    version: Int, savedAt: Instant)` immutable value
  - `IPlayerInventorySnapshotRepository` port + Postgres impl (priority 100) +
    `InMemoryPlayerInventorySnapshotRepository` (priority 0)
  - V10 migration `player_inventory_snapshots` (player_id UUID PK, snapshot JSONB,
    version INT, saved_at TIMESTAMP)
  - `SaveInventorySnapshotUseCase` + `RestoreInventorySnapshotUseCase`
  - `SaveInventoryOnMatchEndedListener` (`MatchEnded` event 購読 → 試合参加者全員の
    snapshot 自動 save)
  - `RestoreInventoryOnPlayerJoinedListener` (`PlayerJoined` event 購読 →
    existing snapshot あれば restore + PlayerSession.state=Loading で action lock)
  - **NOT in scope (Wave 6b)**: Bukkit `Inventory` ↔ JSON serializer + Paper-side
    action lock enforcement (BukkitEventAdapter で `InventoryClickEvent` /
    `PlayerPickupItemEvent` / `PlayerInteractEvent` を Loading state 中 cancel)

### Wave 5f — Economy expansion + Long-tail extensibility (Tier 6)

| Property | Value |
|---|---|
| Depends on | Wave 5e (audit log for currency policy enforcement) |
| Unlocks | Marketplace / 法的 compliance / CEL skill / 動的 GUI numeric binding |
| Complexity | 軽 (4-5 worker, ~4-5h) |
| ADR | 0035 |
| Phase A | CEL library dep 決定 (JCEL / proxima / 自前 mini-parser) |
| New migrations | V11 currency_issuance_log / V12 marketplace_listings / V13 creator_revenue_records (V10 は Wave 5e で消費) |

**Scope**:
- `CurrencyPolicy` (JP regulatory compliance: 6ヶ月 expiry OR ¥10M cap、
  non-redeemable、tradable flag, sourceClass sealed: Purchased/Earned/Bonus)
  per RTM issue #19
- `CurrencyExpired` scheduled task + V11 per-issuance tracking
- `MarketplaceListing` + V12 + 3 use case (Create / Purchase / Cancel) per RTM #12
- `CreatorRevenue` share calc + V13
- `CelSkillScript` + `ICelEvaluator` port + Stub impl (real eval は Wave 6 or
  Phase 6 で library 決定後) per RTM #21
- `ParticleDefinition` domain + content YAML + schema-gen 登録
- `ResolvedReactiveSlot` (`IGuiBindingResolver.resolve` 戻り型拡張: itemId +
  displayOverride + loreOverride + countOverride) — Wave 4.5 で deferred した
  数値 binding 対応、`DefaultGuiBindingResolver` 拡張、`PaperItemTemplateRenderer`
  互換維持

### Wave 6a — Paper Sweep, Combat (Tier 7-1)

| Property | Value |
|---|---|
| Depends on | Wave 5a |
| Unlocks | 実機で example-pistol 発射 → mob HP 減 → status tick expire |
| Complexity | 中-大 (5 worker, ~6h) |
| ADR | 0026 (Paper Runtime Topology) draft 開始 |

**Scope**:
- `PaperDamageGateway` (Bukkit `LivingEntity.damage(amount)` + `DamageOutcome`)
- `PaperStatusEffectContainer` (Bukkit `PotionEffect` map + visual icon)
- `StatusEffectTickListener` (`IScheduler.runRepeatingTicks(1, 1)`)
- `PaperProjectileService` (5 motion variant、Vec3Pool hot-path、raycast collision)
- `ProjectileHitListener` (domain event → ApplyDamageUseCase + ApplyStatusUseCase)
- `TickCooldownUseCase` scheduler 配線 (`runRepeatingTicks(1, 1)`)
- `CooldownExpired` event publish

### Wave 6b — Paper Sweep, World + UI + Bridges (Tier 7-2)

| Property | Value |
|---|---|
| Depends on | Wave 5b-5e (HUD / world / session / reward 全部) |
| Phase A | Lettuce (Redis client) + okhttp/HttpClient (Discord webhook) dep 決定 |
| Complexity | 大 (6-8 worker, ~7h) |
| ADR | 0026 完成 → Accepted |

**Scope**:
- `PaperWorldGateway` 完成 (placeBlock / removeBlock / spawnParticle /
  playSound、teleport は Wave 4.5 で済)
- `PaperBlockStateGateway` (Bukkit BlockData + NBT durability)
- `PaperBarrierGateway` (Bukkit DisplayEntity)
- `PaperSchematicLoader` (FAWE adapter、ADR-0015 D path で optional)
- `PaperBossBar` / `PaperScoreboard` / `PaperTabList` (Adventure API)
- `PaperPlayer` 拡張 (sendActionBar / sendTitle via Adventure)
- `PaperSpectator` (Bukkit `GameMode.SPECTATOR` + camera follow)
- `PaperRewardBridge` (In-process default + Redis env 設定時)
- `IBroadcaster` 拡張 (Lettuce Redis pub/sub real + Discord webhook real)
- `BukkitEventAdapter` 6 新イベント (EntityDamage/Death/Join/Quit/BlockPlace/BlockBreak)
- **`PaperInventorySnapshotSerializer`** (Bukkit `PlayerInventory` ↔ JSON via
  `org.bukkit.util.io.BukkitObjectOutputStream` / `BukkitObjectInputStream`、
  Base64 wrap、Wave 5e `IPlayerInventorySnapshotRepository` の Paper-side
  実装) per RTM #5
- **`PlayerInventoryLifecycleListener`** (PlayerJoin / PlayerQuit / `MatchEnded`
  hook で auto save/restore wire)
- **`InventoryActionLockEnforcer`** (`BukkitEventAdapter` で `InventoryClickEvent` /
  `PlayerPickupItemEvent` / `PlayerInteractEvent` を `PlayerSession.state == Loading`
  の間 cancel — Wave 5e domain で publish される state を Paper で enforce)
- 5 plugin onEnable wire

**Phase 1 PoC end-to-end 完成: Wave 6b 終了時点**

完了基準 (実機 smoke test):
- `/astera-shop browse example-shop` → 武器購入
- `/astera-loadout save MAIN example-pistol` → Postgres 永続化
- `/quit` → 再ログイン → loadout 復元
- `/astera-match start example-arena` → 30秒で ACTIVE 自動遷移
- attacker spawn point に自動 teleport
- 左クリック発射 → projectile trail → mob HP 減
- burn status 適用 → potion icon → N秒後 expire event
- kill 10 → `MatchEnded.winningTeam` 設定 → 報酬 currency credit
- Norma 進捗 +1 → 再起動 → 進捗保持

**RTM 機能パリティ: ~65%**

---

## Phase 3 Siege Warfare 本番化 (Wave 7a-c)

End state: **"1 complete playable Siege map across all 6 game modes"**.

### Wave 7a — Content authoring at scale (Tier 8-1)

| Property | Value |
|---|---|
| Depends on | Wave 6b |
| Unlocks | 1 完全 playable Siege map |
| Complexity | 中 (3-4 worker, ~4-5h)、コード少なめ + YAML 大量 |
| ADR | 0036 |

**Scope**:
- 1 完全 Siege arena schematic (`content/stages/siege-arena.yaml` + `.schem`)
- 6 game modes content YAML × 1 map
- Star Generator content layout (4-8 generators across map)
- 3-5 Anomaly content (ResourceSpike / StatusBurst / SpawnSurge etc.)
- 6 game modes 対応 spawn point の精緻化
- 8+ weapon content (sword/gun/wand × archetype × rarity)
- 16+ skill content
- 8+ armor content
- 16+ status effect content (RTM 16-enum 全部 minimum content)
- Balance YAML 完成 (element-matrix 拡張 + block-durability matrix + armor reduction matrix)

### Wave 7b — Tournament + Leaderboard (Tier 8-2)

| Property | Value |
|---|---|
| Depends on | Wave 7a |
| Unlocks | 競技性 + 統計 |
| Complexity | 中 (5 worker, ~5h) |
| ADR | 0037 |

**Scope**:
- Tournament domain (bracket / round / match link)
- Leaderboard ranking (K/D/R, win rate, ELO 風)
- Season concept (start / end + reset behavior)
- Ranked queue + matchmaking (TrueSkill 風 or simple ELO)
- 1v1 / 2v2 / team match queue

### Wave 7c — Balance iteration + content tooling (Tier 8-3)

| Property | Value |
|---|---|
| Depends on | Wave 7a + 7b |
| Unlocks | 持続的バランス調整 |
| Complexity | 軽 (3 worker, ~3h) |
| ADR | 0038 |

**Scope**:
- YAML hot-reload mechanism
- Content validator pre-commit hook
- Balance analytics events (DamageDealt aggregation → balance.csv)
- Replay system (DomainEvent log → replay)

**Phase 3 Siege Warfare 完成: Wave 7c 終了時点**
**RTM 機能パリティ: ~75%** (RTM 実機ゲームと同等以上)

---

## Phase 4 UGC + Web admin + 経済拡張 (Wave 8a-f)

End state: **"Web admin UI + UGC marketplace + Gacha + Battle pass running on
production server"**.

### Wave 8a — Web admin base (Tier 9-1)

| Property | Value |
|---|---|
| Depends on | Wave 6b |
| Unlocks | 運営 ops の GUI 操作基盤 + UGC review tooling 基盤 |
| Complexity | 大 (6 worker, ~6h) |
| ADR | 0039 |

**Scope**:
- `tools/web-admin/` 新規 Nuxt 4 + Bun (HomePage / AstarManagement と同じ
  toolchain で staffing leverage)
- Admin auth: 既存 `IPermissionService` 経由で `astera.webadmin.access` permission
  key check (RTM では 0 だった、Astera で新規導入)
- ktor or javalin REST server を `plugin/core/.../adapter/web/` (or 新規 plugin
  `plugin/web-api/`) で起動 — Phase A で framework 選択
- WebSocket gateway: player count / match state / live event stream
- ADR-0039 で framework 選択理由記録 (Nuxt 4 + ktor + WebSocket vs SSE)

### Wave 8b — REST API + WebSocket events (Tier 9-2)

| Property | Value |
|---|---|
| Depends on | Wave 8a |
| Unlocks | Web admin + Discord bot + future mobile app の共通 API surface |
| Complexity | 中 (5 worker, ~5h) |
| ADR | 0040 |

**Scope**:
- REST endpoints (read-only first、write は permission gated):
  `/api/players` / `/api/matches` / `/api/stats` / `/api/permissions` /
  `/api/audit-log` / `/api/shops` / `/api/inventory-snapshots` (RTM #5 connected)
- OpenAPI 3 spec auto-gen via Kotlin annotation (`@OpenApi` or similar)
- WebSocket event types: 既存 DomainEvent stream の Web fan-out
  (`PlayerJoined` / `PlayerQuit` / `MatchStarted` / `MatchEnded` / `DamageDealt` /
  `SkillCast`) — `IDomainEventBus.subscribe` → WebSocket frame
- Token bucket rate limit (per-admin)
- Error envelope: `{code, message, fields?}` standardize

### Wave 8c — UGC marketplace submission flow (Tier 9-3)

| Property | Value |
|---|---|
| Depends on | Wave 8b + Wave 5f marketplace |
| Unlocks | RTM did-not-do: コミュニティが weapon/skill/stage を提案 → 自動 PR |
| Complexity | 中 (5 worker, ~5h) |
| ADR | 0041 |

**Scope**:
- `UgcSubmission` domain (id / author / contentType: Weapon/Skill/StatusEffect/
  Stage / yamlContent / reviewState / submittedAt)
- Review queue (Pending → InReview → Approved / Rejected → Merged)
- Auto-validation: Wave 1 schema-gen schemas を reuse (kaml + json-schema)
- Moderator review UI (Wave 8a admin panel)
- Approved submission → `gh pr create` server-side ops automation
- ADR-0041 で 承認 flow + moderator escalation + reject 理由 transparency policy

### Wave 8d — Discord intake bot (Tier 9-3 並列)

| Property | Value |
|---|---|
| Depends on | Wave 8b + Wave 6b broadcaster Discord webhook |
| Unlocks | Discord で UGC 受付 / status query / moderator notify |
| Complexity | 軽 (3 worker, ~3h) |
| ADR | 0042 |

**Scope**:
- Discord webhook subscription + App Commands `/submit` / `/status`
- `/submit` → `UgcSubmission` (Wave 8c integration)
- `#ugc-review` channel に moderator notify
- `/status <id>` で自分の submission state query
- ADR-0042 で Discord bot lifecycle (token rotation / OAuth scope / rate limit)

### Wave 8e — Cosmetics + Gacha + Battle pass + Shop expansion (Tier 9-4)

| Property | Value |
|---|---|
| Depends on | Wave 5f marketplace |
| Unlocks | Monetization 軸 + RTM README documented "50% pull-up gacha" 実装 |
| Complexity | 中 (5 worker, ~5h) |
| ADR | 0043 |

**Scope**:
- `Cosmetic` sealed (WeaponSkin / Emote / BGM / Title / Aura) + equip use case
  (視覚のみ、damage に影響しない pure aesthetic)
- `GachaPool` + `GachaRoll` + **50% pull-up algorithm** (RTM README 明記、
  天井確率 transparency 必須 — RTM #19 と整合)
- `Season` + `TierProgression` + `RewardTrack` (battle pass)
- **Shop expansion** (Wave 4.5 shop に以下を後付け、RTM-PARITY-TRACKER Category 6
  expansion 行に対応):
  - `ShopCategory` (タブ式 / カテゴリ別 browse)
  - Shop rotation (`ShopOffer.availableUntil` + scheduler)
  - Multi-currency price (`ShopPrice` → `MultiPrice(List<ShopPrice>)` sealed expansion)
  - Per-player purchase limits
- ADR-0043 で gacha 確率開示 (RTM #19 法令と整合) + battle pass tier 仕様

### Wave 8f — Ban / Mute / Moderation tooling (Tier 9-5)

| Property | Value |
|---|---|
| Depends on | Wave 8a + Wave 5e audit log |
| Unlocks | 運営 violation 対応 + RTM didn't-do の cheat 対策基盤 |
| Complexity | 軽 (3 worker, ~3h) |
| ADR | 0044 |

**Scope**:
- `BanRecord` + `MuteRecord` domain (player + duration + reason + issuedBy + issuedAt)
- Per-player infraction history (Wave 5e audit log と join)
- Chat filter regex + spam detection (token bucket per-player)
- Web admin UI: ban panel / report queue / appeal log
- Audit log viewer (V9 audit_log を時系列 + filter view)
- ADR-0044 で auto-mod false positive 対策 + appeal SLA

**Phase 4 完成: Wave 8f 終了時点**
**RTM 機能パリティ: ~85%** (RTM 超え)

---

## Phase 5 Metaverse Place (Wave 9a-c)

End state: **"Persistent player housing + SNS feed + seasonal events"**.

### Wave 9a — Persistent plot + housing (Tier 10-1)

| Property | Value |
|---|---|
| Depends on | Wave 8a (web admin for plot management) + Wave 5d (world geometry) |
| Unlocks | Place / lobby world での persistent player presence |
| Complexity | 大 (6 worker, ~6h) |
| ADR | 0045 |

**Scope**:
- `Plot` domain (id / owner: PlayerId / region: BoundingBox / sizeClass / status:
  Owned/Leased/Vacant)
- `PlotTransfer` use case (owner change + audit log + escrow during transfer)
- `PlotLease` (期間 + 賃料 + 自動退去)
- Building permission per plot (sealed: Whitelist(List<PlayerId>) / Open / Readonly)
- Per-plot region rules (Astera 自前 `Plot` + `Region` で WorldGuard 代替、
  ADR-0015 D path 遵守)
- 3 size class: Small (16×16) / Medium (32×32) / Large (64×64)
- ADR-0045 で plot grid layout + permission model + RTM 未着手領域宣言

### Wave 9b — SNS + friend feed (Tier 10-2)

| Property | Value |
|---|---|
| Depends on | Wave 5e (Friendship domain) |
| Unlocks | コミュニティ engagement loop + RTM didn't-do の在席感 |
| Complexity | 中 (5 worker, ~5h) |
| ADR | 0046 |

**Scope**:
- `PlayerPost` domain (text + 添付 ItemId / CosmeticId / AchievementId 任意)
- `FeedTimeline` use case (chronological vs filtered by friendship graph)
- `Like` / `Comment` / `Share` 3 interaction domain
- `PlayerNotified` event + in-game popup + email opt-in
- ADR-0046 で feed algorithm (chronological default、algorithmic は後付け選択肢)

### Wave 9c — Seasonal events automation (Tier 10-3)

| Property | Value |
|---|---|
| Depends on | Wave 8e (battle pass season concept) |
| Unlocks | 周年 / holiday auto-rotate、運営手動オペ削減 |
| Complexity | 軽 (4 worker, ~4h) |
| ADR | 0047 |

**Scope**:
- `EventSchedule` (start / end + 自動 reward distribution date)
- Event scheduler (anniversary / 周年 / Halloween / 正月 auto-rotate)
- Event content YAML structure (`content/events/spring-2026.yaml`)
- `RewardDistributionJob` (`RewardBundle` × player list × `IRewardBridge` fan-out)
- ADR-0047 で event rotation policy + reward fairness + missed-event policy

**Phase 5 完成: Wave 9c 終了時点**
**RTM 機能パリティ: ~90%**

---

## Phase 6 Generative AI (Wave 10a-f、partial running in Wave _assets parallel track)

End state: **"AI generates textures + sounds + 3D + translates chat + reviews PRs"**.

All Wave 10 items run in **parallel** (each is an independent skill or agent).
ADR-0010 (Generative AI policy deferred to Phase 6) はこの phase で finalize。

### Wave 10a — Texture-gen continuation (Tier 11-1)

| Property | Value |
|---|---|
| Depends on | 既存 `tools/texture-gen/` + Wave 7a content baseline |
| Unlocks | 100+ in-game weapon texture coverage |
| Complexity | 中 (3 worker、agent dispatcher 含む、~5h) |
| ADR | 0048 |

**Scope**:
- 既存 `tools/texture-gen/` extension で sword / gun / wand / bow / staff
  archetype 横展開
- 各 archetype 4-8 rarity × 2-4 element = 16-32 textures/archetype
- ADR-0018 (AI asset policy) 遵守
- ADR-0048 で texture pipeline V2 (batch + rarity-aware prompts + leak filter)
- **成功基準**: 100+ texture が in-game で使用可能、quality acceptable (人間 review pass rate > 80%)

### Wave 10b — Sound-gen skill (Tier 11-2)

| Property | Value |
|---|---|
| Depends on | (independent) |
| Unlocks | 16+ weapon fire/hit/explosion/ambient sound |
| Complexity | 軽 (2 worker, ~3h) |
| ADR | 0049 |

**Scope**:
- `.claude/skills/generate-sounds/` 新規作成
- API: ElevenLabs (text-to-audio) + Suno (music) — Phase A で provider 選択
- Output: `.ogg` Minecraft 互換 + `content/sounds/` 配置
- ADR-0049 で sound asset policy + provider 選択理由
- **成功基準**: 16+ weapon fire / hit / explosion / ambient sound usable

### Wave 10c — Model-gen skill (Tier 11-3)

| Property | Value |
|---|---|
| Depends on | 既存 `.claude/skills/build-model/` extension |
| Unlocks | 5+ weapon model + 5+ mob model usable |
| Complexity | 中 (3 worker, ~4h) |
| ADR | 0050 |

**Scope**:
- `.claude/skills/generate-models/` 新規 (`build-model` skill 拡張)
- API: Meshy (text-to-3D) + Tripo
- Output: `.bbmodel` BlockBench 互換 + `content/models/` 配置
- ADR-0050 で model asset policy + retopology pipeline
- **成功基準**: 5+ weapon model + 5+ mob model in-game で表示 + animation valid

### Wave 10d — LLM translation real (Tier 11-4)

| Property | Value |
|---|---|
| Depends on | Wave 5f `IChatTranslator` stub |
| Unlocks | Real-time multilingual chat (RTM didn't-do、JP-EN-ZH-KR support) |
| Complexity | 軽 (2 worker, ~3h) |
| ADR | 0051 |

**Scope**:
- `IChatTranslator` real impl: GPT-4o-mini or Claude Haiku integration
- Real-time chat → 他言語訳出 (en/ja/zh-CN/zh-TW/ko)
- Cache layer (Postgres `chat_translations` table、同一文 < 24h re-use)
- Cost budget control (per-message $0.001 max、超過時 fallback to "translation unavailable")
- ADR-0051 で LLM provider choice + cost ceiling + privacy policy

### Wave 10e — Weapon-designer agent (Tier 11-5)

| Property | Value |
|---|---|
| Depends on | Wave 10a + Wave 10b (texture + sound pipelines) |
| Unlocks | 1 prompt で full weapon (YAML + texture + sound + lore) 生成 |
| Complexity | 中 (3 worker, ~5h) |
| ADR | 0052 |

**Scope**:
- `.claude/agents/weapon-designer/` 新規作成
- Input: 自然言語 (例: "fire sword common, +25% damage, 5s burn") → WeaponSpec YAML +
  texture + fire sound + lore i18n keys 一括生成
- 既存 texture-gen / sound-gen / weapon-loader pipeline 統合 dispatcher
- ADR-0052 で agent orchestration + quality gate (Konsist + schema validation)
- **成功基準**: 1 prompt で完全動作する weapon が `content/weapons/` に置かれ、
  in-game で fire できる (failure rate < 30% acceptable)

### Wave 10f — AI PR review bot (Tier 11-6)

| Property | Value |
|---|---|
| Depends on | (independent) |
| Unlocks | Architectural drift 自動検出、ADR-0001/0002 違反早期 catch |
| Complexity | 軽 (2 worker, ~3h) |
| ADR | 0053 |

**Scope**:
- GitHub PR webhook + Claude API review
- 既存 Konsist rule 違反 + ADR 違反検出 (Paper imports in domain etc.)
- PR comment で review summary post
- ADR-0053 で AI review confidence threshold + human override policy
- **成功基準**: false positive < 10%、true positive > 70% (3 sprint sampling)

**Phase 6 完成: Wave 10f 終了時点**
**RTM 機能パリティ: ~93%**

---

## Phase 7 Ops automation (Wave 11a-d) + Wave _ops 並列

### Wave _ops (anytime parallel, can run during any of Wave 5-10)

7 sub-item を並列実行可能。各 item は独立した本流 wave に edge from。

#### _ops-1 — KEDA dynamic Game Pod

- KEDA ScaledObject (matchmaking queue depth → game pod spawn → idle → scale to 0)
- Match end後 N 分で auto-shutdown (Postgres から match state 読み)
- ADR (existing reserved) — Wave 6b で実機 ready 後に decisive
- **成功基準**: queue 0 → 0 pod、queue ≥ 1 → 1 pod < 30s spawn

#### _ops-2 — Sealed-Secrets vs SOPS (ADR-0023)

- 比較表 (transport / rotation / drift detection / dev UX)
- private submodule 内 secrets を gitops で track する選択肢評価
- ADR-0023 起草、決定後 `private/infra/` 移行
- **成功基準**: ArgoCD でsync可、`kubectl get secret` 経由で見えない (Sealed)
  or `kubectl get` で plaintext (SOPS preview only)

#### _ops-3 — Provider 路線 (ADR-0024)

- Oraxen / MythicMobs / WeaponMechanics の `adapter/providers/*` governance
- soft-dep 検出 timing + missing 時の fallback chain
- ADR-0024 起草、Provider 採用判断 framework 確定
- **成功基準**: vanilla Paper boot + provider 全部 missing でも green
  (ADR-0001 / 0002 遵守 verify)

#### _ops-4 — Micrometer + Prometheus exporter

- `plugin/core/.../adapter/metrics/` で `MeterRegistry` 抱える
- `/metrics` endpoint (Prometheus scrape format)
- 標準 metric: jvm / paper tick / match throughput / domain event rate
- **成功基準**: `kubectl port-forward` で `/metrics` scrape、Grafana ingest 確認

#### _ops-5 — Structured JSON logs (ECS format)

- log4j2 / logback で ECS Elasticsearch Common Schema 出力
- correlation id (matchId / playerId) を MDC injection
- **成功基準**: Loki ingest + 検索クエリで matchId filter 動作

#### _ops-6 — Grafana dashboards

- player count timeline / match throughput / error rate / damage distribution
- alerting rule 5 件 (error spike / pod crashloop / queue stuck / cache miss / latency)
- **成功基準**: dashboard JSON が `deploy/grafana/` で version-control 可能

#### _ops-7 — `make local-e2e` script

- kind cluster spin-up + Postgres / Redis bootstrap + Astera shadowJar deploy
- example match smoke test (CI で nightly run)
- **成功基準**: `make local-e2e` がクリーン VM で < 5 分 green

### Wave 11 sequence

### Wave 11a — release-noter agent (Tier 12-1)

| Property | Value |
|---|---|
| Depends on | Phase 6 AI tooling baseline |
| Unlocks | 手動 release notes 作成負担削減 |
| Complexity | 軽 (2 worker, ~2h) |
| ADR | 0054 |

**Scope**:
- Commits since last release tag → release notes Markdown
- 自動 categorize: feat / fix / docs / chore / breaking (conventional commits 経由)
- ADR への cross-link (commit body の `ADR-XXXX` 参照を build into release notes)
- ADR-0054 で release cadence (calendar-based vs feature-based) + version bump strategy
- **成功基準**: 1 tag bump → PR body 自動生成、人間 edit time < 5 分

### Wave 11b — incident-responder agent (Tier 12-2)

| Property | Value |
|---|---|
| Depends on | Wave _ops-4 (Prometheus) + _ops-6 (Grafana alerts) |
| Unlocks | On-call MTTR 削減 |
| Complexity | 中 (3 worker, ~4h) |
| ADR | 0055 |

**Scope**:
- Grafana alert webhook → Claude API hypothesis + runbook 抜粋
- PagerDuty / Slack `#oncall` integration
- 自動 enrich (recent commits / matching past incidents / log excerpt)
- ADR-0055 で incident severity matrix + escalation timer
- **成功基準**: alert 受信 < 30 秒で initial Slack post (hypothesis + 関連 runbook URL)

### Wave 11c — Voting / affiliate system (Tier 12-3)

| Property | Value |
|---|---|
| Depends on | Wave 5e audit log + Wave 8a web admin |
| Unlocks | コミュニティ governance + content creator monetization |
| Complexity | 中 (4 worker, ~5h) |
| ADR | 0056 |

**Scope**:
- 投票 domain (`Proposal` + `Option` + `Vote` + quorum rule)
- Proposal lifecycle (Drafted → OpenForVote → Closed → Enacted)
- Affiliate payout tier (`CreatorRevenue` Wave 5f 連動、tier 別 share %)
- Audit hooks (全 vote / payout を `AuditEvent` で記録)
- ADR-0056 で governance + payout audit + sybil 対策
- **automation rule example**: 「creator が当該月の view 上位 10% に入った場合
  自動で tier 1 → tier 2 promotion」

### Wave 11d — RTM 検定後継 (certification / 格付け / 称号) (Tier 12-4)

| Property | Value |
|---|---|
| Depends on | Wave 8e (Cosmetics の Title integration) + Wave 7b leaderboard |
| Unlocks | RTM 検定の Astera 後継、competitive ladder closure |
| Complexity | 中 (4 worker, ~5h) |
| ADR | 0057 |

**Scope**:
- Certification tier (Bronze / Silver / Gold / Platinum / Diamond)
- Testing criteria per tier (kills / wins / playtime / mode-specific challenge)
- 称号 (Title) integration with cosmetics system (Wave 8e Title cosmetic 連動)
- Anti-cheat verification (replay system Wave 7c 連動 + manual review queue)
- ADR-0057 で certification fairness + anti-cheat + appeal process
- **automation rule example**: 「3 シーズン連続で Diamond 維持 → 永久 Diamond
  title 付与 + leaderboard 殿堂入り」
- **automation rule example**: 「特定 mode で 100 試合勝利 → 専用 称号
  (例: "BreakCore Master") 自動 award」

**Phase 7 完成: Wave 11d 終了時点**
**RTM 機能パリティ: ~98%** (RTM 既存機能 + Astera 独自 ops automation)

---

## Cumulative milestone table

| Milestone | Wave | RTM parity | Session # |
|---|---|---|---|
| Phase 0 Infrastructure | 1-4 | ~10% | 4 (✅) |
| Phase 1 PoC closing (gap fill) | 4.5 | ~12% | 5 (✅) |
| Phase 1 PoC closing (docs realignment) | 4.6 | ~15% | 6 (✅) |
| Phase 1 PoC closing (master roadmap) | 4.7 | ~15% | 7 (✅) |
| Phase 1 PoC closing (RTM #5 + Phase 4-7 detail) | 4.8 | ~15% | 8 (✅) |
| Combat domain depth | 5a | ~22% | 9 (✅ `ca6ddab`) |
| Damage consolidation + Armor attribute-aware + ADR-0058 | 5a-fix | ~22% | 10 (✅ `b14c915`) |
| HUD ports + ADR-0058 read-path shim | 5b | ~28% | 10 (✅ `078f6e1`) |
| Match flow rich + Game modes + Star Generator + Anomaly + Sudden Death | 5c | 48.4% (pre-saturation denom) | 11 (✅ `e9808a2`) |
| World geometry + Block / Barrier / Schematic / MatchWorld / Restoration | 5d | 55.5% (pre-saturation denom) → 49.3% (post-saturation denom; same 71 ✅, larger catalog) | 11 (✅ `3849446`) |
| Wave _infra-1 (Fake MC server + Scenario DSL + ADR-0059) | _infra-1 | — | 11 (✅ `a68c9bc`) |
| **2026-05-19 RTM saturation** (ADR-0029 §"Deep audit" + parity tracker +16 rows + CONTENT-ROADMAP) | docs | denom 128→144 | 12 (✅) |
| **Damage attribution + SkillTrigger 6→20** (ADR-0060 follow-up to 5a) | **5a-fix2** | +3 rows ✅ projected (~51% post-saturation denom) | 13 (pending、半セッション; Wave 5e と並列可) |
| Session + Social + Tutorial + Inventory snapshot + Attribute migration + PersonalSettings (4 typed fields) + PlayerPin + LevelUpMission + PortalReady event | 5e | ~60% projected (post-saturation denom) | 14 |
| Economy + extensibility | 5f | ~63% projected | 15 |
| Paper Combat (+ double-jump / particle filter / quickcast / status unified API) | 6a | ~68% projected | 16 |
| Paper World + UI + Inventory + StructureService | 6b | **~75% projected (Phase 1 PoC 完了)** | 17 |
| Siege Warfare content (+ BattleRoyale + CarryBigTnt + ItemGenerator) | 7a | ~80% projected | 18 |
| Tournament + Leaderboard | 7b | ~83% | 19 |
| Balance + tooling | 7c | **~85% (Phase 3 完了 + post-saturation denom)** | 20 |
| Phase 4 UGC + Web + Shop expansion | 8a-f | ~85% | 21-26 |
| Phase 5 Metaverse | 9a-c | ~90% | 27-29 |
| Phase 6 Gen AI full | 10a-f | ~93% | 30-35 |
| Phase 7 Ops automation | 11a-d | ~98% | 36-39 |

Wave _ops は並列 (any time after Wave 4.6)、Wave _assets は継続中。

---

## Parallelization windows

これらは同セッションで並列に走らせられる:

- **Wave 5a + 5b**: full parallel (independent)
- **Wave 7a + Wave _ops**: independent (content vs infra)
- **Wave 8a → 8b**: critical path; **8c / 8d / 8e / 8f は 8b 後並列可**
- **Wave 10a-f**: 全部 parallel (texture / sound / model / agent は独立)
- **Wave _ops + Wave _assets**: 全期間並列

---

## ADR allocation per wave

| Wave | ADR # | Theme |
|---|---|---|
| 5a | 0030 | Combat Depth |
| 5b | 0031 | HUD Ports |
| 5c | 0032 | Game Mode Framework + Star Generator + Anomaly |
| 5d | 0033 | World Geometry (Block / Barrier / Schematic) |
| 5e | 0034 | Session + Social + Tutorial + EnderChest + Reward bridge |
| 5f | 0035 | Economy Expansion + JP Compliance + CEL |
| 6a / 6b | 0026 | Paper Runtime Topology |
| 7a | 0036 | Siege Content Authoring |
| 7b | 0037 | Tournament + Matchmaking |
| 7c | 0038 | Balance Iteration Tools |
| 8a | 0039 | Web Admin Base (Nuxt 4) |
| 8b | 0040 | REST + WebSocket |
| 8c | 0041 | UGC Submission Flow |
| 8d | 0042 | Discord Intake Bot |
| 8e | 0043 | Cosmetics + Gacha + Battle Pass |
| 8f | 0044 | Moderation Tooling |
| 9a | 0045 | Plot + Persistent Housing |
| 9b | 0046 | SNS + Feed |
| 9c | 0047 | Seasonal Events Automation |
| 10a | 0048 | Texture pipeline V2 |
| 10b | 0049 | Sound asset policy |
| 10c | 0050 | Model asset policy |
| 10d | 0051 | LLM translation provider + cost ceiling |
| 10e | 0052 | Weapon-designer agent orchestration |
| 10f | 0053 | AI PR review confidence threshold |
| 11a | 0054 | Release cadence + version bump |
| 11b | 0055 | Incident severity matrix |
| 5a-fix | 0058 | Universal Attribute Container (forward commitment; Wave 5b reads, Wave 5e migrates) |
| 11c | 0056 | Voting + affiliate audit |
| 11d | 0057 | Certification + anti-cheat |
| Wave _ops | 0023 / 0024 | Secrets + Provider 路線 |
| Wave _infra-1 | 0059 | Fake MC Server + Scenario DSL (`AsteraTestHarness`, PDCA harness, no MockBukkit) |
| Wave _infra-2 | 0060 | DataDriven Contents + Per-Config Reload (supersedes ADR-0017) |
| Wave _infra-3 | 0061 | Studio /weapons preview route (Three.js read-only viewer, ADR-0018 carve-out extension) |
| Wave _infra-4 | 0062 | `contentLint` Gradle pipeline (JSON Schema + i18n parity + ref-resolve) |
| **5a-fix2** | **0063** | **Damage Attribution Window** (sliding-window damage history + assist credit; ADR-0030 §1 追補) |
| 5e (Listener-Service refactor sub-scope) | 0064 | Listener-Service separation pattern (RTM issue #22; ADR-0001 hexagonal の module-internal routing convention) |

Reserved but not yet drafted: 0026 (Wave 6a/6b), 0030-0057 (Wave 5a〜11d), 0060-0062 (Wave _infra-2〜4).

---

## Update protocol

This doc is **living**. Update sequence per wave:

1. **Phase C (me 統合)** ends with:
   - Tick the milestone row in cumulative table
   - Update RTM-PARITY-TRACKER.md status columns
   - Add commit hash to the wave's row above
   - If scope shifted, note it in the wave's section

2. **Annual** (or every Phase boundary): cull deprecated content, refresh
   parity %, re-audit RTM upstream for new issues.

3. **Surprise discoveries** (audit reveals new RTM feature missed by parity
   tracker): add row to RTM-PARITY-TRACKER.md, decide which wave absorbs it,
   reference this roadmap from the wave's handoff doc.

---

## Notes on RTM divergence

RTM (RyuZUTechnicalMagic) is the **public predecessor** at
https://github.com/Y-RyuZU/RyuZUTechnicalMagic. Astera **references RTM but
does not follow** it (ADR-0013). The roadmap above:

- **Keeps** RTM's gameplay design intent (6 game modes / 31 easing curves /
  loadout slot taxonomy / weapon archetype split / star generator concept /
  anomaly concept / block durability + NBT pseudo-recovery / barrier system /
  reward 2-server flow / JP currency compliance / CEL extensibility — all in
  this roadmap)
- **Redesigns** RTM's architecture (Hexagonal / Koin single / Postgres+JSONB /
  sealed events / typed generics / `Result<T, E>` / vendor-neutral ports / no
  reflection in domain)
- **Adds** modern needs RTM didn't address (GitOps / KEDA / Generative AI
  pipeline / AI PR review / Sealed-Secrets / Discord intake / Web admin /
  metaverse Place)

See [architecture/rtm-divergence.md](architecture/rtm-divergence.md) for the
full Keep / Redesign / Add tables.

---

## Next session trigger

```
Wave 5a (Combat depth) を実装して。
```

Reads `/home/node/.claude/plans/wave-5a-handoff.md` for execution detail
(Phase A includes Tier 0 prep: Vec3 math + IMcEvent 6 new variants).
