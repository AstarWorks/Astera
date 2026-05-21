---
status: Accepted
date: 2026-05-20
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0089: Server lifecycle + restic backup + Lobby HUD + IMatchmaker foundation

## Status

Accepted

## Context

Wave 5w は Phase 1 PoC closing の cleanup wave (`docs/IMPLEMENTATION-ROADMAP.md`
§Wave 5w)。元来は単独の「Server bootstrap / Lifecycle / Graceful shutdown」
スコープだったが、Plan v5 拡張で以下 4 件を一括 land する判断:

1. **Server lifecycle / Bootstrap / Graceful shutdown** — 起動順 + 終了順
   が暗黙的なまま Wave 6a に突入すると、Paper 起動時の race condition (in-flight
   match の save 漏れ、reward outbox flush 漏れ、DB close 順) が Paper-side
   コードに hard-code される。Phase 1 で `IServerLifecycleListener` port を
   commit し、終了順を domain 表現として固定する。

2. **restic backup automation** (`.claude/plans/idea-anti-grief-policy.md` §b) —
   anti-grief の事後修復軸として「24h hot retention + daily snapshot」を
   adminいiha 提供する。Wave 5n の region permission (事前防御) と直交。
   実装は **restic** (open-source、incremental + dedup + encryption +
   S3-compatible) を subprocess 経由で呼ぶ adapter。

3. **Match queue / Lobby waiting room HUD (Hypixel 型)** — Wave 5b の HUD
   port (`IBossBar` / `IScoreboard` / `ITabList`) を使った lobby 表示
   service。queue position / ETA / mode / cancel hint を action bar +
   scoreboard で表示。Wave 5j 既存 HUD primitive 流用、新規 sealed type は
   作らない。

4. **IMatchmaker foundation (Plan v5 で発見、Phase 1 PoC blocker)** — `/astera
   queue join` から `/astera-match start` までの間に「player をどう
   集めて team 分割するか」の port が抜けていた。Wave 5c (Match) で Match
   instance 作成 + spawn は実装済だが、**Match の前段 = queueing** が空白。
   Phase 1 PoC でも minimum FIFO + party-aware grouping が必要 (Hypixel
   型 lobby と一貫)。Phase 2 で Elo / Glicko2 拡張 (`idea-phase2-roadmap.md`
   軸 A 連動)。

### Wave 5t player-scoped backup vs Wave 5w server-scoped restic の棲み分け

Wave 5t (Admin/Ops、別 worktree) は **`IServerBackupGateway` (player-scoped)**
を予定しているが、これは "**個別 player の JSON export / import**" (GDPR-style
data portability + admin "give me this player's loadout snapshot" UX) を
担当する。

Wave 5w の `IServerBackupGateway` は **server-scoped restic** = "**Postgres
全テーブル + match world archive 全体を 1 snapshot**" を担当する。

両者は port 名が衝突しないよう Wave 5w 側は **`IServerBackupGateway`**
(server-wide) で確定、Wave 5t 側は port 名を **`IPlayerDataExportGateway`**
等に rename する (Wave 5t handoff 側で対応)。Wave 5t がまだ landしていない
ため、Wave 5w 側で先に `IServerBackupGateway` を確保する。

### IMatchmaker simple FIFO Phase 1 PoC + Phase 2 Elo 拡張 commitment

Phase 1 PoC では:

- FIFO ordered queue (`PriorityQueue<QueueEntry>` by `enqueuedAt`)
- Party-aware grouping (`partyId` が同じ entry は同 team)
- Mode-segregated queue (Norma / Custom / Tournament で別 queue)
- `tryFormMatch(mode)` は **冪等** (同じ formation を 2 度呼んでも 1 度しか
  match を返さない)

Phase 2 で:

- Elo / Glicko2 rating field を `QueueEntry` に追加 (additive、ADR-0089 は
  refresh 不要)
- `tryFormMatch` に skill-balance heuristic (median Elo ± σ で団体分割)
- `IMatchmaker.estimatedWaitTime(playerId)` の精度向上 (現状は queue depth
  / per-mode 平均 form rate からの粗推定)

### Lobby HUD ↔ IMatchmaker のリアルタイム連動

