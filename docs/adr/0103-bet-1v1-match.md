---
status: Draft
date: 2026-05-21
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0085: 1v1 Betting Match (RTM `コンテンツ案一覧.md` 由来)

## Status

Draft

## Context

RTM Writerside `コンテンツ案一覧.md` に "賭け1v1" が post-launch アイデアと
して記録されており、ADR-0029 §"Newly-discovered Phase 4+ backlog items" で
**Phase 4+ backlog** 化されている。**具体 Wave 未割当** の状態が続いていた。

User の戦略意図:

> PvP は 1v1 コンテンツとかだろうか。色々工夫したい。

Wave 7b (Tournament + Ranked queue) は既に 1v1 / 2v2 / team queue を計上済。
本 ADR は **1v1 + betting** を Wave 7b の上に追加し、ADR-0029 backlog を
具体化する。

リサーチ根拠: `docs/competitive-analysis/07-cross-cutting.md` §7
(OSRS Duel Arena 教訓 + PoE Race 制約モデル)。

**重要な制約**:
- OSRS Stake (Duel Arena) は 2007 年 RWT 問題で discontinued、2024 に restricted
  form で復活。**moderation 負債が大きい**。
- JP regulatory: Wave 5f CurrencyPolicy (ADR-0035) に整合 (6ヶ月 expiry / ¥10M
  cap / 非換金)。本物の通貨ではないこと、依存症対策が必要。

## Decision

Wave 12f で以下を land する:

### Domain

- `BettingMatch` (challenger / opponent / stake / payout split / status)
- `BettingPolicy` 設定可能パラメータ:
  - `maxStakePerMatch`: 1 試合あたり最大 stake (default: 通貨総量の 5%)
  - `cooldownAfterMatch`: 連続 betting 防止 (default: 10 分)
  - `lossStreakLimit`: 連敗時の auto-cool-down (default: 5 連敗で 24h disable)
  - `enabledForPlayer`: opt-in 必須 (Wave 5e PlayerSettings)
- `BettingMatchInitiated` / `BettingMatchResolved` / `BettingPayoutDistributed`
  domain event
- Wave 5e audit log integration: `BettingAudit` 全 betting event を記録

### Constraints (compliance + safety)

- **ゲーム内通貨のみ** (real money 換金不可、Wave 5f JP CurrencyPolicy 整合)
- ELO 影響は **別 ladder** (Wave 7b ranked queue ladder と混在させない、cheating
  incentive 低減)
- Wave 8f (Moderation tooling) 稼働後に enable 推奨
- 不正検出時の payout 凍結機能 (admin UI from Wave 8a)

### Workflow

1. Challenger が `/astera-bet challenge <player> <stake>` で申請
2. Opponent が accept / decline (10 分 timeout)
3. Match 開始 (既存 Wave 7b ranked queue 流用、別 ladder)
4. Match 終了 → `BettingMatchResolved` publish → payout
5. `BettingAudit` event で全記録 → Wave 5e audit_log persist

## Consequences

### Positive

- RTM `コンテンツ案一覧.md` backlog 具体化、ADR-0029 §Phase 4+ backlog 解消
- 1v1 PvP の retention 向上 (stake が engagement を増やす — PoE Race 流の
  fairness が前提)

### Negative / Trade-offs

- Moderation 負担増 (cheating / collusion / scam の reporting / 対応 SLA)
- 依存症リスク (loss-streak limit で軽減するが完全防止ではない)
- 不正検出 false positive で player frustration

### Mitigations

- Wave 8f Moderation tooling 稼働後に enable (順序固定)
- BettingPolicy はサーバ運営者が tunable (default 値は控えめに)
- Wave 12g Analytics で betting metric を監視 (avg loss streak / 異常パターン
  検出)
- player opt-in 必須 (default は disable)

## Alternatives Considered

- **Real money 換金可能 betting**: 拒否 (JP 法令違反リスク + RWT 問題)
- **Wave 7b に統合**: 拒否 (Wave 7b は launch 必須機能、Betting は Phase 4+
  optional として明確分離)
- **Betting なしの "casual 1v1"**: Wave 7b で既にカバー済、本 ADR は betting
  にのみ focus

## References

- 関連 ADR: [[adr/0029-rtm-empirical-catalog]] §"Newly-discovered Phase 4+ backlog" (出自) / [[adr/0035-marketplace-jp-currency-compliance-cel]] (CurrencyPolicy) / [[adr/0037-...]] (Wave 7b Tournament) / [[adr/0044-...]] (Wave 8f Moderation)
- 関連 docs: [[competitive-analysis/07-cross-cutting]] §7
- RTM source: `docs/Writerside/topics/コンテンツ案一覧.md` (post-launch ideas)
