---
status: Accepted
date: 2026-05-19
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0079: Pre-Paper-sweep depth — close 6 domain gaps before Wave 6

## Status

Accepted

## Context

Pre-Wave-6 readiness audit (2026-05-19) で **6 つの domain gap** を発見:

1. **Hologram**: `combat.domain.projectile.DisplaySpec` は projectile 専用の
   minimal commitment。Bukkit `Display` API の Transformation / Interpolation
   / kind-specific fields を表現しておらず、capture progress / generator
   label / NPC nameplate / boss name のような pure-label hologram の domain
   commitment が無い。Wave 6b で `PaperHologramGateway` を書く時に「先に
   domain 設計が必要」となり Wave 6 が pure-impl wave でなくなるリスク。

2. **GUI slot expansion**: Wave 4 `GuiSlot` sealed は Static / Reactive /
   Empty / Border の 4 variant のみ。shop 購入確認 / settings 編集 / report
   入力 / chat alias 編集 が UI で必要だが Domain commitment 無し。Wave 6b
   で UI を組む時に「ad-hoc に 5 つ目を生やす」誘惑が出る。

3. **Tutorial player flow**: Wave 5e で `TutorialScript` / `RunTutorialUseCase`
   は land 済みだが、**player の完了/skip 状態を持つ port が無い**
   (`IPlayerTutorialStateRepository` 欠落)。「初回 join で tutorial を
   起動」「skip した player に再起動しない」「`/astera tutorial replay`
   で再起動」が全部実装不可。

4. **Moderation Core 4**: `IModerationService` を含む moderation domain が
   **完全未着手**。`SendChatMessageUseCase` に mute check 経路無し。
   live testing が始まると chat spam / 迷惑行為への対応手段がゼロ。

5. **Match disconnect/reconnect**: `PlayerSession.Disconnected` state は
   sealed enum に存在するが、`PlayerQuitServer` 中の match 内処置 +
   `PlayerJoinedServer` 中の resume offer の listener wiring が完全に未完。
   ACTIVE 中に disconnect → 復帰すると match 状態が崩壊する潜在 bug。

6. **Spectator API**: `EnterSpectatorUseCase` / `LeaveSpectatorUseCase` に
   "TODO(Wave 6): inject IWorldGateway once it exposes a spectator-spawn
   API" コメントが残る。`spectatorSpawn` / `lobbySpawn` の port semantic 未定義。

これらを Wave 6 に持ち込むと、Paper impl wave が「先に port 設計を完成
させる」必要があり scope が膨張。Wave 6 を「Bukkit listener + adapter
コードだけ書く」に純化するため、Wave 5h で 6 領域一括 close。

**ユーザ確認 scope decisions** (Phase 3, 2026-05-19):
- 6 gap を 1 wave で close (split しない)
- Moderation = Mute / Kick / Ban / ChatRateLimit の 4 variant (Report は Phase 4 commitment)
- Hologram = Bukkit `Display` の **full richness** (3 DisplayEntity + Quaternion
  Transformation + Interpolation + Brightness)

## Decision

### §1. Hologram domain (vendor-neutral Display API mirror)

新 domain (in `plugin/core/.../domain/hologram/`):

- `Hologram` aggregate + `HologramId` value class + `HologramHandle` opaque value class
- `HologramKind` sealed:
  - `Text(lines: List<MessageKey>, lineWidth, backgroundColor?, textOpacity, shadowed, seeThrough, defaultBackground, alignment)`
  - `Item(itemId: ItemId, displayMode: ItemDisplayMode)`
  - `Block(blockKey: String)`
- `HologramTransform(translation, leftRotation, scale, rightRotation)` — Bukkit
  `Transformation` field-by-field 1:1。**`leftRotation` / `rightRotation` の型は
  `org.joml.Quaternionf` 直接** (Wave 5h-fix、user feedback 2026-05-19):
  JOML は pure math library、Bukkit 非依存、Paper API が transitively 既に
  classpath に乗せている。独自 Quaternion 再実装は Hamilton-product math の
  reimplementation cost + Wave 6b adapter の boundary 変換 glue を生むので却下、
  Bukkit `Transformation` constructor が直接 `Quaternionf` を取るので domain →
  adapter は zero-conversion。Konsist の "Bukkit-free domain" rule は
  `org.bukkit.* / io.papermc.* / etc.` のみ forbid、`org.joml.*` は許容。