`ILobbyHudCoordinator.onEnter(playerId)` で:

1. `IMatchmaker.queueStatus(playerId)` を 1s おきに poll
2. `QueueEntry.estimatedWaitSec` + `enqueuedAt` から ETA countdown 計算
3. action bar (queue position) + scoreboard (mode / ETA / cancel hint) を更新
4. `MatchFormed` event subscribe → 該当 player に "Match found! Teleporting..."
   action bar、HUD clear

`MatchQueueJoined` / `MatchQueueLeft` event 受信時は HUD 再構築 (mode 切替時の
表示更新)。

## Decision

Wave 5w で以下を一括 land (forward-only、後方互換取らず — Phase 1 PoC closing
posture per CLAUDE.md):

### α. Server lifecycle (Core plugin)

- `IServerLifecycleListener` port (`core.application.port.outbound.lifecycle`)
  — `onBoot()` / `onReady()` / `onShutdown(reason)` / `onRestart()` hook
  4 種。Listener は ordered (priority field) で `IServerBootstrapper` が
  call。
- `IServerBootstrapper` port — plugin order resolution (Astera-Core → 5
  feature plugins → Astera-Persistence) + dependency check + graceful
  shutdown orchestration。
- Graceful shutdown order (domain-encoded as `ShutdownPhase` sealed):
  `StopAcceptingPlayers` → `SaveInFlightMatches` → `FlushRewardOutbox` →
  `CloseDatabase` → `DisablePlugins`
- `InMemoryServerBootstrapper` impl + 4 lifecycle event variants
  (`ServerBooted` / `ServerReady` / `ServerShutdownStarted` /
  `ServerShutdownCompleted`)

### β. restic backup gateway (Core plugin)

- `IServerBackupGateway` port (`core.application.port.outbound.backup`):
  ```kotlin
  public interface IServerBackupGateway {
      public fun runFullBackup(target: BackupTarget): Result<BackupSnapshotRef, BackupError>
      public fun listSnapshots(target: BackupTarget): Collection<BackupSnapshotRef>
      public fun restoreFromSnapshot(snapshotRef: BackupSnapshotRef, options: RestoreOptions): Result<RestoreReport, BackupError>
  }
  ```
- `BackupTarget` sealed: `Postgres` / `MatchWorldsArchive` /
  `CustomDimensionWorld(WorldId)` / `All(Set<BackupTarget>)`。Phase 1
  PoC では `IslandWorld(IslandId)` variant は記載のみ — `IslandId` 型は
  Phase 3 (生活の島) 着手時に追加。
- `BackupRetentionPolicy` (domain) — `hourly: Int`, `daily: Int`, `weekly: Int`
  (default `24, 7, 4`)。`content/balance/backup-policy.yaml` で YAML 流入
  (Wave _infra-2b ADR-0065 の per-category reload dispatcher 参加)。
- `ResticBackupGateway` impl は **Wave 6b** で land (Paper plugin process
  から subprocess 起動 + S3 credential 読込が必要、Wave 5w では skeleton
  + `InMemoryServerBackupGateway` Fake impl のみ)。Wave 6b 段階で ADR-0072
  と同等の allow-list 流儀で commit。
- `/astera backup run [target]` / `/astera backup list [target]` /
  `/astera backup restore <snapshot-id> [target]` Brigadier command tree
  (Core platform 側、permission `astera.server.backup.{run,list,restore}`)。
- Hourly Postgres incremental + daily full backup の scheduler 配線
  (`IScheduler.runRepeating`) は Phase C wiring で Match Plugin **ではなく**
  Core Plugin 側で行う (server-wide concern)。

### γ. Lobby waiting room HUD (Match plugin)

- `ILobbyHudCoordinator` port (`match.application.port.outbound`) —
  `onEnter(playerId)` / `onLeave(playerId)` / `refresh(playerId)` の 3 メソッド。
- `LobbyWaitingRoomHud` service (`match.application.service.hud`) — impl が
  Wave 5b の `IBossBarManager` / `IScoreboardManager` / `IPlayerGateway`
  (sendActionBar) を使って queue position / ETA / mode を表示。**Bukkit は
  使わない** (Konsist enforcement)。
