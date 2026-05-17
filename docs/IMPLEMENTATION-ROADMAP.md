# Astera Implementation Roadmap

**Single source of truth** for "what gets built next, in what order, and why."
Living doc — updated at the end of every wave.

> **Last updated**: 2026-05-17 (Wave 4.6 docs realignment)
> **Current baseline**: commit `25147c8` (Wave 4.5 + 4.6 land)
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
RTM-PARITY-TRACKER.md (this master tracker) + rtm-divergence.md 更新 + 7
sub-wave handoff doc 起草 + 旧 wave-5-handoff deprecated rename

### Wave 5a — Combat depth (Tier 1) — **NEXT SESSION**

| Property | Value |
|---|---|
| Depends on | Wave 4.5 + 4.6 |
| Unlocks | Meaningful skill effects + balanced combat |
| Complexity | 中 (6 worker, ~5-6h) |
| ADR | 0030 |
| Phase A | Tier 0 prep — Vec3 math (normalize/dot/cross/length/distance) + IMcEvent 6 new variants (PlayerJoined/PlayerQuit/EntityDamaged/EntityDied/BlockPlaced/BlockBroken) |

**Scope** (Wave 5a):
- `SkillCastDispatcher` (6 archetype handler: PROJECTILE / MELEE / AOE / BUFF /
  TELEPORT / SUMMON) — closes "SkillCast subscriber 0" hollow gap
- `ArmorSpec` + `ArmorSlot` (HELMET / CHESTPLATE / LEGGINGS / BOOTS) + DamageRule
  armor reduction (per-slot % stacking)
- Critical strike (chance + multiplier)
- Knockback formula (uses Vec3.normalize)
- Friendly-fire toggle
- Skill resource cost (mana / energy field on SkillSpec)
- Charged skill variant (sealed: Instant / Charged(chargeTicks))
- Death attribution → respawn flow

**完了基準**: 6 archetype handler unit test + Armor reduction "base 10 × armor
50% → 5 damage" + Crit 100% chance → 2x damage + Knockback Vec3 direction +
Friendly-fire flag off → 0 damage + Resource cost InsufficientResource + Charged
skill 30 tick 後発火 + ADR-0030 + `./gradlew check` 緑 + parity tracker 更新

### Wave 5b — HUD ports (Tier 2) [Wave 5a と並列可]

| Property | Value |
|---|---|
| Depends on | Wave 4.5 のみ (Tier 1 不要、Wave 5a と完全独立) |
| Unlocks | 試合 HUD / 戦闘ダメージ表示 / Tutorial actionbar |
| Complexity | 軽 (4-5 worker, ~3-4h) |
| ADR | 0031 |