- `HologramInterpolation(durationTicks, delayTicks, teleportDurationTicks)`
- `HologramBrightness(blockLight, skyLight)` (range 0-15)
- `HologramColor(red, green, blue, alpha)`
- `BillboardMode` enum (移動: `combat.domain.projectile` → `core.domain.hologram`)
- `TextAlignment` / `ItemDisplayMode` enum (Bukkit mirror)
- `HologramVisibilityScope` sealed: `Everyone` / `PerPlayer(playerIds: Set<PlayerId>)` / `PerTeam(matchId, teamId)`

新 port: `IHologramGateway` (`spawn / despawn / updateTransform / updateKind / teleport / all`) + `HologramError` sealed (`UnknownWorld / UnknownHandle / InvalidTransform / Storage`)。

Wave 5h ships `InMemoryHologramGateway` (priority 0) + `FakeHologramGateway`
(test-fixtures、operation history)。**Wave 6b で `PaperHologramGateway`** が
Bukkit `TextDisplay` / `ItemDisplay` / `BlockDisplay` を実体化、Astera
`Quaternion` を `org.joml.Quaternionf` に変換 (edge にて閉じる)。

**DisplaySpec coexist 方針**: 既存 `combat.domain.projectile.DisplaySpec`
は projectile 用 trajectory state のため Hologram と semantic 異なる
(transient lifetime + tick motion)。両者 coexist、`BillboardMode` だけ共有。
Wave 6a `PaperProjectileService` 実装時に再評価。

### §2. GUI slot expansion (Confirmation + TextInput)

`GuiSlot` sealed に 2 variant 追加:

- `Confirmation(promptKey, confirmItemId, cancelItemId, confirmIntent, cancelIntent)` — 2-button 確認 prompt、Paper impl (Wave 6b) は 2 hotbar item
- `TextInput(placeholderKey, maxLength = 32, onSubmitIntent)` — anvil GUI text input (Wave 6b)、submit text は intent payload key `"text"`

`GuiDiffEngine` exhaustive `when` を維持 (compile catch)、`DefaultGuiBindingResolver`
は pass-through、YAML schema + parser 拡張、example YAML 2 file。

### §3. Tutorial player flow

新 domain `PlayerTutorialState(playerId, completedTutorialScriptIds: Set,
skippedTutorialScriptIds: Set, currentTutorial: TutorialScriptId?,
currentStepIndex: Int?, updatedAt)` + pure helper methods
(`markCompleted / markSkipped / startTutorial / advanceStep / clearCurrent`)。

新 port `IPlayerTutorialStateRepository` (find / upsert / delete) +
`InMemory` / `Postgres` / `Fake` 3 impl。

新 V20 migration `player_tutorial_state(player_id PK, completed_tutorials
JSONB, skipped_tutorials JSONB, current_tutorial, current_step, updated_at)`。

新 use case:
- `StartTutorialOnFirstJoinListener(PlayerJoinedServer)` — 初回 join で
  default tutorial を起動 (state 空 && skipped に default が無い場合)。
  default tutorial id は constructor 注入 (Phase C で wire)。
- `SkipTutorialUseCase` / `ReplayTutorialUseCase` / `AdvanceTutorialStepUseCase`

新 domain event: `TutorialStarted` / `TutorialCompleted` / `TutorialSkipped`

新 command: `/astera tutorial skip <id>` / `/astera tutorial replay <id>`
(permission `astera.tutorial.{skip,replay}`、PLAYER role 自動付与)

### §4. Moderation Core 4