- `content/hud/lobby-waiting.json` — display label key + refresh tick rate
  config (Phase 1 PoC 用 minimal、後の Wave 5j 拡張 / Wave 6a で font
  registry 連携)。
- `MatchQueueJoined` / `MatchQueueLeft` / `MatchFormed` event subscribe で
  HUD 再構築。

### δ. IMatchmaker foundation (Match plugin)

- `match.domain.matchmaking` package 新設:
  - `MatchmakingMode` sealed: `Norma` / `Custom(adminConfigured: GameModeId)` /
    `Tournament(tournamentMatchId: String)`
  - `QueueEntry(playerId, mode, enqueuedAt, estimatedWaitSec, partyId)` data class
  - `MatchFormation(players, teams, mode)` data class
  - `QueueError` sealed: `AlreadyEnqueued` / `NotEnqueued` / `ModeClosed` /
    `PartyTooLargeForMode`
- `IMatchmaker` port (`match.application.port.outbound.matchmaking`):
  ```kotlin
  public interface IMatchmaker {
      public fun enqueue(playerId: PlayerId, mode: MatchmakingMode, partyId: PartyId? = null): Result<QueueEntry, QueueError>
      public fun dequeue(playerId: PlayerId): Result<Unit, QueueError>
      public fun queueStatus(playerId: PlayerId): QueueEntry?
      public fun pendingByMode(mode: MatchmakingMode): Collection<QueueEntry>
      public fun tryFormMatch(mode: MatchmakingMode): Result<MatchFormation?, QueueError>
  }
  ```
- `InMemoryMatchmaker` impl — FIFO + party-aware (party member は同 team)、
  `tryFormMatch` は冪等 (consume 後 entry 削除)。
- `MatchFoundUseCase` — Wave 5c `StartMatchUseCase` の前段。`tryFormMatch`
  → `MatchFormation` 取得 → `MatchStateMachine` で `StartMatchUseCase`
  invoke → 該当 player を `MatchSpawnUseCase` 経由 teleport (Wave 5c 既存
  flow に hand-off)。
- 4 domain event: `MatchQueueJoined` / `MatchQueueLeft` / `MatchFormed` +
  `ServerShutdownStarted` (α 由来) は `core.domain.event` 配下に置く
  (Match 側で subscribe するため Core が SSOT)。
- `/astera queue join <mode>` / `/astera queue leave` / `/astera queue status`
  Brigadier command tree (Match platform 側)。

## Consequences

### Positive

- **Wave 6a に持ち越す race condition risk が大幅縮減**。Graceful shutdown
  の順序 (5 phase) が domain で型として fix されたため、Paper adapter は
  単に `IServerLifecycleListener.onShutdown(...)` を respect すれば良い。
- **restic 24h hot retention により事後修復軸が確立**。Wave 5n region
  permission (事前防御) と組み合わせて anti-grief 2 軸 (idea-anti-grief
  -policy.md) が Phase 1 PoC 内で完備。
- **lobby UX が "queue → match" として体感できる**。Hypixel 型の "queue
  に入ってる感" を Phase 1 demo で見せられる (ETA countdown + mode 表示
  + cancel hint)。
- **IMatchmaker port が Wave 6a で塞がる前に commit**。Phase 2 Elo 拡張時
  は `QueueEntry` に rating field 追加 (additive) で済む。Wave 5w で port
  shape を固めなければ Phase 2 で Paper 側 wiring 込みの大改修が必要。

### Negative / Trade-offs

- **restic CLI subprocess 依存**。Paper plugin process が restic binary
  を `$PATH` で見つけられる必要 (Wave 6b で `ResticBackupGateway` impl が
  必須 dep 化、container image に restic を含める)。Wave 5w 段階では fake
  impl のみで `./gradlew check` は通る。
- **IMatchmaker は in-memory only**。サーバ再起動で queue は消滅 — Phase
  1 PoC では許容 (lobby is single-process)。Phase 2 multi-server 化時は
  Redis-backed impl を Wave _persist-2 などで追加。
