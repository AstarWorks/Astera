---
status: Accepted
date: 2026-05-21
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0088: Match recording capture — event log + key frame, playback deferred to Phase 4

## Status

Accepted

## Context

Phase 4 ロードマップは **post-match playback** (web の clip viewer / Discord 共有 / tournament re-watch) を含むが、その前段である **capture pipeline** は本セッション (Wave 5v) で land する必要がある。理由:

- Wave 5o (Respawn + Death flow、別 worktree commit `9fda7bb`) と Wave 5c
  (Game modes + Match phase) が PvP の "観るべき瞬間" を生成しており、
  これらを記録するハーネスを Phase 1 PoC に間に合わせないと **PoC が録画なしで終わる**。
- 録画 format / scope を Phase 4 で握ると、UI 側設計が決まる前に
  schema が固定され、playback UI 側で renderable な情報が不足するという
  既知の失敗 mode に直結する (RTM の reference 実装でも同型の問題)。
- 一方で playback UI 自体は web フロント + 3D renderer + scrub bar が
  必要で、Wave 5v 内で完結させると schema design と UI 設計を並列で
  進めなければならず、両方が中途半端になる risk が高い。

User 確認 (2026-05-20):

- **capture only** とする (Wave 5v は capture 専用、playback impl は
  Phase 4 Wave 8 に deferred)。
- **format は軽量化**: 4 player × 1 hour で **~5MB 目標**。
  full per-tick state dump (per-frame video equivalent) は debug-only として
  残し、default は **event log + 定期 key frame** にする。
- Wave 5o の `PlayerRespawned` event を含む domain event を漏れなく
  subscribe できる構造とする (Wave 5o 自体は別 worktree でまだ main に
  merge されていないため、forward-only でその event 型は本 wave の依存
  にしない — 該当 ReplayEvent variant は schema に予約だけする)。

検討した alternatives:

1. **完全な per-tick state dump** (Quake demo / Minecraft Replay Mod 方式)
   — 完璧な playback fidelity だが、4 player × 1 hour で ~80MB 想定。
   5MB 目標から大きく外れ、Postgres JSONB row size と S3 アップロード
   コストの両方を悪化させる。
2. **Bukkit packet capture** (Replay Mod 互換) — Paper packet 全部を
   capture し binary dump にする。fidelity 最高だが、ADR-0030 §8 の
   Bukkit hijack 方針より下のレイヤーに依存することになり、Wave 5v が
   Paper adapter 層に踏み込まざるをえなくなる。Wave 5v は core +
   match.application で完結する設計を選ぶ。
3. **YouTube / Twitch screen capture** — 別 service / 別 OPS。Astera
   server side の概念から外れるので捨てた。

## Decision

Wave 5v で以下を一括 land (forward-only、Phase 1 PoC posture):

### 1. Domain types — `:plugin:core.domain.replay.*`

- `ReplayId` (value class), `RecordedReplay`, `KeyFrame`, `Vec3Snapshot`
- `ReplayFormat` sealed: `EventLogKeyFrame(keyFramePer=100, interpolateMovement=true)`
  と `FullPerTick(maxDurationTicks=24000)` の 2 変種
- `ReplayEvent` sealed (9 変種): `PlayerMoved`, `DamageDealt`,
  `PlayerKilled`, `PlayerRespawned`, `ItemUsed`, `SkillCast`, `ChatSent`,
  `MatchPhaseChanged`, `PillarCaptured`
- `DamageSourceKind` enum (combat `DamageSource` を 8 カテゴリにまとめた粗粒度版)
- `ReplayError` sealed: `NotRecording`, `EmptyRecording`, `Storage`, `CapHit`

### 2. Ports — `:plugin:match.application.port.*`

- `IMatchRecorder` (inbound): `startRecording / stopRecording /
  isRecording / activeRecordings`
- `IMatchReplayRepository` (outbound): `save / findByMatchId / findById /
  delete`
