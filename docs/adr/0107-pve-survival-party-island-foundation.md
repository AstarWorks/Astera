---
status: Accepted
date: 2026-05-24
accepted_date: 2026-05-24
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0107: Skyblock party-shared island foundation (生活軸)

## Status

Accepted (Wave 5pve-survival landed 2026-05-24)

## Context

Astera は Norma PvP / Wave 5pve PvE / Wave 5z Boss-raid に続く **4 つ目の
world tier**「生活の島」(Lobby / Match / PvE open / **Skyblock 生活**) を
Phase 3 ターゲットで構想していた (`.claude/plans/idea-pve-survival.md`)。
Round 4 計画で待機 idea md から **Phase 1 PoC closing 中に foundation を
fast-forward** することが決定 (`wave-5g-core-hardening-giggly-sunset.md`
§Round 4)。

ユーザー確認 2026-05-20:

> party-shared instance、2-10 人 / 1 instance、招待制、leader 全員 offline
> → island unload (rss 節約)

**設計選択の制約**:
- メタバース観点で「個人 island」より「複数人 party-shared」が交流的に良い
- per-player instance より party-shared の方がサーバー負荷が少ない
- 生活で集めた item を PvP match に持ち込むと経済 balance が崩れる
- Wave 5d schematic + Wave _persist-1 cross-server preserve 基盤を流用したい
- Wave 5e Party / Wave 5n Region 既存 type を再利用したい

**Phase 1 PoC closing posture** (`.claude/CLAUDE.md` §Pre-release posture)
を最大限活用: fallback shim / dual-decoder / `@Deprecated` を残さず、
真に長期的に効く設計を選ぶ。

リサーチ根拠: Hypixel SkyBlock の Co-op Island モデル (Phase 3 で party
splitting / island archival を refer する想定)。

## Decision

Wave 5pve-survival (2026-05-24 landed) で以下を Core 単体に foundation
として land した。**Paper adapter (gathering listener / region permission
連動 / 実 schematic snapshotter / Velocity proxy 分離) は Wave 6 で別建て**
— 本 wave は port + use case + 永続化 + YAML 仕様までを commit する。

### Domain (`core.domain.survival.*` + `core.domain.backup` 既存流用、計 10 file)

- `IslandId` — `core.domain.backup` で Wave 5w に予約済の value class を
  そのまま採用。重複定義しない。
- `IslandStatus` — enum `Active / Unloaded / Archived`。`HOT = {Active,
  Unloaded}` を伴う。
- `IslandVisibility` — sealed `PartyOnly / FriendsAllowed(invitedFriends) /
  Public`。`FriendsAllowed` 空 set は constructor reject。
- `IslandTemplate` + `IslandTemplateId` — schematic path + spawn offset +
  default plot size + default max members (2..10) を運ぶ author 定義
  preset。
- `PartyIsland` — `(IslandId, PartyId, WorldId, IslandTemplateId,
  maxMembers, IslandVisibility, IslandStatus, createdAt, lastActiveAt)`
  data class。`maxMembers ∈ [1..10]` enforce、`lastActiveAt >= createdAt`
  enforce、`transitionTo(IslandStatus, Instant)` で immutable copy
  ヘルパー。
- `PlotId` — UUID-backed value class (runtime mint)。
- `IslandPlot` — `(PlotId, IslandId, PlayerId, Region, Set<PlayerId>)`。
  `Region` は **既存 `core.domain.stage.Region`** を流用 (新 Cuboid は
  作らない)。owner ∈ buildAllowed enforce、`grantBuild / revokeBuild`
  ヘルパー、owner revoke は IllegalArgumentException。
- `GatheringSpotId` + `GatheringSpotKind` (sealed:
  `Fishing/Mining/Logging/Digging`) + `GatheringSkillTrigger` (sealed:
  `FishingRodCast/PickaxeSwing/AxeSwing/ShovelDig`) + `GatheringSpot` +
  `GatheringYield` + `GatheringSpotKind.matches(GatheringSkillTrigger)`
  extension。
