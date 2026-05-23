---
status: Accepted
date: 2026-05-21
accepted_date: 2026-05-24
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0103: 1v1 Betting Match (RTM `コンテンツ案一覧.md` 由来)

## Status

Accepted (Wave 5bet landed 2026-05-24)

## Context

RTM Writerside `コンテンツ案一覧.md` に "賭け1v1" が post-launch アイデアと
して記録されており、ADR-0029 §"Newly-discovered Phase 4+ backlog items" で
**Phase 4+ backlog** 化されていた。Wave 5bet は **Wave 7b (Tournament +
Ranked queue) の foundation** として、これを Phase 1 PoC closing 中に
fast-forward する。

User の戦略意図:

> PvP は 1v1 コンテンツとかだろうか。色々工夫したい。

Wave 7b は既に 1v1 / 2v2 / team queue を計上済。本 ADR は **1v1 + betting**
を Wave 7b の上に追加し、ADR-0029 backlog を具体化する。

リサーチ根拠: `docs/competitive-analysis/07-cross-cutting.md` §7
(OSRS Duel Arena 教訓 + PoE Race 制約モデル)。

**重要な制約**:
- OSRS Stake (Duel Arena) は 2007 年 RWT 問題で discontinued、2024 に restricted
  form で復活。**moderation 負債が大きい**。
- JP regulatory: Wave 5f CurrencyPolicy (ADR-0035) に整合 (6ヶ月 expiry / ¥10M
  cap / 非換金)。本物の通貨ではないこと、依存症対策が必要。

## Decision

Wave 5bet (2026-05-24 landed; **originally planned for Wave 12f** but
fast-forwarded as 1v1 PvP retention foundation) で以下を land した。

### Domain (`economy.domain.betting.*`, 7 files)

- `BettingMatchId` — UUID-wrapping value class (server-minted per acceptance、
  human-authored ではないので [MatchId] とは異なり UUID で OK)。
- `BettingMatch` — challenger / opponent / stake / status / acceptanceDeadline
  / matchId? / winnerId? / endReason? 一体記録。state machine 6 状態
  (`PendingAcceptance` → `Active` → `Resolved` または終端 `Declined` /
  `Expired` / `Cancelled`)。
- `BetStake` — `(currency, amount)`; **GEM stake は domain 構築時 reject**
  (paid premium currency は JP 法律上 gambling 化リスクのため)。
- `BettingPolicy` + `BettingPolicyId` — per-server 設定可能 (`content/bet-
  policies/<id>.yaml` 駆動):
  - `maxStakePerMatch`: 1 試合あたり最大 stake (default 1,000)
  - `perCurrencyDailyStakeCap`: 1 日上限 (default 10,000) — PoE Race 流の
    daily ladder cooldown
  - `cooldownAfterMatch`: 連続 betting 防止 (default 10 分)
  - `acceptanceTimeout`: 申請 timeout (default 10 分)
  - `lossStreakLimit`: 連敗時の auto-cool-down (default 5 連敗で 24h disable)
  - `lossStreakCooldown`: 連敗 cool-down 期間
  - `allowedCurrencies`: 賭けに使える通貨 (default `[STAR, GOLD]`、GEM 不可)
  - `payoutSplit`: 勝者 payout 比率 (default 1.0 = winner all; Phase 4 で
    house cut tunable)
- `BettingEscrow` + `BettingEscrowStatus` (Held / Paid / Refunded) — 賭け金
  確保レコード; 監査の SSOT。
- `LossStreakState` — 連敗カウンタ + auto-cooldown 開閉ロジック。
- `BettingError` (sealed 15-variant) — `UnknownPolicy` / `CurrencyNotAllowed`
  / `StakeExceedsCap` / `DailyCapWouldBeExceeded` / `CooldownActive` /
  `LossStreakLimitReached` / `SelfBettingForbidden` / `PlayerOptedOut` /
  `UnknownMatch` / `NotPending` / `NotActive` / `AcceptanceDeadlineExpired` /
  `NotInvited` / `EscrowDebitFailed` / `AlreadyEscrowed` / `EscrowMissing`。

### Application (`economy.application.{port/outbound,service,usecase,config,contents,eventbus/listeners}.betting.*`)