- `IRecorderStateProvider` (outbound): key-frame snapshot に必要な
  per-player state を返す。Phase 1 PoC は `NoopRecorderStateProvider`、
  Wave 6a で `PaperRecorderStateProvider` (Bukkit.getOnlinePlayers /
  player.location / player.health / mainHand) を overlay。

### 3. Domain events — `:plugin:core.domain.event.*`

- `MatchRecordingStarted(replayId, matchId, formatDescriptor, atTick)`
- `MatchRecordingStopped(replayId, matchId, startTick, endTick,
  eventCount, keyFrameCount, sizeBytes, atTick)`

### 4. Application service — `:plugin:match.application.service.replay.*`

- `ReplayCaptureEngine`: `IDomainEventBus.subscribe` で 5 種の domain
  event (`DamageDealt`, `PlayerKilled`, `SkillCast`, `ChatMessageSent`,
  `PhaseChanged`) を listen し `ReplayEvent` に projection する。
  `IScheduler.runRepeatingTicks(20, 20)` で 1 秒に 1 回 key-frame tick
  をチェックし、各 active recording の `keyFramePer` の倍数で
  `IRecorderStateProvider` から snapshot を取って `KeyFrame` を追加する。
  buffer cap 12 MB / match (4 player × 1h ≈ 5MB に余裕)。
- `InMemoryMatchReplayRepository`: priority 0 fallback (Postgres 不在時)。

### 5. Persistence — V20 migration + `:plugin:persistence.repository.*`

```sql
CREATE TABLE match_replays (
    match_id    VARCHAR(64) PRIMARY KEY,
    replay_id   UUID NOT NULL,
    start_tick  BIGINT NOT NULL,
    end_tick    BIGINT NOT NULL,
    size_bytes  INTEGER NOT NULL CHECK (size_bytes >= 0),
    format      JSONB NOT NULL,
    key_frames  JSONB NOT NULL,
    events      JSONB NOT NULL,
    saved_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX idx_match_replays_replay_id ON match_replays (replay_id);
CREATE INDEX idx_match_replays_saved_at ON match_replays (saved_at DESC);
```

- `PostgresMatchReplayRepository` (priority 100、`AsteraPersistencePlugin`
  で register)。JSONB DTOs は `:plugin:match.application.replay.json.*`
  に置く (sealed-by-key discriminator pattern、ADR-0014 流儀)。
- `MatchReplayJson` の DTOs は Postgres column 形と export `.replay.json`
  形を兼ねる (Phase 4 web UI は同 schema を読み込む)。

### 6. Command — `/astera-match replay`

- `start <matchId>`: opt-in 録画開始 (auto-record は Phase 4)
- `stop <matchId>`: 録画停止 + Postgres へ persist
- `export <matchId>`: 直近録画を `<dataFolder>/replays/<matchId>.replay.json`
  に書き出し
- permission: `astera.match.admin.replay.{start,stop,export}`

### 7. Konsist rules (2)

- `Wave 5v ReplayEvent variants must contain only primitive or value-class types`
  — JSONB schema を deterministic に保つ
- `Wave 5v IMatchRecorder must not import Bukkit Paper` — inbound port
  の vendor-neutrality 維持

## Consequences

### Positive

- **5MB budget realistic**: event log + key frame の組み合わせで、
  movement event (delta-encoded Float×3) + key frame (per 100 ticks =
  5 sec) という最小情報量。4 player × 1h で ~4.5MB の見積もりが立つ。
- **Phase 4 playback UI が schema 込みで起こせる**: `.replay.json` の
  wire shape (`ReplayExportEnvelope` + 9 `ReplayEventJson` 変種)
  が固定済み、web フロント側は JSON schema を直接 type-check できる。
- **Postgres と export file が同一 schema**: DB row decode と
  `.replay.json` decode が同じ DTO を使うので、Phase 4 で「DB から
  fetch → 直接 frontend に流す」/ 「`.replay.json` upload → frontend
  parse」の両 path が 1 parser で済む。
