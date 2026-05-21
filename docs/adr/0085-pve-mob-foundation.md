---
status: Accepted
date: 2026-05-21
deciders: orchestrator (Wave 5pve)
supersedes: null
superseded_by: null
---

# ADR-0085: PvE mob foundation — MobSpec + IMobAdapter two-tier

## Status

Accepted

## Context

Astera Annihilation のビジョン (`.claude/plans/idea-annihilation-vision.md`) と
Wave 5g 親 plan v5 §"Wave 5pve" は、Phase 1 PoC の最後に **PvP の対極軸として
mob hunting open world** を投入することを要求する。これにより:

- **PvP retention の補強**: PvP マッチ前後の "ロビー世界" を PvE で繋ぐ
- **両軸 PoC**: Norma 経済 (Wave 5e) が PvP / PvE 両ソースから XP / アイテムを
  受け取れることを実証
- **content/loot/ + Phase 4 Battle Pass** への土台

ただし以下の制約が走る:

1. **MythicMobs は permitted soft-dep であって mandatory ではない**: ベースの
   バニラ Paper でも mob は spawn 可能でなければならない (Wave 5l の
   `INpcAdapter` two-tier と同じ哲学)。
2. **Mob skill は Astera SkillId 流用**: 既に存在する `ISkillRegistry` /
   `SkillCastRequested` を mob 側でも使う。MythicMobs 独自の skill 語彙を
   並列に持つと combat resolve が二系統化する。
3. **PvP / PvE 軸分離**: mob は match world と open world の両方で spawn 可能。
   PvP match では `IMobSpawnLadder` の per-region cap = 0 で抑制。
4. **Phase 1 / Phase 4 分担**: domain shape + Minimal adapter + MythicMobs port
   skeleton は Phase 1 で commit、MythicMobs Paper 実装と本格 loot table /
   XP yield table は Phase 4。
5. **Pre-release breaking changes 方針** (`.claude/rules/coding-posture.md
   §Pre-release`): dual-decoder / 旧 schema fallback は書かない。設計が clean
   なら破壊的変更で OK。

選択肢:

- **A**: Mob を `:plugin:combat` に置く (combat が mob と damage の両方を所有)
- **B**: 新しい `:plugin:mob` plugin を作る
- **C**: Core の `core.domain.mob.*` + `core.application.{port,service}.mob.*`
  に置く (Wave 5l の `core.domain.entity.*` と同居)
- **D**: Mob skill を MythicMobs API に直接委譲 (Astera SkillId 流用しない)

## Decision

**C + Wave 5l の `INpcAdapter` two-tier を mob にも適用** する。

1. `MobSpec` + 周辺 (`MobTypeId`, `MobSkillBinding`, `SpawnConditions`,
   `MobAppearance`, `MobAiHint`, `MobError`, `MobBackendId`, `MobRegionId`,
   `LootTableId`) を `core.domain.mob.*` に置く (pure domain、Bukkit-free、
   MythicMobs-free)。
2. `IMobAdapter` port + Two-tier 実装:
   - `MinimalMobAdapter` (Core, always available, `backendId = "minimal"`)
     — ArmorStand + Display entity ベース、`MobSkillTriggered` event 発火、
     Wave 6b で Paper subclass が real entity spawn を overlay。
   - `MythicMobsMobAdapter` (Core, **Phase 1 skeleton**, Wave 6b Paper subclass
     が active 化、Phase 4 で full impl、`backendId = "mythicmobs"`) —
     `isAvailable() = false` を返すだけ。実装本体は将来。
3. `IMobSpawner` (router) は両 adapter を priority 順に並べ、最初に
   `isAvailable() = true` な adapter を選択 (Wave 5l 同型)。
4. `IMobRegistry` は `YamlDynamicContents<MobTypeId, MobSpec, MobYamlConfig>`
   substrate (ADR-0065) で `/astera reload mobs` 対応。
5. Open world hunting integration ports (Phase 1 commit、Phase 4 で本格化):
   - `IMobSpawnLadder` — per-region candidate set + concurrent cap
   - `IPveLootDistributor` — mob kill → loot table → drop (Phase 1 は Noop)
   - `IPveXpDistributor` — mob kill → XP grant (Phase 1 は Noop)
6. Domain events: `MobSpawned`, `MobKilled`, `MobSkillTriggered`,
   `PveLootDistributed`, `PveXpGranted`。
7. **Skill cast event の分離**: mob cast は `MobSkillTriggered` のみ発火 (player
   item cast の `SkillCastRequested` とは別 path)。`SkillCastRequested` は
   non-null `actorId: PlayerId` + `sourceItemId: ItemId` を要求しており、mob
   cast で nullable 化すると blast radius が広い。Combat (Wave 6a) は両 event
   を別々に subscribe する。
8. Konsist 2 rule 追加:
   - `IMobAdapter implementations must declare backendId() uniquely`
   - `core.domain.mob.* must not import io.lumine.mythic.*`

### Wave 6b 担当 (このADRが commit する port skeleton の実装)