**Outbound ports** (5):

- `IBettingMatchRepository` — find / save / recent-by-player / open-by-player
  / by-MatchId / by-status。
- `IBettingEscrowService` — hold / releaseToWinner / refundBoth / find。
  ledger 越しの atomic 動作 (rollback on opponent debit fail を含む)。
- `IBettingHistoryService` — daily-stake aggregation + cooldown tracking +
  loss-streak 持続。
- `IBettingPolicyRegistry` — YAML から読まれる policy lookup。
- `IBettingOptInProvider` — player の opt-in 状態 (default 全員 disabled)。

**Use cases** (4):

- `InitiateBettingChallengeUseCase` — 8 段階の fail-fast validation
  (self-bet / policy / currency / cap / opt-in / cooldown / loss-streak /
  daily-cap)。
- `AcceptBettingChallengeUseCase` — 受諾 → escrow.hold → matchId bind →
  2 件の `CurrencyChanged` (kind=`escrow_debit`) を publish。
- `DeclineBettingChallengeUseCase` — opponent decline / challenger rescind。
- `ExpirePendingBettingChallengesUseCase` — per-minute scheduler が
  acceptanceDeadline 超過の Pending を Expired に。

**Listeners** (2):

- `ResolveBettingOnMatchEndedListener` — `MatchEnded` → `BettingMatch`
  解決 → `BettingMatchResolved` publish。winning team から challenger /
  opponent を投影; どちらでもない (None / 両方) は Draw として refund-both。
- `DistributeBettingPayoutOnResolvedListener` — `BettingMatchResolved` →
  escrow release / refund + loss-streak 更新 + `BettingPayoutDistributed`
  publish + per-side `CurrencyChanged` (kind=`winner_payout` または `refund`)。

### Cross-plugin domain events (`core.domain.event.*`, 3 new)

- `BettingMatchInitiated` — Wave 5e audit log / Wave 12g analytics 用。
- `BettingMatchResolved` — 経済プラグイン内部の payout listener が消費 +
  audit / analytics も購読。
- `BettingPayoutDistributed` — 最終の fund flow audit (`winner_paid` /
  `refunded_both`)、JP 法令準拠の compliance SSOT。

加えて `CurrencyChangeReason.Betting(bettingMatchUuid, kind)` variant を追加
し、`CurrencyChanged` 経由で analytics が escrow / payout / refund 3 系列を
区別できるようにした。

### Persistence (V38 + V39 migrations)

- **V38 `betting_matches`** — UUID PK + policy_id + challenger / opponent +
  stake currency / amount + status + lifecycle timestamps + match_id (NULL
  until accept) + winner_id (NULL until resolve) + end_reason_kind +
  end_reason_detail。Indexes: `status` / `match_id` partial /
  `(challenger, created_at DESC)` / `(opponent, created_at DESC)`。
- **V39 `bet_escrows`** — bettingMatchId PK + FK to V38 + challenger /
  opponent + currency + per_side_amount + status + held_at + settled_at。
  CHECK constraint: `(status='Held' AND settled_at IS NULL) OR (status IN
  ('Paid','Refunded') AND settled_at IS NOT NULL)`。

**Postgres overlays** (priority 100): `PostgresBettingMatchRepository` +
`PostgresBettingEscrowService` (transactional debit-credit pair via injected
`ICurrencyLedger`) + `PostgresBettingHistoryService` (SQL aggregate for
daily stake; in-process map for loss-streak / last-resolution — 同じ
hybrid パターンを `PostgresEngagementSnapshotRepository` でも採用)。

### Konsist (+1 rule)

- `betting domain stays pure (ADR-0103)` — `economy.domain.betting.*` MUST
  NOT import Bukkit / kotlinx.serialization / exposed / postgres / sql /
  lettuce / koin。`scopeFromProject().files` ベース (将来の plugin rename を
  吸収)。

### Workflow

1. Challenger が `/astera-bet challenge <player> <stake>` で申請 (Phase 2
   surface; Wave 5bet は domain + ports + persistence + 構成済み listener
   まで land、command surface は Phase 2 で追加)。
2. Opponent が accept / decline (10 分 timeout、Wave 5bet スケジューラが per-
   minute sweep)。