- **HUD は polling-based (1s tick)**。push-based push にすべきという議論
  はあるが、Wave 5b の `IScoreboard.update` semantics と整合させる Phase
  1 では polling で十分。Phase 2 で `IScoreboardManager.subscribe(player,
  binding)` reactive 化を検討。
- **ServerShutdownStarted event は `core.domain.event` 配下**。Match
  Plugin が Core の event を subscribe する設計は ADR-0022 sibling
  isolation matrix で許容済 (Core → 全 feature plugin が subscribe する
  pattern は MatchStarted / MatchEnded で前例あり)。

### Mitigations

- restic binary 不在の dev 環境では `InMemoryServerBackupGateway` が priority
  0 で active。runtime に "restic not found" warning を一度だけ出す (Wave
  6b で warning → fatal escalation 検討)。
- `IMatchmaker.tryFormMatch` の冪等性は **Konsist rule で enforce**:
  impl class は `tryFormMatch` 後 consume した entry を即座に dequeue する
  か、または formation 結果を `WeakReference` で抱え込んで二重 consume
  しない構造を要求 (rule のテキスト = "MatchFormation を二度同じ entry set
  から作る path が無いこと")。実装テストでも 2 回連続 call で 2 回目が
  `Success(null)` を返すことを assert。
- HUD polling rate は `content/hud/lobby-waiting.json` で tunable (default
  20 ticks = 1s)。

## Alternatives Considered

- **Alt A: Lifecycle を Wave 6a Paper Sweep に統合**: Paper 起動順 + graceful
  shutdown は inherently Paper-side 概念だから Wave 6a で良い、という案。
  **却下**: domain にも graceful shutdown 順 (5 phase) と "in-flight match
  save" は概念として存在し、Paper adapter は単にそれを実行するだけ。Wave
  6a に持ち込むと 6a が 3 軸 (lifecycle + combat sweep + 既存 6a scope)
  になり handoff 不能になる。
- **Alt B: restic 採用ではなく Postgres `pg_dump` + custom world snapshot
  自前実装**: 全部自分で書く案。**却下**: restic は incremental + dedup +
  encryption + S3 を 1 binary で提供。自前実装は Phase 2 で「結局 restic
  rewrap」になる可能性が極めて高い。Phase 1 PoC では subprocess 採用が
  最短。
- **Alt C: IMatchmaker を Wave 7b Tournament + Matchmaking まで遅らせる**:
  Wave 7b が ADR-0037 で predominantly matchmaking 担当だから、Phase 2 で
  良い、という案。**却下**: Phase 1 PoC でも "queue → match" 体感が無いと
  demo として lobby が空虚。Phase 1 で simple FIFO だけでも入れる必要
  あり、Phase 2 (Wave 7b) で Elo 拡張という 2-stage が roadmap として
  自然 (ADR-0089 で commit、Wave 7b は ADR-0037 で Elo 拡張)。
- **Alt D: Lobby HUD を Wave 5b HUD port land 時 (済) に inline**: Wave
  5b で HUD primitive を land した時点で lobby HUD example を入れる案。
  **却下**: Wave 5b 時点では IMatchmaker が無かったため、queue position
  / ETA を表示するための data source が存在しなかった。今 Wave 5w で
  IMatchmaker と同時に land する方が自然。

## References

- 関連 plan: `.claude/plans/wave-5g-core-hardening-giggly-sunset.md` §Wave 5w
- 関連 idea: `.claude/plans/idea-anti-grief-policy.md` §(b) restic backup
- 関連 idea: `.claude/plans/idea-phase2-roadmap.md` 軸 A (Elo / Glicko2 拡張)
- 関連 ADR: [[adr/0022]] 7 plugin layout / sibling isolation
- 関連 ADR: [[adr/0031]] HUD ports (BossBar / Scoreboard / TabList)
- 関連 ADR: [[adr/0032]] Game mode framework (MatchmakingMode と直交)
- 関連 ADR: [[adr/0065]] per-category reload dispatcher (backup-policy.yaml)
- 関連 ADR: [[adr/0072]] pre-Paper-sweep port commitments (Wave 5g 流儀踏襲)
- 関連 RTM: Category 2 Match queue + lobby waiting room (Plan v5 補完)