`ModerationAction` sealed 4 variant:
- `Mute(playerId, issuedBy, reasonKey, issuedAt, expiresAt?, liftedAt?)` — `expiresAt = null` で permanent
- `Kick(playerId, issuedBy, reasonKey, issuedAt, liftedAt?)` — `expiresAt` 強制 null (instantaneous)
- `Ban(..., scope: BanScope)` + `BanScope sealed { Global / PerServer(serverId) }`
- `ChatRateLimit(..., ratePerSecond: Double, burstSize: Int)`

新 port `IModerationService` (`apply / lift / activeFor / history`) + 3 impl
(InMemory / Postgres / Fake)。

新 V21 migration `moderation_actions(id PK, player_id, kind VARCHAR CHECK IN
('mute','kick','ban','chat_rate_limit'), reason_key, issued_by, issued_at,
expires_at?, lifted_at?, payload JSONB)` + `idx_mod_active` 複合 index。

新 use case:
- `ApplyModerationActionUseCase` (per-variant permission gate: `astera.mod.{mute,kick,ban,ratelimit}`)
- `LiftModerationActionUseCase` (`astera.mod.lift`)
- `QueryActiveModerationUseCase` (no gate)

**`SendChatMessageUseCase` 拡張**: `moderationService.activeFor(playerId)`
で Mute / ChatRateLimit を検査、Mute は `ChatError.Muted`、ChatRateLimit は
in-memory token bucket 超過時 `ChatError.RateLimited` 返却。

**`DefaultRoles` 拡張**: 新 role `MODERATOR_LITE` (mute / ratelimit のみ)、
既存 `MODERATOR` に `kick / ban / lift` 追加。

新 command: `/astera mod {mute|kick|ban|ratelimit|lift|history}`

**Paper enforcement = Wave 6b 委譲**: Wave 5h は domain decision のみ。
Wave 6b で `PlayerLoginEvent` の Ban check、`AsyncChatEvent` の Mute / Rate
cancel、`Player.kick()` の Kick execution を wire。Wave 5h 完了時点では
mute/ban を apply しても server から実際に kick されない / chat が止まらない
(DB に行が増えるだけ) — KDoc で明文化。

### §5. Match disconnect / reconnect

新 listener:
- `DisconnectDuringMatchListener` (PlayerQuitServer): phase 別 rule
  - `COUNTDOWN` → `RemoveFromMatchUseCase(reason = Disconnect)`
  - `ACTIVE` → state を `Disconnected` で freeze + 3 min reconnect window
    (`IScheduler.runDelayedTicks(3600 ticks)`, 20 tps × 180s = 3600 ticks)、
    timeout 時 kick + `PlayerDisconnectedFromMatch(willTimeoutAtTick)` publish
  - `SUDDEN_DEATH` → `ApplyForfeitUseCase`
  - `ENDED / WAITING` → no-op
- `ReconnectDuringMatchListener` (PlayerJoinedServer): state `Disconnected`
  + `currentMatch != null` なら `ResumeMatchUseCase` 起動、失敗時 lobby spawn

新 use case + sealed `RemoveReason` (`Disconnect / Forfeit / Kicked(by, reasonKey) / Banned`)

新 domain event: `PlayerDisconnectedFromMatch` / `PlayerReconnectedToMatch`
/ `PlayerForfeited`

新 cross-cutting domain event: `PlayerQuitServer` (`PlayerJoinedServer` の mirror、existing pattern)

### §6. Spectator API extension

`IWorldGateway` 拡張:
- `spectatorSpawn(matchId, playerId): Result<Unit, WorldGatewayError>` — match-specific spectator vantage point
- `lobbySpawn(playerId): Result<Unit, WorldGatewayError>` — server-global lobby spawn

新 sealed `WorldGatewayError` (`UnknownMatch / UnknownPlayer / Storage`)

`EnterSpectatorUseCase` / `LeaveSpectatorUseCase` の TODO 解消 (nullable
IWorldGateway 引数を constructor に追加、Phase C で Core から inject)。

`FakeWorldGateway` 拡張 + `SpawnCall` recording。**Paper impl は Wave 6b**
(`PaperWorldGateway` で `Player.gameMode = SPECTATOR` + `teleport()` 実行)。

