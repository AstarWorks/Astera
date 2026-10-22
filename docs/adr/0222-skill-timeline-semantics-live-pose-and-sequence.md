---
status: Accepted
date: 2026-10-22
deciders: founder, Claude (orchestrator)
supersedes: null
superseded_by: null
---

# ADR-0222: Skill timeline semantics — live pose, fire-time conditions, true multi-hit, `sequence` archetype

## Status

Accepted

## Context

ADR-0221 の 10 人格武器 (40 スキル) を ADR-0220 の基盤で書いた結果、作者 5 名が同じ穴に当たった:
多段ヒットが 20 tick の無敵時間で消える (`ignore_invulnerability` が未実装)、遅延ビートがキャスト時の位置・条件で
評価される (ダッシュ後の斬撃が元の位置に出る)、`filter` が条件を無視、放射ノックバックが無い、
hit_stop が同 tick のノックバックを消す、スキル全体がタイムラインの場合に `melee range 0.5` や
ダミーステータス付き `buff` を器として濫用していた、など。

## Decision

- **真の多段**: `damage.ignore_invulnerability` を damage gateway まで通す (Paper / sim)。
- **ライブポーズ既定**: delay / stride / period で遅れて発火する mechanic は、発火時点の術者位置・視線でターゲッタを解決し、
  `condition` も発火時に評価する。旧挙動は `snapshot_pose: true` で選べる。投射物命中コンテキストは対象外。
- **`filter`** は候補ごとに条件評価。**方向** `away_from_caster` / `toward_caster` を追加。`enemies` / `allies` に `radius`。
- **hit_stop 中の knockback / knockup / propel は解除時に適用** (記述順に依存しない)。
- **VFX**: `group_keyframes` (フォーメーション全体の移動・回転・等倍スケール)、`end_tick` はスロット共通の絶対時刻
  (`slot_lifetime_ticks` で個別寿命)。
- **`sequence` archetype**: cast フェーズのみ、命中判定なし。タイムラインだけのスキルの器。
- `restore_resource`、素手でも resist 属性が効く (`ArmorReductionModifier` の早期 return 撤去)、melee/aoe の `params.damage` で `per_stack`。

## Consequences

### Positive
- Limbus 的「コイン = ヒット数」を実ダメージイベントとして表現できる。ダッシュ → 多段の位置ずれが無くなる。

### Negative / Trade-offs
- ライブポーズ既定は挙動変更 (Pre-release posture により互換 shim なし)。既存スキルは game-sim で再確認済み。
- 無敵時間貫通は TTK を縮めうる。多段は 1 ヒットを小さくし合計を据え置く運用とする。

## Alternatives Considered
- **無敵時間を全体で短縮**: 通常攻撃の連打まで速くなるため不採用。スキル側の明示フラグにした。

## References
- ADR-0220, ADR-0221
