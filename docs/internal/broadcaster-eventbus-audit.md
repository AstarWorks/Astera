# Wave 2 R1.d — Broadcaster vs EventBus 役割監査

**Status**: Phase A 暫定 reference (Wave 2 セッション中)
**Successor**: ADR-0021 *Event Wiring & Broadcaster/EventBus Split* (Phase C で起草、accepted 後に本ファイルは削除)
**Audited at**: commit `7a025fe` (2026-05-17)

## ルール (silly-knuth §5 / ADR-0022 由来)

silly-knuth 計画 §5 *Event 系の設計制約* + ADR-0022 *Multi-plugin Portable Architecture* より:

1. **DomainEvent の独立性** — Bukkit Event を直接 domain/application に持ち込まない。
   `adapter-minecraft-impl-paper` の `BukkitEventAdapter` が Bukkit Event → 自作 DomainEvent
   に変換。DomainEvent は `domain/event/` 配下、`org.bukkit.*` import 一切なし
2. **`IBroadcaster.publish(event)`** — **外部 fan-out** 用途
   (Redis pub/sub、Discord webhook、persistence など)。Phase 1 は logging のみ、
   Wave 5 で Redis/Discord 実装
3. **`IDomainEventBus.publish(event)` + `subscribe(type, handler)`** — **in-process
   同一 JVM 通知**。cross-plugin listener の single source of truth
4. **二段化** — 「in-process 通知 ＋ 外部 fan-out」の両方が欲しい場合は、
   まず `eventBus.publish` → その subscriber 経由で必要なら `broadcaster.publish`
   を呼ぶ二段構成。eventBus が single source of truth、broadcaster は subscriber

## 呼び出し全列挙

### broadcaster.publish(...)  (外部 fan-out 用途)

| 呼び出し元 | イベント型 | 分類 |
|---|---|---|
| `plugin/weapons/.../usecase/FireWeaponUseCase.kt` | `WeaponFired` | ✅ 外部 fan-out |

**Wave 2 で追加予定**: `LeveledUp` を `broadcaster.publish` で配信
(in-game chat / Discord 通知向け)。

### eventBus.publish(...)  (in-process 用途)

| 呼び出し元 | イベント型 | 分類 |
|---|---|---|
| `plugin/combat/.../skill/CastSkillUseCase.kt` | `SkillCast` | ✅ in-process |
| `plugin/combat/.../damage/ApplyDamageUseCase.kt` | `DamageDealt` | ✅ in-process |
| `plugin/combat/.../damage/ApplyDamageUseCase.kt` | `PlayerKilled` | ✅ in-process |
| `plugin/combat/.../status/ApplyStatusUseCase.kt` | `StatusApplied` | ✅ in-process |
| `plugin/stats/.../stats/RecordDeathUseCase.kt` | `StatsUpdated` | ✅ in-process |
| `plugin/stats/.../stats/RecordKillUseCase.kt` | `StatsUpdated` | ✅ in-process |
| `plugin/stats/.../level/GrantXpUseCase.kt` | `XpGranted` | ✅ in-process |
| `plugin/stats/.../level/GrantXpUseCase.kt` | `LeveledUp` | ✅ in-process |
| `plugin/stats/.../mission/CompleteMissionUseCase.kt` | `MissionCompleted` | ✅ in-process |
| `plugin/stats/.../listeners/MissionProgressOnKillListener.kt` | `MissionCompleted` | ✅ 二段化 (PlayerKilled subscriber 内から再 publish) |
| `plugin/match/.../match/StartMatchUseCase.kt` | `MatchStarted` | ✅ in-process |
| `plugin/match/.../match/EndMatchUseCase.kt` | `MatchEnded` | ✅ in-process |
| `plugin/match/.../match/SwitchPhaseUseCase.kt` | `PhaseChanged` | ✅ in-process |
| `plugin/economy/.../currency/DepositUseCase.kt` | `CurrencyChanged` | ✅ in-process |
| `plugin/economy/.../currency/TransferUseCase.kt` × 2 | `CurrencyChanged` | ✅ in-process |
| `plugin/economy/.../currency/WithdrawUseCase.kt` | `CurrencyChanged` | ✅ in-process |