3. Accept で **両者の wallet から stake が debit される** (`escrow.hold`、
   片方失敗時は他方を refund して all-or-nothing)。matchId に紐付け。
4. Match 終了 → `MatchEnded` 受信 → `BettingMatchResolved` publish。
5. payout listener が escrow release / refund + loss-streak 更新 +
   `BettingPayoutDistributed` publish。
6. (Wave 5e audit) 全 betting イベントを audit_log に persist。Wave 5bet は
   イベントを発火するのみ、persistence は audit log の既存の
   `AuditLogListener` が ad-hoc に処理 (Phase 2 で `BettingAuditListener`
   追加予定)。

### Out of scope (deferred)

- `/astera-bet` command surface — Phase 2 (next wave)。Wave 5bet は domain
  + use case + listener + persistence + Konsist + Plugin.kt wiring まで。
- Item stake — OSRS Duel Arena 反例 (`docs/competitive-analysis/04-osrs.md`)
  により Phase 1 PoC 段階では **currency-only**。Phase 4+ で `BetStake`
  に sealed variant 追加 (item-with-power-index based) を検討。
- Multi-party / spectator betting — Phase 4+ backlog。
- ELO 別 ladder — Wave 7b ranked queue ladder 整合は Wave 7b 着手時に
  concrete 設計。

## Consequences

### Positive

- RTM `コンテンツ案一覧.md` backlog 具体化、ADR-0029 §Phase 4+ backlog 解消
- 1v1 PvP の retention 向上 (stake が engagement を増やす — PoE Race 流の
  fairness が前提)
- Wave 7b (Tournament + Ranked queue) のための 1v1 foundation を fast-forward

### Negative / Trade-offs

- Moderation 負担増 (cheating / collusion / scam の reporting / 対応 SLA)
- 依存症リスク (loss-streak limit で軽減するが完全防止ではない)
- 不正検出 false positive で player frustration
- per-match の Postgres write が 1 件追加 (escrow hold / release で 2 件;
  V38 row update も 2 件)

### Mitigations

- Wave 8f Moderation tooling 稼働後に enable (順序固定 — Wave 5bet
  `IBettingOptInProvider` の default `disabled` がその間の gating)
- BettingPolicy はサーバ運営者が tunable (default 値は控えめに)
- Wave 12g Analytics で betting metric を監視 (avg loss streak / 異常パターン
  検出); `CurrencyChangeReason.Betting(kind=...)` が 3 系列を区別可能に
- player opt-in 必須 (default は disable)
- `payoutSplit` 経由で Phase 4 house-cut switch (依存症対策ファンディング
  原資にもなる)

## Alternatives Considered

- **Real money 換金可能 betting**: 拒否 (JP 法令違反リスク + RWT 問題)
- **Wave 7b に統合**: 拒否 (Wave 7b は launch 必須機能、Betting は Phase 4+
  optional として明確分離)
- **Betting なしの "casual 1v1"**: Wave 7b で既にカバー済、本 ADR は betting
  にのみ focus
- **Item stake initial allow**: 拒否 (OSRS 教訓; Phase 4+ で再考)
- **`:plugin:match` に置く**: 拒否 (Match plugin → Combat → Items の sibling
  matrix で、Economy にしか currency / wallet 概念がない。Betting は本質
  的に通貨フロー)

## References

- 関連 ADR: [[adr/0029-rtm-empirical-catalog]] §"Newly-discovered Phase 4+
  backlog" (出自) / [[adr/0035-marketplace-jp-currency-compliance-cel]]
  (CurrencyPolicy / GEM 非 wagerable) / [[adr/0037-...]] (Wave 7b
  Tournament) / [[adr/0044-...]] (Wave 8f Moderation) / [[adr/0099-pve-
  content]] (parallel fast-forward 事例)
- 関連 docs: [[competitive-analysis/04-osrs]] (Duel Arena 反例) /
  [[competitive-analysis/03-path-of-exile]] (PoE Race fairness) /
  [[competitive-analysis/07-cross-cutting]] §7
- RTM source: `docs/Writerside/topics/コンテンツ案一覧.md` (post-launch ideas)