- **Wave 5o との結合は schema 予約のみ**: `ReplayEvent.PlayerRespawned`
  variant を schema には入れたが、本 wave では subscribe しない (5o
  worktree の event は main に未 merge)。5o merge 後に 1 行 subscribe
  を足すだけで動く。
- **Wave 6a の Paper adapter が小さい**: 必要なのは
  `PaperRecorderStateProvider` 1 個 + (希望) PlayerMove event listener
  だけ。Wave 6a の budget を圧迫しない。

### Negative / Trade-offs

- **Vanilla Replay Mod 互換ではない**: 専用 `.replay.json` 形式なので、
  既存の Replay Mod viewer は使えない。Phase 4 で web viewer を自前で
  書く必要がある。
- **Playback fidelity が制限される**: movement は per-tick ではなく
  delta-encoded で 100-tick key frame 間に interpolate。激しい
  knockback / teleport 後の数 tick は再現精度が落ちる。
- **`FullPerTick` format が ad-hoc**: forensic 用 escape hatch として
  残したが、Phase 4 web UI が実装するかどうかは未確定。size cap
  72000 ticks (60 min) で hard-stop なので暴走しないが、本気で 5×
  容量を消費する。
- **Auto-record off by default**: Phase 1 PoC で運用負荷を低減する
  ため opt-in にした (admin が `/astera-match replay start` を打たない
  限り capture されない)。Phase 4 で privacy / EU storage 議論と合わせて
  再設計する余地。

### Mitigations

- 12 MB hard cap (`MAX_BUFFER_BYTES`) — `FullPerTick` 暴走時も
  Postgres JSONB row が肥大化しすぎる前に capture が止まる。
- Schema 変更は Phase 1 PoC posture (forward-only) で躊躇しない:
  `MatchRecordingStarted.version` を bump し、playback UI 側に
  schema version 表示を持たせる前提で進める (dual-decoder は書かない)。
- `event-log-keyframe` の `interpolateMovement = false` mode は
  size を更に 1/3 程度に圧縮できる escape hatch (key frame 間に
  movement event を一切出さない)、選択肢として残した。

## Alternatives Considered

- **Full per-tick state dump (Quake / Replay Mod 方式)**: fidelity は
  完璧だが 4×1h で 80MB → 5MB 目標から外れる。`FullPerTick` format は
  forensic / debug 用に残し、default にはしない。
- **Bukkit packet capture (Replay Mod 互換)**: Paper packet を binary
  dump するアプローチ。fidelity 最高だが ADR-0030 §8 の Bukkit hijack
  方針より下のレイヤーに踏み込む必要があり、Wave 5v が
  `:plugin:match.application` で完結する設計を捨てることになる。
- **Auto-record everything**: privacy / EU GDPR storage 論点と
  intersect する。Phase 1 PoC では opt-in を選び、Phase 4 で再設計。
- **`event_seq` 別テーブル**: 1 event = 1 row の Postgres 設計。
  ReplayEvent count は数 10k order、JSONB blob 1 row は 10MB 未満で
  扱えるため、別テーブルを起こす runtime / index コストの方が高い
  (RecordedReplay は read-whole aggregate、ADR-0014)。

## References

- `idea: tournament + clip` plan (`/IdeaProjects/Astera/.claude/plans/idea-tournament-and-clip.md`)
  — Phase 4 Wave 8 playback UI commitment
- Plan v5 §"Wave 5v" (`wave-5g-core-hardening-giggly-sunset.md`) —
  本セッションのスコープ source-of-truth
- [ADR-0014](0014-jsonb-column-policy.md) — JSONB column 採用基準
- [ADR-0020](0020-persistence-architecture.md) — Persistence 全体方針
- [ADR-0022](0022-multi-plugin-architecture.md) — sibling-isolation matrix
  (Match → Core; Persistence → all features)
- [ADR-0030](0030-armor-bukkit-hijack-policy.md) §8 — Bukkit event hijack 方針
- Wave 5o handoff (`wave-5o`) — `PlayerRespawned` event 提供元