### Konsist enforcement (+2 rules, baseline 30 → 32)

1. **`Hologram domain must not import Bukkit Display family (ADR-0074)`**:
   `core.domain.hologram.*` の全 import を scan、`org.bukkit.* / io.papermc.*
   / org.spigotmc.* / com.destroystokyo.paper.* / com.mojang.*` prefix を禁止。
   `org.joml.*` は **意図的に許容** (Wave 5h-fix、pure-math library)。
2. **`ModerationAction sealed must remain exhaustive (ADR-0074)`**: production
   code の `when` block で `is ModerationAction.<Variant> ->` arm を持つ
   ものに `else ->` 分岐があれば fail (Postgres deserialization 等の
   String-discriminator switch は除外、`is ModerationAction.X` pattern が
   無い ので fence は許容)。

## Consequences

### Positive

- **Wave 6a/b は pure-Paper-impl wave に純化**: 6 領域すべて domain commitment
  完了、Paper 側は Bukkit listener + entity 操作だけ書けばよい
- **Hologram の Bukkit-free domain**: `org.bukkit.*` を一切 import せず、
  rotation は `org.joml.Quaternionf` 直接使用 (Bukkit `Transformation`
  constructor が同 type を取るので edge での type 変換 glue 不要)。
  ADR-0002 の精神 (Minecraft が edge) は守りつつ、pure math library は
  domain で使ってよい (Wave 5h-fix)
- **Moderation の DB-backed audit trail**: V21 で 4 variant 全て persistence、
  Wave 6b で Paper enforcement 接続するだけで live testing 対応 OK
- **Tutorial の "first join" UX**: V20 + listener で player onboarding が
  domain side で完結、Wave 6b は Paper 側 spawn point + UI rendering のみ
- **Disconnect/Reconnect の robustness**: ACTIVE phase で disconnect →
  3min 以内に再 join で資源・loadout・stats すべて引き継ぎ可能、ACTIVE 中
  state 崩壊が無くなる
- **Spectator UX 確立**: `spectatorSpawn` の domain port が決まったので
  Wave 6b の `PaperWorldGateway` は teleport 1 行で済む

### Negative / Trade-offs

- **Hologram の richness が "そこそこ重い"**: 14 type 新規 (Hologram +
  HologramKind 3 variant + HologramTransform + Quaternion + Interpolation +
  Brightness + Color + VisibilityScope 3 variant + Handle + Error + 2 enum)。
  worker α が ~6h の重い workload。Phase A で skeleton 先 land で吸収。
- **Moderation Paper enforcement の Wave 6b 委譲**: Wave 5h 完了時点では
  mute/ban を apply しても実 enforce 不可。「DB に行が増えるだけ」を KDoc /
  ADR §4 で明文化、Wave 6b ハンドオフ doc で必ず引き当て。
- **`ResolveAnomalyEffectUseCase` 流儀の nullable constructor**: ε の
  use case が `worldGateway: IWorldGateway?` で受ける、null branch は
  no-op。production 側で必ず non-null なので runtime cost ゼロだが
  signature 上の複雑度が増える。
- **`BillboardMode` 移動の breaking import**: 既存 `combat.domain.projectile.BillboardMode`
  caller が `core.domain.hologram.BillboardMode` を import するよう全更新
  (Phase A 前提)。grep で 100% カバー確認。
- **新 listener の cross-server entry point**: Disconnect/Reconnect は
  game pod 内 event のみ追跡、Phase 4 で Velocity proxy 経由の cross-server
  traversal を追加で考慮必要。

### Mitigations

- Konsist rule 2 件で Hologram vendor-neutrality + ModerationAction
  exhaustiveness を mechanical 監視
- Phase A で 13 type の domain skeleton を先 land、worker α は impl + Fake +
  test 集中
- Wave 6b ハンドオフ doc で Paper enforcement の TODO 明示
- nullable constructor の null branch は KDoc + test で動作担保
- ADR-0073 (Wave _items-1) / ADR-0072 (Wave 5g) と同 wave の 6 領域として
  ADR review