- `GatheringProgress` — per-(player, spot) harvest record + tick-based
  cooldown helpers (`isOffCooldown`, `recordHarvest`, `first` factory)。
- `GameModeContext` — sealed `Lobby / PvpMatch / PveWorld /
  SurvivalIsland`。`IInventoryShareGuard` から consult される。
- `PartyInvitation` + `PartyInvitationId` — runtime-only 招待 record
  (in-memory)。createdAt < expiresAt enforce、inviter != invitee enforce、
  `isExpired(now)` helper。

### Cross-plugin events (`core.domain.event.*`, 4 new)

- `PartyIslandCreated(islandId, partyId, world, createdBy, occurredAt,
  version)`
- `PartyMemberInvited(invitationId, islandId, partyId, inviter, invitee,
  expiresAt, occurredAt, version)`
- `GatheringSpotHarvested(spotId, playerId, trigger, world, rolledYields:
  Map<ItemId, Int>, occurredAt, version)`
- `IslandUnloaded(islandId, partyId, world, reasonKind: String,
  occurredAt, version)` — `reasonKind` は snake_case discriminator
  (`leader_offline / admin_unload / archival_freeze`)、`BettingMatchResolved`
  pattern と同じ。

### Application ports (`core.application.port.outbound.survival.*`, 9 file)

- `IPartyIslandRepository` (in-mem default + Postgres overlay)
- `IIslandPlotRepository` (同)
- `IPartyInvitationRepository` (in-mem only、Phase 4 で Postgres overlay)
- `IGatheringProgressRepository` (in-mem + Postgres)
- `IGatheringSpotRegistry : Contents<GatheringSpotId, GatheringSpot>`
  (YAML-loaded `GatheringSpotContents` extends `YamlDynamicContents`)
- `IIslandTemplateRegistry` (in-mem; Phase 4 で YAML 化)
- `IIslandPersistence` — `save / load / place / exists` (schematic
  snapshot 抽象、Phase 4 で Postgres blob overlay)
- `IInventoryShareGuard` — `canCarryAcrossMode(itemId, from, to)` + `deny
  / clearDeny` (mode 間 item 持ち込み制御)
- `IGatheringYieldRoller` — `roll(spot): Map<ItemId, Int>` (テスト用
  seed-stable roller を別接続できる)
- `IPartyIslandManager` (lifecycle 高レベル façade: `create / load /
  unload / visit`)
- `IIslandPlotManager` (plot lifecycle façade: `assignPlot / canBuild /
  plotFor / plotsOn / releasePlot`)

### Use cases (`core.application.usecase.survival.*`, 8 file + 4 error file)

- `CreatePartyIslandUseCase` — テンプレ resolve + party 単独所有 enforce
  + `Active` で create + `PartyIslandCreated` publish
- `InvitePartyMemberUseCase` — inviter party member 確認 + invitee
  not-already-member + party-full + duplicate-invitation 検査 + 5 分 TTL
- `AcceptPartyInvitationUseCase` — invitee 一致 + 有効期限 + party-full
  最終確認 + party 集合更新 + invitation consume (event は別 — 既存
  `PlayerArrivedOnServer` で十分)
- `LoadPartyIslandUseCase` — `Active` 短絡 (lastActiveAt bump のみ) /
  `Unloaded` から schematic place + status flip / `Archived` は reject
- `UnloadPartyIslandUseCase` — schematic save (失敗時は `PlatformFailure`
  返却、idempotent flip) + `IslandUnloaded` publish。`Unloaded` 状態の
  再 unload は idempotent (event 二重発火しない)
- `VisitPartyIslandUseCase` — `IslandVisibility` 3 分岐 + status HOT 強制
- `AssignIslandPlotUseCase` — 1 member 1 plot、+X 軸沿いに plot レイアウト
  (`spawnOffset + nextIndex * (plotSize.x + 4)`)、template 不在時は
  `Vec3(16, 64, 16)` default
