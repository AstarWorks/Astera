---
status: Accepted
date: 2026-05-27
accepted_date: 2026-05-27
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0110: Camera + Music gateway foundation (cinematic primitive)

## Status

Accepted (Wave 5cinematic landed 2026-05-27)

## Context

Wave 5h spectator camera は fixed-spawn 視点しか提供しておらず、tutorial 導入
/ boss reveal cutscene / Tournament highlight reel / 適応的 BGM などの
Phase 1 — Phase 4 想定シナリオが **共通で必要とする「camera を packet-
レベルで操作する port」と「long-form 音楽 を session 単位で扱う port」
が完全欠落** していた。

2026-05-27 audit (`.claude/plans/wave-5g-core-hardening-giggly-sunset.md`
§Round 5) で次の gap が定量化された:

- `ICameraGateway` (lock / pan / shake / zoom / freeze / release) 無し
- `ICinematicPathPlayer` (path-based camera move、Tournament 用 foundation) 無し
- `IFreelookGateway` (movement frozen + look free、tutorial 用) 無し
- `IScreenEffectGateway` (fade / freeze、cutscene primitive) 無し
- `IMusicGateway` (long-form audio + cross-fade + ducking) 無し
- `IMusicTrackRegistry` (`content/music/` YAML、license metadata 付) 無し
- `IPerMatchBgmService` / `IAdaptiveBgmService` (HP / phase trigger linkage) 無し

これらは Wave 5pve-boss boss reveal cutscene、Wave 5p tutorial intro
cinematic、Wave 5j HUD music sync、Wave 5annihilation phase
transition BGM 切替 などの先行 wave が **暗黙に存在する前提で設計されていた**
が、port が未提供のため adapter / Wave 6 Paper sweep を **port 接続無しで
ad-hoc に書く** リスクがあった。

**設計選択の制約**:
- Bukkit camera packet / `Player.playSound` / overlay packet は domain 直接
  import 禁止 (Konsist `core domain stays pure`).
- Wave 5h `Easing` (32 標準 curve) を流用する。新 easing curve は作らない。
- Phase 1 PoC closing posture (`.claude/CLAUDE.md` §Pre-release posture) を
  最大限活用 — fallback shim / dual-decoder / `@Deprecated` は残さない。
- Music license tier は Phase 4 UGC を視野に入れて sealed type 化 (Royalty-
  free / Licensed / User-uploaded) — Phase 1 では Royalty-free のみ実利用。
- DB migration なし (session-scoped、metadata は YAML)。main 最大 V43 →
  本 wave V44+ を予約しない。
- Sub-agent harness 内では `rtm-sync` / `astera-reviewer` subagent / Agent
  tool が呼べないので Phase B は orchestrator solo sequential、Phase C は
  手動 RTM update + 手動 review。

リサーチ根拠: Hypixel cinematic system (boss reveal pan / sudden-death
shake)、League of Legends adaptive music switching (low-HP → high-intensity
stem)。

## Decision

Wave 5cinematic で **Core 単体に foundation port + in-memory impl + YAML
music registry までを land** する。Paper adapter (camera packet / playSound /
overlay) は Wave 6 で別建て。

### Domain (`core.domain.{cinematic,music}.*`、計 13 file)

`core.domain.cinematic.*` (8 file):
- `Color(r, g, b, a)` — RGBA value、`BLACK/WHITE/TRANSPARENT` companion。
  cinematic-local。Bukkit `Color` 禁止。
- `CameraTarget` sealed: `Position(Vec3) / Entity(EntityHandle) /
  Player(PlayerId) / RelativeToPlayer(Vec3 offset)`。
- `CameraHandle` value class (`String` id、blank reject)。
- `CinematicHandle` value class (同上)。
- `ScreenEffectHandle` value class (同上)。
- `CameraWaypoint(target, durationTicksToNext: Long, easing: Easing)` —
  Wave 5h `Easing` 流用、negative duration reject。
- `CinematicPath(waypoints: List<CameraWaypoint>)` — non-empty enforce、
  `totalDurationTicks` は last waypoint の `durationTicksToNext` を ignore
  した sum。
- `FreelookSession(playerId, anchorPosition: Vec3)` — pure value、lifecycle
  は gateway 側。