**Scope**:
- `IBossBar` / `IBossBarManager` port + `FakeBossBarManager`
- `IScoreboard` / `ITeamScoreboard` port + `FakeScoreboard`
- `ITabList` port + `FakeTabList`
- `IMcPlayer.sendActionBar(messageKey, placeholders)` 追加
- `IMcPlayer.sendTitle(titleKey, subtitleKey?, fadeIn, stay, fadeOut)` 追加
- Reference design section: CT / star / level HUD layout (RTM issue #14)

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

### Wave 5e — Session + Social + Tutorial + EnderChest + Reward bridge (Tier 5)

| Property | Value |
|---|---|
| Depends on | Wave 5d (world for spawn) + Wave 5b (HUD for tutorial UI) |
| Unlocks | 観戦 / フレンド / Tutorial / Vault / 試合報酬 lobby 配送 |
| Complexity | 中 (5-6 worker, ~5-6h) |
| ADR | 0034 |
| New migrations | V7 personal_vaults / V8 player_settings / V9 audit_log |

**Scope**:
- `PlayerSession` state (InMatch / Lobby / Spectator / Dead / Disconnected)
- Spectator mode use case (RTM ADR-0029 issue #17 互換)
- `Friendship` + `Party` domain + 8 use case (SendRequest / Accept / Decline /
  Remove / Create / Join / Leave / Disband)
- `ChatChannel` sealed (Global / Team / Party / Private) + `SendChatMessage` event
- Achievement / Title / Badge domain + 3 milestone listener (kill / damage / match win)
- TutorialStep sealed (SendMessage / TeleportTo / WaitForAction / GiveItem) +
  `TutorialScript` runner + 1 example YAML
- `PersonalVault` + V7 migration + Postgres impl + 1 IT (RTM issue #3)
- `PlayerSettings` + V8 + Postgres (RTM issue #15)
- `RewardBundle` + `IRewardBridge` port + In-memory impl + 2 listener
  (MatchEnded / MissionCompleted) (RTM issue #6)
- `AuditEvent` sealed + V9 + `AuditLogListener` (全 domain event 購読)

### Wave 5f — Economy expansion + Long-tail extensibility (Tier 6)

| Property | Value |
|---|---|
| Depends on | Wave 5e (audit log for currency policy enforcement) |
| Unlocks | Marketplace / 法的 compliance / CEL skill / 動的 GUI numeric binding |
| Complexity | 軽 (4-5 worker, ~4-5h) |
| ADR | 0035 |
| Phase A | CEL library dep 決定 (JCEL / proxima / 自前 mini-parser) |
| New migrations | V10 currency_issuance_log / V11 marketplace_listings / V12 creator_revenue_records |

**Scope**:
- `CurrencyPolicy` (JP regulatory compliance: 6ヶ月 expiry OR ¥10M cap、
  non-redeemable、tradable flag, sourceClass sealed: Purchased/Earned/Bonus)
  per RTM issue #19
- `CurrencyExpired` scheduled task + V10 per-issuance tracking
- `MarketplaceListing` + V11 + 3 use case (Create / Purchase / Cancel) per RTM #12
- `CreatorRevenue` share calc + V12
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

| Wave | Scope | Depends on | ADR |
|---|---|---|---|
| 8a | Web admin base (Nuxt 4 + REST/WebSocket scaffold) | Wave 6b | 0039 |
| 8b | REST API + WebSocket events | 8a | 0040 |
| 8c | UGC marketplace submission flow | 8b + Wave 5f marketplace | 0041 |
| 8d | Discord intake bot | 8b + Wave 6b broadcaster Discord | 0042 |
| 8e | Cosmetics + Gacha + Battle pass | Wave 5f marketplace | 0043 |
| 8f | Ban / Mute / Moderation tooling | 8a + Wave 5e audit log | 0044 |

Complexity 別 effort: 8a 大 (6 worker, ~6h)、8b/8c/8e 中 (5 worker, ~5h)、8d/8f 軽 (3 worker, ~3h)。

**Phase 4 完成: Wave 8f 終了時点**
**RTM 機能パリティ: ~85%** (RTM 超え)

---

## Phase 5 Metaverse Place (Wave 9a-c)

End state: **"Persistent player housing + SNS feed + seasonal events"**.

| Wave | Scope | ADR |
|---|---|---|
| 9a | Persistent plot + housing domain (Plot / Region permission / building) | 0045 |
| 9b | SNS + friend feed + notification | 0046 |
| 9c | Seasonal events automation (rotation + reward distribution) | 0047 |

**Phase 5 完成: Wave 9c**

---

## Phase 6 Generative AI (Wave 10a-f、partial running in Wave _assets parallel track)

End state: **"AI generates textures + sounds + 3D + translates chat + reviews PRs"**.

| Wave | Scope |
|---|---|
| 10a | Texture-gen continuation (16+ weapon archetype 横展開) |
| 10b | Sound-gen skill (ElevenLabs / Suno) |
| 10c | Model-gen skill (Meshy / Tripo) |
| 10d | LLM translation real (Wave 5f stub → GPT-4o-mini real per RTM design) |
| 10e | Weapon-designer agent (YAML + texture + sound 一括生成 agent) |
| 10f | AI PR review bot (architectural drift catch) |

All Wave 10 items run in **parallel** (each is an independent skill or agent).

---

## Phase 7 Ops automation (Wave 11a-d) + Wave _ops 並列

### Wave _ops (anytime parallel, can run during any of Wave 5-10)

| Item | ADR |
|---|---|
| KEDA dynamic Game Pod (matchmaking queue → spawn → scale to 0) | (existing reserved) |
| Sealed-Secrets vs SOPS 比較 | 0023 (reserved) |
| Provider 路線 (Oraxen / MythicMobs / WeaponMechanics adapter governance) | 0024 (reserved) |
| Micrometer + Prometheus + `/metrics` endpoint | new |
| Structured JSON logs (ECS) | new |
| Grafana dashboards | new |
| `make local-e2e` script + kind cluster e2e | new |

### Wave 11 sequence

| Wave | Scope |
|---|---|
| 11a | release-noter agent (commits → release notes) |
| 11b | incident-responder agent (Grafana alert → hypothesis + runbook) |
| 11c | Voting / affiliate system (community governance) |
| 11d | RTM 検定後継 (certification / 格付け / 称号 — RTM design rolled forward) |

---

## Cumulative milestone table

| Milestone | Wave | RTM parity | Session # |
|---|---|---|---|
| Phase 0 Infrastructure | 1-4 | ~10% | 4 (✅) |
| Phase 1 PoC closing (gap fill) | 4.5 | ~12% | 5 (✅) |
| Phase 1 PoC closing (docs realignment) | 4.6 | ~15% | 6 (✅) |
| Combat domain depth | 5a | ~22% | 7 |
| HUD ports | 5b | ~25% | 8 (5a 並列) |
| Match flow rich | 5c | ~30% | 9 |
| World geometry | 5d | ~38% | 10 (1-2 sess) |
| Session + Social | 5e | ~45% | 12 |
| Economy + extensibility | 5f | ~50% | 13 |
| Paper Combat | 6a | ~58% | 14 |
| Paper World + UI | 6b | **~65% (Phase 1 PoC 完了)** | 15 |
| Siege Warfare content | 7a | ~70% | 16 |
| Tournament + Leaderboard | 7b | ~73% | 17 |
| Balance + tooling | 7c | **~75% (Phase 3 完了)** | 18 |
| Phase 4 UGC + Web | 8a-f | ~85% | 19-24 |
| Phase 5 Metaverse | 9a-c | ~90% | 25-27 |
| Phase 6 Gen AI full | 10a-f | ~93% | 28-33 |
| Phase 7 Ops automation | 11a-d | ~98% | 34-37 |

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
| Wave _ops | 0023 / 0024 | Secrets + Provider 路線 |

Reserved but not yet drafted: 0026 (Wave 6a/6b), 0030-0047 (Wave 5a〜9c).

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