- `HarvestGatheringSpotUseCase` — per-spot cooldown
  (`ConcurrentHashMap<spotId, lastInstant>`) + per-player cooldown
  (`IGatheringProgressRepository`) + kind/trigger match enforce + yield
  roll + progress upsert + `GatheringSpotHarvested` publish

すべて `Result<T, sealed Error>` で application API surface (`IslandError /
PlotError / InvitationError / GatheringError`)。Konsist が application 層
の raw throwable 禁止を強制。

### Application services (`core.application.service.survival.*`, 7 file)

- `InMemoryPartyIslandRepository` (ConcurrentHashMap)
- `InMemoryIslandPlotRepository`
- `InMemoryPartyInvitationRepository` (+ `purgeExpired(now)` helper)
- `InMemoryGatheringProgressRepository` (`(playerId, spotId)` key)
- `InMemoryIslandTemplateRegistry`
- `InMemoryIslandPersistence` (`ISchematicLoader` 注入、`save` は
  `islands/<id>.schem` を deterministic に mint)
- `InMemoryInventoryShareGuard` — default rule: `SurvivalIsland ->
  PvpMatch` と `PveWorld -> PvpMatch` は item 持ち込み deny、それ以外は
  allow (currency tier は item ではないので allow)
- `UniformGatheringYieldRoller` — weighted-pick、`java.util.Random` 注入で
  決定的テスト可
- `DefaultPartyIslandManager` / `DefaultIslandPlotManager` (use case 集約
  façade)

### Application contents + YAML config

- `GatheringSpotContents` extends `YamlDynamicContents` — `reload` /
  `bootstrap(content/gathering/)` / `register(spec)` を提供。
  `IReloadableContentsRegistry` に登録、`/astera-survival reload gathering`
  で hot-swap。
- `GatheringSpotYamlConfig` (kaml DTO、`application.config.survival.*` —
  Konsist が domain で `kotlinx.serialization` 禁止) — wire format:

  ```yaml
  id: oak-grove
  display_name: { en: "Oak Grove", ja: "オークの森" }
  kind: logging       # fishing | mining | logging | digging
  trigger: axe_swing  # fishing_rod_cast | pickaxe_swing | axe_swing | shovel_dig
  world: survival-default
  position: { x: 100.5, y: 64.0, z: -42.5 }
  cooldown_ticks: 200
  yields:
    - { item_id: oak-log, weight: 80, min_count: 1, max_count: 3 }
    - { item_id: oak-sapling, weight: 20, min_count: 1, max_count: 1 }
  ```

- `content/gathering/` に sample 3 件 (`oak-grove`, `iron-vein`,
  `fishing-pond`)。

### Persistence (`:plugin:persistence`)

- **V41** `party_islands` — `island_id PK / party_id (unique) / world_id /
  template_id / max_members CHECK (1..10) / visibility_kind +
  visibility_payload JSONB / status CHECK (Active/Unloaded/Archived) /
  created_at / last_active_at CHECK >= created_at`。Visibility 分割
  (discriminator + JSONB allow-list) は ADR-0014 hybrid 規則に従う
  (discriminator は頻繁に query、friend list は visit 時のみ scan)。
- **V42** `island_plots` — `plot_id PK / island_id FK CASCADE / owner_id /
  6 column min/max bounds / build_allowed JSONB` + UNIQUE `(island_id,
  owner_id)`。Cuboid bounds を flat column にしたのは position-to-plot
  lookup を B-tree range index で効かせるため (JSONB だと SQL function
  必要)。
- **V43** `gathering_progress` — composite PK `(player_id, spot_id)` +
  `last_harvested_at` + `total_harvests >= 0`。`gathering_spots` への FK は
  **意図的に張らない** — YAML が SSOT で、孤立 progress 行は無害。
- Exposed tables `PartyIslandsTable / IslandPlotsTable /
  GatheringProgressTable` (新 package `persistence.table.survival.*`)。