### eventBus.subscribe(...)  (Stage 3-改 までの配線、参考)

| Plugin | イベント | handler |
|---|---|---|
| Economy | `XpGranted` | `DepositOnXpListener` (金銭報酬付与) |
| Stats | `PlayerKilled` | `RewardOnKill` |
| Stats | `PlayerKilled` | `MissionProgressOnKill` |
| Stats | `DamageDealt` | `RewardOnDamage` |

## 違反候補

**0 件**。19 publish + 4 subscribe すべてルール適合。
Phase C で修正対象なし → me の統合作業は wire のみで完結。

## Wave 2 で追加する配線 (R1.b)

下記 6 件を Phase B worker γ が新規 listener として実装、Phase C で me が
各 `AsteraXxxPlugin.onEnable` に subscribe wire を入れる。

| 新規 listener | subscribe | 呼び出し先 |
|---|---|---|
| `RecordMatchPlayedOnMatchEndedListener` | `MatchEnded` | Stats `RecordMatchPlayedUseCase` (新規 or 既存拡張) |
| `RecordMatchResultOnMatchEndedListener` | `MatchEnded` | Stats `RecordMatchResultUseCase` (新規、win-loss 更新) |
| `RewardOnMatchEndedListener` | `MatchEnded` | Economy reward payout (winner に currency deposit) |
| `DepositOnMissionCompletedListener` | `MissionCompleted` | Economy deposit (`NormaReward.currencyAmount` 分) |
| `BroadcastOnLeveledUpListener` | `LeveledUp` | `IBroadcaster.publish` (外部 fan-out、Wave 5 で Redis/Discord 配線) |
| (任意) `RecordMatchEndedDebugListener` | `MatchEnded` | logging only (削除可) |

## Phase B worker briefing 用 シンボル location

| シンボル | 絶対パス |
|---|---|
| `IBroadcaster` | `plugin/core/src/main/kotlin/com/astarworks/astera/core/application/port/outbound/IBroadcaster.kt` |
| `IDomainEventBus` | `plugin/core/src/main/kotlin/com/astarworks/astera/core/application/port/outbound/IDomainEventBus.kt` |
| `FakeBroadcaster` (test) | `plugin/test-fixtures/src/main/kotlin/com/astarworks/astera/testfixtures/FakeBroadcaster.kt` |
| `DefaultRoles` | `plugin/core/src/main/kotlin/com/astarworks/astera/core/domain/permission/DefaultRoles.kt` |
| `MatchEnded` | `plugin/match/src/main/kotlin/com/astarworks/astera/match/domain/event/MatchEnded.kt` |
| `MissionCompleted` | `plugin/stats/src/main/kotlin/com/astarworks/astera/stats/domain/event/MissionCompleted.kt` |
| `LeveledUp` | `plugin/stats/src/main/kotlin/com/astarworks/astera/stats/domain/event/LeveledUp.kt` |
| `ContentLoader<T>` | `plugin/core/src/main/kotlin/com/astarworks/astera/core/application/service/ContentLoader.kt` |
| `SchemaGenerator` | `tools/schema-gen/src/main/kotlin/com/astarworks/astera/schemagen/SchemaGenerator.kt` |
| `WeaponYamlConfig` (雛形) | `plugin/weapons/src/main/kotlin/com/astarworks/astera/weapons/application/config/WeaponYamlConfig.kt` |
| `SkillYamlConfig` (雛形) | `plugin/combat/src/main/kotlin/com/astarworks/astera/combat/application/config/SkillYamlConfig.kt` |
| `IMatchRepository` | `plugin/match/src/main/kotlin/com/astarworks/astera/match/application/port/outbound/IMatchRepository.kt` |