## Alternatives Considered

- **Hologram に `applyVanilla` 系を生やして domain で BLINDNESS / GLOW 認識**:
  却下 (Vanilla concept が domain に漏れる、ADR-0030 §8 違反)
- **2 wave split (5h = UX-side / 5i = admin-side)**: 却下、orchestration コスト
  + ADR 2 件 + commit 2 件で overhead 大、user 確認で 1 wave 選択
- **Tutorial state を JSONB の 1 row でなく per-tutorial separate row**:
  却下、player 1 件 = state 1 row で query simple、JSONB Set で十分
- **Moderation Report 機能を Wave 5h に含む**: 却下、Phase 4 commitment と
  user 確認で決定 (Report は web admin UI の重要 surface、Wave 8 系で land)
- **Quaternion 独自実装** (`core.domain.hologram.Quaternion` value class):
  Wave 5h 初版で採用したが Wave 5h-fix で **撤回** (user feedback、2026-05-19):
  pure math reimplementation は cost、JOML 直接の方が trade-off 良い。
  ADR-0002 の "Bukkit-free domain" rule は pure-math library に拡張しない
  という principle として記録
- **Disconnect の reconnect window を config 化**: 却下、3 min は domain
  decision として hardcode、後続 wave で config 化したくなれば ADR 起草
- **DisplaySpec を Hologram に統合**: 却下、projectile transient lifetime
  vs hologram persistent lifetime で semantic 異なる、coexist が clean

## References

- 関連 ADR:
  - [[adr/0002-mc-as-edge]] — Bukkit 漏らさない原則
  - [[adr/0022-multi-plugin-layout]] — sibling-isolation matrix
  - [[adr/0027-phase-1-poc-closing-scope]] — forward-only 方針
  - [[adr/0030-combat-depth-and-damage-event-decomposition]] §8 — status effect surface rules (Vanilla 統一の前例)
  - [[adr/0068-cross-server-persistence-boundary]] — priority 100 Postgres overlay convention
  - [[adr/0072-pre-paper-sweep-port-commitments]] — Wave 5g sibling (ResolveAnomalyEffectUseCase nullable constructor 流儀の前例)
  - [[adr/0073-item-centric-content-model-and-skill-binding]] — Wave _items-1 sibling
- 関連 docs:
  - `docs/RTM-PARITY-TRACKER.md` — Cat 2/4/7/8/9 行 (Disconnect/Reconnect, GUI Confirmation/TextInput, Tutorial flow, Moderation, Hologram)
  - `docs/IMPLEMENTATION-ROADMAP.md` — Wave 5h entry (Tier 6.6)
- Code (Wave 5h で land):
  - `plugin/core/.../domain/hologram/` (14 file)
  - `plugin/core/.../application/port/outbound/IHologramGateway.kt` / `IModerationService.kt` / `IPlayerTutorialStateRepository.kt`
  - `plugin/core/.../domain/moderation/{ModerationAction.kt,BanScope.kt,ModerationActionId.kt}`
  - `plugin/core/.../domain/tutorial/PlayerTutorialState.kt`
  - `plugin/core/.../domain/gui/GuiSlot.kt` (Confirmation + TextInput)
  - `plugin/core/.../application/usecase/{tutorial,moderation,chat}/*`
  - `plugin/core/.../application/port/outbound/IWorldGateway.kt` (spectatorSpawn / lobbySpawn)
  - `plugin/match/.../application/eventbus/listeners/{Disconnect,Reconnect}DuringMatchListener.kt`
  - `plugin/match/.../application/usecase/match/{ResumeMatchUseCase,RemoveFromMatchUseCase,ApplyForfeitUseCase}.kt`
  - `plugin/persistence/src/main/resources/db/migration/{V20__player_tutorial_state,V21__moderation_actions}.sql`
  - `plugin/persistence/.../repository/{PostgresPlayerTutorialStateRepository,PostgresModerationService}.kt`
  - `tools/architecture-test/.../ArchitectureTest.kt` (Wave 5h +2 rules)