- Postgres repos `PostgresPartyIslandRepository /
  PostgresIslandPlotRepository / PostgresGatheringProgressRepository`
  (`repository.survival.*`) を `AsteraPersistencePlugin` で `priority=100`
  overlay。
- JSONB DTO: `IslandVisibilityJson` (friend UUID 文字列 list) +
  `IslandPlotAllowedJson` (allow-list UUID 文字列 list)。

### Wiring (Core onEnable)

- 6 port の InMemory 既定実装を `services.register(..., priority = 0)` で
  登録 (`IPartyIslandRepository / IIslandPlotRepository /
  IGatheringProgressRepository / IPartyInvitationRepository /
  IIslandTemplateRegistry / IIslandPersistence / IInventoryShareGuard /
  IGatheringYieldRoller`)。
- `GatheringSpotContents` を `content/gathering/` から bootstrap して
  `IReloadableContentsRegistry` に登録。
- 5 つの use case を組み合わせて `DefaultPartyIslandManager` /
  `DefaultIslandPlotManager` を構築し port に登録。

### Konsist (+1 rule)

`survival domain stays pure (ADR-0107)` — `core.domain.survival.*` への
Bukkit / kotlinx.serialization / JDBC / Exposed / Postgres / lettuce / koin
import を禁止。Konsist rule 件数 44 → 45。

### i18n

`astera.social.party.error.*` (Wave 5e から dangling だった
`PartyError.messageKey` を実際に語彙登録) + `astera.social.island.*` +
`astera.social.island.plot.*` + `astera.social.island.invite.*` +
`astera.survival.gathering.*` の全 keys を `en.yaml` / `ja.yaml` 両方に
追加。i18n parity 維持 (parity test green)。

### テスト (12 file, 79 test)

- domain 4: `PartyIslandTest` / `IslandPlotTest` / `GatheringSpotTest` /
  `GatheringProgressTest`。
- use case 7: `CreatePartyIslandUseCaseTest` /
  `InvitePartyMemberUseCaseTest` / `AcceptPartyInvitationUseCaseTest` /
  `UnloadAndLoadPartyIslandUseCaseTest` / `VisitPartyIslandUseCaseTest` /
  `AssignIslandPlotUseCaseTest` / `HarvestGatheringSpotUseCaseTest`。
- service 2: `InMemoryInventoryShareGuardTest` /
  `InMemoryIslandPersistenceTest`。

すべて Bukkit harness 不要 (純 JVM)。

## Consequences

### Positive

- **生活軸 foundation 完成**: party-shared instance / 採取 / plot /
  inventory boundary という Phase 3 生活軸の核 6 port が Wave 6 Paper
  adapter から呼び出せる状態に。
- **既存型再利用が徹底**: `IslandId` (Wave 5w 予約)、`Region` (Wave 5n
  stage)、`PartyId` (Wave 5e)、`Schematic` (Wave 5d)、`Contents` substrate
  (`YamlDynamicContents`) — 並行 type を 1 つも作っていない。
- **Cross-server preserve 経路あり**: Postgres overlay により island /
  plot / gathering progress は server 再起動 + cross-server hop で保持
  (Wave _persist-1 と同じ流儀)。
- **Wave 6 Paper adapter の作業範囲が単純化**: gathering listener →
  `HarvestGatheringSpotUseCase` 呼び出し、`PaperSchematicLoader` →
  `InMemoryIslandPersistence` 差し替え、leader-offline sweep →
  `UnloadPartyIslandUseCase` 駆動。Core 側に Paper を入れる作業は zero。
- **経済 balance 保護**: `IInventoryShareGuard` の default rule で
  Survival/PvE → PvP item flow を deny、port 経由で per-item override 可。

### Negative / Trade-offs

- **`IIslandTemplateRegistry` が in-mem only**: foundation 段階では
  template YAML schema が未確定 (`floating-plains` vs `tier-1-mountain`
  variant 等) なので、platform bootstrap で programmatic register する
  形になっている。Phase 4 で YAML 化 (`YamlDynamicContents` 化)。