`core.domain.music.*` (6 file):
- `MusicTrackId` value class — `[a-z0-9]+(-[a-z0-9]+)*` lowercase-kebab-case
  (`MatchId` 同じ kebab discipline)。
- `MusicTrack(id, displayName, source, license, mood, defaultVolume, loop,
  durationTicks?)` — `defaultVolume ∈ 0.0..1.0` / non-negative duration
  enforce。
- `MusicSource` sealed: `ResourcePack(path) / ExternalUrl(url) / InGameNote`。
- `MusicLicense` sealed: `RoyaltyFree(attribution?) / Licensed(licenseRef) /
  UserUploaded(uploaderId)` — Phase 4 UGC 視野。
- `MusicMood` enum: `LOBBY / MATCH / BOSS_REVEAL / HIGH_INTENSITY / VICTORY /
  DEFEAT / CINEMATIC`。
- `MusicHandle` value class。

### Cross-plugin events (`core.domain.event.*`、5 new)

- `CinematicStarted(playerId, handle, totalDurationTicks, atTick)`。
- `CinematicEnded(playerId, handle, reasonKind: String, atTick)` —
  `reasonKind ∈ {"completed", "cancelled", "failed"}` (snake_case
  discriminator、`IslandUnloaded.reasonKind` と同 pattern)。
- `CameraReleased(playerId, handle, atTick)` — auto-release / explicit
  release 両方で fire。
- `MatchMusicChanged(matchId, previousTrack?, newTrack, atTick)` —
  previousTrack == newTrack reject (`AnnihilationPhaseTransitioned` と同)。
- `AdaptiveMusicTriggered(matchId, newMood, selectedTrack, reasonKind, atTick)`
  — `reasonKind ∈ {"hp_threshold", "phase_transition", "player_count",
  "manual"}`。

### Outbound ports (`core.application.port.outbound.{cinematic,music}.*`、8 new)

`cinematic/`:
- `ICameraGateway`: `lockCamera / panCamera / shakeCamera / zoomCamera /
  releaseCamera / freeze / activeHandle`。`CameraError` sealed
  (`UnknownPlayer / InvalidIntensity / InvalidZoom / InvalidDuration /
  Platform`)。
- `ICinematicPathPlayer`: `play / cancel / active`。`CinematicPlaybackError`
  sealed。
- `IFreelookGateway`: `enableFreelook / disableFreelook / session / all`。
  `FreelookError` sealed。
- `IScreenEffectGateway`: `fade / freeze / cancel / active`。`ScreenEffectError`
  sealed。

`music/`:
- `IMusicGateway`: `play / crossfade / duck / unduck / stop / active`。
  `MusicError` sealed (`UnknownPlayer / UnknownHandle / UnknownTrack /
  InvalidVolume / InvalidDuration / Platform`)。
- `IMusicTrackRegistry : Contents<MusicTrackId, MusicTrack>` — `byMood(mood)`
  追加。
- `IPerMatchBgmService`: `setTrack / stop / current`。`PerMatchBgmError`
  sealed。
- `IAdaptiveBgmService`: `trigger(matchId, newMood, reasonKind)` —
  `IMusicTrackRegistry.byMood` で track 選択 → `IPerMatchBgmService` に forward。
  `AdaptiveBgmError` sealed。

すべて `Result<T, sealed Error>` — Konsist が application 層の raw throwable
禁止を強制。

### In-memory services (`core.application.service.{cinematic,music}.*`、7 file)

- `InMemoryCameraGateway(eventBus, tickClock)` — active handle per player、
  `releaseCamera` で `CameraReleased` 発火。`freeze` は in-memory では
  auto-release scheduler を持たない (lifetime 安全のため、Paper adapter で
  `IScheduler.runLater` 注入)。
- `InMemoryCinematicPathPlayer(eventBus, tickClock)` — lifecycle のみ、
  waypoint advancement 駆動は Paper adapter (Wave 6) 側。`play` で
  `CinematicStarted` 発火、`cancel` で `CinematicEnded(reasonKind="cancelled")`。
- `InMemoryFreelookGateway` — session per player、replace は silent。
- `InMemoryScreenEffectGateway` — fade / freeze の handle per player set
  記録。cancel idempotent。
