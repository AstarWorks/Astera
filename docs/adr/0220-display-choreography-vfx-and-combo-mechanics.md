---
status: Accepted
date: 2026-10-22
deciders: founder, Claude (orchestrator)
supersedes: null
superseded_by: null
---

# ADR-0220: Display choreography (content/vfx), math curves, combo/stack/dash mechanics

## Status

Accepted

## Context

施主の要望: Limbus Company の人格 (Identity) 紹介動画の 2D アニメーションを参考に、スキルと武器を
「演出込み」で作る。パーティクルだけでは表現力が足りず、Block / Item / TextDisplay を使った演出
(Sky Torch 等のデータパック事例) が参考になる。スキルの土台が足りなければ併せて実装する。

既存の土台で足りなかったもの (調査結果):
- `spawn_display` は静的に 1 体を出すだけ。キーフレーム・イージング・クライアント補間・配置 (フォーメーション) が無い。
- 曲線 / クォータニオン演算 / ローカル座標系などの数学が無い (`Quaternion` はデータのみ)。
- `loop.stride_ticks` と `apply_status.stack_count` がパースされるが無視されていた。スタック数で条件分岐・ダメージ加算・消費ができない。
- 移動しながら当てるダッシュ攻撃、ヒットストップが無い。

## Decision

1. **数学 (`core.domain.geometry.math`)**: `Quaternion` (演算・slerp・euler・lookRotation)、`Frame` (MC ローカル軸: +Z 前 / +Y 上 / +X 左)、
   `Curve` (line / arc / helix / spiral / bezier / polygon(star) / fibonacci sphere / lightning / transformed + `distribute`)、
   `SeededRandom`、`Easing.fromKey`。純 Kotlin、Bukkit 非依存。
2. **VFX 定義 (`content/vfx/*.yaml`)**: 要素 (block / item / text / particle) × フォーメーション (曲線上に N 個、tangent/outward 整列、stagger)
   × キーフレーム (translate / rotate / scale / ease、block・text の差し替え = 透明度の代わりの「ブロックラダー」フェード) × oscillate × jitter。
   純粋なプランナ `VfxTimeline.plan` が各 display の spawn / update(transform, interpolation ticks) / despawn を事前計算し、
   イージングは `sample_ticks` 刻みのクライアント線形補間で近似する。スキル側は `play_vfx { vfx_id }` で参照。
   ランタイム `VfxPlayer` (combat application) が `IDisplayEntityGateway` (update / setBlock / setText / teleport を追加) 経由で再生。
   同時表示上限 600。content-lint が `vfx_id` / vfx 内 `particle_id` 参照を検証、schema-gen が `vfx.schema.json` を生成。
3. **コンボ系メカニクス**: `loop.stride_ticks` を実装、`apply_status.stack_count` を通す、`has_status.min_stacks`、
   `remove_status.stacks` (消費)、damage component の `per_stack` (スタック数比例の加算、consume 可)、
   `dash_strike` (視線方向へ毎 tick 移動 + カプセル判定 + on_hit + 無敵)、`hit_stop` (数 tick 拘束)。
   新ステータス tremor / rupture / sinking / poise / charge (stack マーカー)。

## Consequences

### Positive
- 演出をすべて YAML で書ける。Limbus 的な「予備動作 → 移動 → 多段 → 決め → 余韻」を delay_ticks + play_vfx + hit_stop で表現可能。
- プランナが純粋関数なので game-sim / 単体テスト / オフラインプレビュー (`tools/vfx-preview`) で検証できる。

### Negative / Trade-offs
- Display entity はパケットコストが高い。1 キャスト 60 体程度、同時 600 体を上限とする。
- クライアント補間は線形のみ。イージングは分割更新で近似 (`sample_ticks`)。
- `dash_strike` / `hit_stop` の数値は YAML 上スカラ固定 (CEL 不可)。

### Mitigations
- `VfxPlayer` の上限超過は新規インスタンスを捨てて debug log。プレビューツールで display 数を確認できる。

## Alternatives Considered

- **パーティクルのみで拡張**: 太い斬撃線・巨大武器投影・文字演出が出せない。
- **リソースパックのカスタムモデル + アニメーション**: アセット制作コストが高く、ADR-0010 (生成 AI 資産は Phase 6) とも衝突。Display + バニラブロックで十分な表現が出る。
- **スキルごとにインラインで display を記述**: 再利用性が無く YAML が肥大化するため、`content/vfx/` に分離して ID 参照にした。

## References
- ADR-0077 (spawn_display), ADR-0230 (persona weapon roster)
- 参考動画: ProjectMoon 公式チャンネル (Limbus Company 人格紹介)、"Orbital Lasers in Minecraft (Sky Torch)"