- **One island per party**: `party_islands(party_id) UNIQUE` 制約で
  party は 1 island のみ。relax 必要なら drop UNIQUE。
- **`IIslandPersistence` が schematic file ベース**: Phase 4 で Postgres
  blob / S3 cold storage を overlay で追加予定。今は filesystem path
  生成 (`islands/<id>.schem`) のみ。
- **Plot レイアウトが trivial**: foundation 版は +X 軸沿いに line out。
  本格的 spatial planner は Wave 6 Paper adapter で chunk-aligned に
  上書き予定。
- **archival job 未実装**: `IslandStatus.Archived` は予約のみ。7 日
  idle archive は Phase 4。

### Mitigations

- `IIslandTemplateRegistry` は **port なので** Phase 4 で
  `YamlDynamicContents` impl 差し替えに caller-side 変更不要。
- Plot レイアウトの簡略化は **use case 単独** に閉じている (`spawnOffset
  + index * stride` の単純式) ので Wave 6 で `AssignIslandPlotUseCase`
  本体を上書きすれば済む。
- `IslandPersistence` impl 差し替えは `services.register<IIslandPersistence>(
  ..., priority = 100)` で overlay (既存 schematicLoader と同パターン)。

## Alternatives Considered

- **Alt A: per-player private island** (経済簿記が完全に分離して安全)
  — メタバース観点で交流 0 になる、user 拒否 (「party-shared がいい」)。
- **Alt B: 単一巨大 island、player を全員詰め込む** (rss 最小) —
  Phase 1 demo 用に config で復活可能だが、Phase 3 で party split は必須、
  scope-bloat。
- **Alt C: 新 Cuboid 型を導入** (idea md 原案) — `Region` と同義になる
  ので Konsist が将来 flag する重複を作るだけ。`Region` 流用に決定。
- **Alt D: 新 IslandId 型を `domain/survival/` に重複定義** — Wave 5w が
  `domain/backup/` に予約済の値を上書きする破壊変更になる。流用採用。
- **Alt E: gathering trigger を `SkillTrigger` 家族に合流** — combat /
  gathering の意味境界が曖昧になる、exhaustive `when` に else 漏れが入る
  リスク。trigger family を別 sealed として明示分離。
- **Alt F: Invitation を Postgres 永続化** — Phase 1 では invitation TTL
  5 分しかなく、server 再起動で消えても許容範囲。Phase 4 で Notification
  Gateway と一緒に永続化計画。
- **Alt G: `IInventoryShareGuard` を hardcode rule table** — Wave 5f
  marketplace / Wave 5pve loot が同 matrix を再実装することになる。port
  で 1 consult 点に集約。

## References

- 関連 ADR:
  - [[adr/0022]] (Multi-plugin portable architecture — Core 単体に
    foundation を入れる根拠)
  - [[adr/0073]] (Item-first — `ItemId` 流用、生活 item は外部 system
    でなく ItemSpec 自体)
  - [[adr/0040]] (Party / Social 集合 — `Party` 流用)
  - [[adr/0068]] (Cross-server persistence — Postgres overlay 同パターン)
  - [[adr/0085]] (PvE mob foundation — `YamlDynamicContents` 同パターン)
  - [[adr/0089]] (Server lifecycle + restic backup — `BackupTarget.IslandWorld`
    が Wave 5w に予約済の `IslandId` を本 ADR で実利用)
  - [[adr/0092]] (Region permission — `Region` 流用、Wave 6 で plot ↔
    region rule 連動)
  - [[adr/0014]] (Persistence: JSONB vs column rule of thumb —
    `party_islands.visibility` 分割の根拠)
- 関連 plan:
  - `.claude/plans/idea-pve-survival.md` (本 ADR の原案 idea md)
  - `.claude/plans/wave-5g-core-hardening-giggly-sunset.md` §Round 4
    (本 wave の roadmap allocation)
- Hypixel SkyBlock Co-op Island — party-shared instance の design 先例