- `InMemoryMusicGateway(tracks)` — registry 経由で `UnknownTrack` 早期検出、
  per-handle `(playerId, trackId, ducked, stopped)` state。
- `InMemoryPerMatchBgmService(tracks, eventBus, tickClock)` — `setTrack`
  同一 no-op、`MatchMusicChanged` 発火。
- `InMemoryAdaptiveBgmService(tracks, perMatch, eventBus, tickClock)` —
  `byMood` の first track 選択 (deterministic test-friendly)、reasonKind
  validate、空候補は silent skip。

### Content + YAML config

- `MusicTrackContents : YamlDynamicContents<MusicTrackId, MusicTrack,
  MusicTrackYamlConfig>` (`/astera reload music` 経由 hot-swap、
  `IReloadableContentsRegistry` 登録)。
- `MusicTrackYamlConfig` kaml DTO (`application.config.music.*` — Konsist が
  domain で `kotlinx.serialization` 禁止) で wire 形式
  `{id, display_name, source{kind, path|url}, license{kind, attribution?,
  license_ref?, uploader_id?}, mood, default_volume, loop, duration_ticks}`。
- Sample content: `content/music/{lobby-loop, match-default,
  boss-reveal-strings, high-intensity-drums}.yaml` (4 tracks、全
  `royalty_free` placeholder)。

### Konsist +1 rule (45→46)

`cinematic and music domain stays pure (ADR-0110)` — `core.domain.cinematic.*`
+ `core.domain.music.*` が Bukkit / kotlinx.serialization / exposed /
postgres / sql / lettuce / koin / **JOML** を import するのを禁止。JOML 禁止
は cinematic が Wave 5h `Easing` の pure Kotlin double で十分なため、
domain 表面を最小に保つ意図的判断。

### Plugin wiring

`AsteraCorePlugin.onEnable` で 8 ports を priority=0 register:
- 4 cinematic gateways + `IMusicGateway` + `IMusicTrackRegistry` (YAML
  bootstrap) + `IPerMatchBgmService` + `IAdaptiveBgmService`。
- `reloadRegistry.register(musicContents, "content/music/")` で hot-reload
  に接続。
- `SERVICE_NAMES` companion list に 8 entry 追加。

### Tests (13 test classes、66 tests)

- Domain: `CinematicPathTest` (6) / `ColorTest` (5) / `CameraHandleTest` (3) /
  `MusicTrackTest` (5) / `MusicTrackIdTest` (6) = **25**。
- Service: `InMemoryCameraGatewayTest` (9) /
  `InMemoryCinematicPathPlayerTest` (4) / `InMemoryFreelookGatewayTest` (4) /
  `InMemoryScreenEffectGatewayTest` (5) / `InMemoryMusicGatewayTest` (8) /
  `InMemoryPerMatchBgmServiceTest` (5) / `InMemoryAdaptiveBgmServiceTest` (4) /
  `MusicTrackContentsTest` (2) = **41**。

すべて Bukkit harness 不要 (純 JVM)。`./gradlew check` green、Konsist 46
rules pass、7 plugin shadowJars 全 green。

## Consequences

### Positive

- **Wave 5pve-boss boss reveal cutscene / Wave 5p tutorial / Wave 5j HUD
  music sync** が port 接続可能になり、Wave 6 Paper sweep で「port は既に
  ある」状態。
- **Wave 5annihilation phase transition BGM** が `IAdaptiveBgmService.trigger(
  matchId, HIGH_INTENSITY, "phase_transition")` で 1 行 hook 可能 — feature
  plugin から Core 経由で BGM 制御。
- **Phase 4 Tournament highlight reel foundation** が
  `CinematicPath + ICinematicPathPlayer` で確立 — Phase 4 で YAML loader
  / `CinematicPathId` / 録画 / 再生 framework を上乗せ可。
- **Music license tier** が Phase 4 UGC 受け入れに対して **構造的に準備済**
  (`MusicLicense.UserUploaded(uploaderId)` で takedown 対象を記録)。
- **Wave 5h `Easing` 流用** で `panCamera` / `CameraWaypoint` の curve 選択
  が 32 種類 — 新しい easing math を増やさず carry over。
- **Konsist 46 rules** で cinematic / music の Bukkit-free / JOML-free が
  CI で永続的に enforce。