- `PaperMinimalMobAdapter`: ArmorStand + DisplayEntity の real spawn。
- `PaperMythicMobsMobAdapter`: MythicMobs API 検出 + spec translation。
- Combat plugin が `MobSkillTriggered` を subscribe してmob attack resolve。
- Paper damage hijack (ADR-0030 §8) との integrate。

### Phase 4 担当

- `ContentLootDistributor` — `content/loot/` substrate + 重み付き roll。
- `BalancedPveXpDistributor` — XP curve + per-mob yield table。
- `TimeBucketSpawnLadder` — Wave 5c Star Generator 流儀の tier 進行。
- Battle Pass XP source として `PveXpGranted` を subscribe。

## Consequences

### Positive

- **Vendor isolation**: MythicMobs install 不要で baseline 動作。Konsist で
  vendor leak を build-time に catch。
- **Skill resolve 一元化**: mob skill = player skill = 同じ `SkillId` + 同じ
  combat 解決 path。新 skill を作るたびに mob 側を別途実装する必要なし。
- **Two-tier 哲学の一貫性**: Wave 5l (`INpcAdapter`)、Wave 5t (`IPermissionBackend`,
  `IRichPresenceGateway`) と同じ adapter selector パターン。読み手の認知負荷低。
- **PvP / PvE 軸分離**: `IMobSpawnLadder` の per-region cap で per-match
  config から mob spawn を抑制可能。
- **Phase 1 PoC critical path 完了**: Wave 5pve = domain wave critical path
  最後。これで Wave 6 Paper sweep に進める。

### Negative / Trade-offs

- **Phase 1 では in-memory state のみ**: MinimalMobAdapter は real entity を
  spawn しない (handle + position bookkeeping のみ)。Wave 6b の Paper
  subclass が landing するまで visible な mob は出ない。
- **`MobRegionId` の forward-coupling**: Wave 5n の `core.domain.world.RegionId`
  が mainline 化した時点で typealias / supersede ADR が必要。
- **`MobNameTagColor` の forward-coupling**: Wave 5j の `HudColor` mainline 化
  時点で typealias / supersede ADR が必要。
- **MythicMobs Adapter skeleton は dead code**: Phase 4 まで `isAvailable() =
  false` を返すだけの class。但し router pattern を Phase 1 から exercise
  するためには必須。
- **Skill cast 分離 (`MobSkillTriggered` vs `SkillCastRequested`) は subscriber
  二重実装**: Combat plugin が両方 subscribe する手間が増える。一本化する
  なら `SkillCastRequested.actorId` / `sourceItemId` を nullable 化する破壊的
  変更が必要 (将来 ADR で再考可)。

### Mitigations

- **Wave 6b roadmap 明記**: PaperMinimalMobAdapter / PaperMythicMobsMobAdapter
  + Combat 側の `MobSkillTriggered` subscribe を Wave 6 sprint 計画書に追加。
- **`MobRegionId` / `MobNameTagColor` の forward-coupling 注記**: 該当 file
  KDoc に "Wave 5n / 5j 着地時点で supersede" 明記。
- **MythicMobs skeleton は Konsist test の "at least 1 IMobAdapter exists"
  guard 内で active validation 対象**: dead code であっても backend uniqueness
  + skeleton presence は CI で守られる。

## Alternatives Considered

- **A. Mob を combat plugin に置く**: combat module が爆発的に肥大化、
  cross-plugin 依存 (Items → Combat の mob 参照、Stats → Combat の kill 通知)
  が逆方向になり ADR-0022 sibling isolation を破る。却下。
- **B. `:plugin:mob` plugin を新設**: 7-plugin 構成に 8 番目を追加すること
  になり ADR-0022 cardinality を増やす。Phase 1 では Wave 6a Paper sweep の
  blast radius が懸念。Phase 4 で必要なら supersede ADR で plugin 化検討可。
- **D. Mob skill を MythicMobs に委譲**: Astera SkillId 流用が崩れる。combat
  resolve が二系統化、Norma `kill mob with X skill` 系の進捗トラッキングが
  分岐し複雑化。却下。
- **`SkillCastRequested.actorId` を nullable 化**: mob cast を一本化できる
  メリットはあるが、全 subscriber (Stats, Norma, audit, anti-cheat) の
  null-check 追加が必要。Wave 5pve scope を超えるため後回し。

## References

- 関連 ADR: [[adr/0022-multi-plugin-portable-architecture]],
  [[adr/0058-universal-attribute-container]],
  [[adr/0065-per-category-reload-dispatcher]],
  [[adr/0073-item-centric-content-model-and-skill-binding]],
  [[adr/0077-mechanic-targeter-phase-skill-architecture]],
  [[adr/0082-entity-spawning-and-npc-two-tier]],
  [[adr/0083-anti-cheat-and-anti-afk]],
  [[adr/0086-admin-ops-permission-presence-feedback]]
- 関連 plan: `.claude/plans/wave-5g-core-hardening-giggly-sunset.md` §"Wave 5pve",
  `.claude/plans/idea-annihilation-vision.md`
- 関連 Konsist rules:
  - `IMobAdapter implementations must declare backendId() uniquely (ADR-0085)`
  - `core domain mob must not import MythicMobs API (ADR-0085)`