### Negative / Trade-offs

- In-memory `InMemoryCameraGateway.freeze` / `InMemoryScreenEffectGateway.{fade,
  freeze}` は **auto-release scheduler を持たない** — Paper adapter (Wave 6)
  で `IScheduler.runLater(durationTicks) { release(...) }` を注入する必要
  あり。in-memory impl が scheduler を抱えると「gateway 差し替え時 task
  リーク」になるため意図的に avoid。
- `InMemoryAdaptiveBgmService` の track 選択は `byMood(...).first()` の
  deterministic な naive 選択。weighted random / round-robin / per-team
  preference は Phase 4 follow-up。
- `MusicSource.ExternalUrl / InGameNote` は Phase 1 で **実 playback 経路
  なし** — metadata 記録のみ。Wave 6 / Phase 4 で adapter 実装。
- `IMusicGateway` は per-player playback だが、`IPerMatchBgmService` の
  per-match 状態と **bridge する subscriber** は本 wave に含まない —
  Wave 5gamemode `IGameMode` lifecycle hook で `MatchMusicChanged` →
  全 participant への `IMusicGateway.play` fan-out を後続 wave で接続。

### Mitigations

- Paper adapter (Wave 6) で auto-release / fan-out subscriber を **port
  semantics の変更なしで** 追加できる設計 (priority=100 で in-memory を
  shadow)。
- Phase 4 で weighted selector が必要になったら `IAdaptiveBgmService` impl
  を差し替えるだけで済む (port 不変、event shape 不変)。
- `MusicSource.ExternalUrl` の playback は Wave 6 で **stream URL を resource
  pack-side proxy** で扱う想定 (Paper `Player.playSound` の category 経由)。

## Alternatives Considered

- **Alt A — Bukkit Camera packet を adapter API に直接露出**: Konsist
  `core.adapter.api.* must stay vendor-neutral` 違反。却下。
- **Alt B — `IBroadcaster.playSound` 一発で済ませる**: long-form music の
  cross-fade / ducking / handle-based stop が表現できない。短 SFX 専用
  port と long-form music port を分離する方が清潔。
- **Alt C — Cinematic を sealed enum で 5-6 種類用意 (intro / outro /
  boss reveal / sudden death)**: 高凝集に見えるが extension cost が高い
  (新 cinematic 種類 = enum 追加 + dispatch 更新)。Path-based 1-primitive
  + 上位 builder (Phase 4 用) の方が flexible。
- **Alt D — Music を `IPerPlayerSettingsRepository.get(playerId)` 経由で
  per-player BGM 上書き**: per-viewer は Wave 5y-ext (Round 5) の領分。
  本 wave は match-level + adaptive までで止め、per-viewer は Wave 5y-ext
  に明示的 hand off。
- **Alt E — Easing を cinematic 用に新規定義**: Wave 5h `Easing` enum (32
  curve) が animation / display / cinematic 共通使用に十分。重複定義
  すると "どっちの Easing を使うのか" が API surface に表れて confusing。

## References

- 関連 ADR:
  - [[adr/0079-extract-studio-to-own-repo]] (Wave 5h hologram / Display
    entity foundation)
  - [[adr/0090-per-viewer-state-visibility]] (Wave 5y、per-viewer BGM の
    将来 hookup 先)
  - [[adr/0098-pve-boss-foundation]] (Wave 5pve-boss、boss reveal で
    `ICinematicPathPlayer` 利用想定)
  - [[adr/0104-engagement-dashboard]] (Wave 5engage、adaptive BGM 効果
    測定の analytics 接続)
  - [[adr/0106-game-mode-framework-foundation]] (Wave 5gamemode、`IGameMode`
    lifecycle 連動)
  - [[adr/0107-pve-survival-party-island-foundation]] (Wave 5pve-survival、
    `IslandUnloaded.reasonKind` snake_case discriminator pattern を継承)
  - [[adr/0108-annihilation-mode-foundation]] (Wave 5annihilation、phase
    transition で `IAdaptiveBgmService` 利用想定)
- 関連 docs:
  - [[architecture/principles]]
  - [[RTM-PARITY-TRACKER]] (Camera + Music gateway foundation row)
  - `.claude/plans/wave-5g-core-hardening-giggly-sunset.md` §Round 5
