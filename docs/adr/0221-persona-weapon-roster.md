---
status: Accepted
date: 2026-10-22
deciders: founder, Claude (orchestrator)
supersedes: null
superseded_by: null
---

# ADR-0221: Persona weapon roster (Limbus Company-inspired, 10 weapons)

## Status

Accepted

## Context

施主要望: ProjectMoon 公式チャンネルの Limbus Company 人格紹介動画を参考に、スキルと武器を実装する。
人格 = 1 キャラの「別の生き方」= モチーフ・配色・固有のスタック仕組み・3 スキル + 防御 で構成される。
2D アニメーション (ポーズの切り替え、多段ヒット、斬撃線、インパクトフレーム、文字演出) を、
ADR-0220 の Display 演出基盤 (`content/vfx`) とコンボ系メカニクスで Minecraft 上に翻訳する。

動画は frame contact sheet (3fps) に分解して解析し、ビート (予備動作 → 移動 → 多段 → 決め → 余韻) と
「看板演出」を抽出した。名前・台詞・ロゴ・キャラ名は一切流用しない (着想のみ)。

## Decision

10 本の「人格武器」を追加する。各武器は 4 バインドの固定キット:

| trigger | 役割 |
|---|---|
| left_click | S1 基本 (速い 1-3 hit、スタックを貯める) |
| right_click | S2 特殊 (移動 + 多段、看板演出) |
| sneak_right | S3 決め技 (大演出、スタック消費 / 比例、長 CD) |
| sneak_left | DEF 防御 / カウンター / 回避 |

| item | 人格 | スタック |
|---|---|---|
| couture-rapier | 仕立屋の細剣 | poise |
| gossamer-katana | 蜘糸の太刀 | bleed |
| vendetta-rifle | 報復の銃剣 | ammo + tremor |
| ember-gauntlet | 灼拳の手甲 | burn + charge |
| bastion-greatsword | 城壁の大剣 | charge |
| verdict-hammer | 審判の杭槌 | rupture |
| crimson-chain-axe | 緋の鎖斧 | rupture |
| sigil-rifle | 刻印の狙撃銃 | sinking |
| orrery-staff | 天球儀の杖 | charge |
| edict-blade | 勅令の刃 | poise + rupture |

制約: 1 キャストの同時 display ≤ 60、演出 ≤ 60 tick (決め技 ≤ 100)。大技は PvP で読めるよう必ず予兆 (telegraph) を出す。
検証: 武器ごとの game-sim persona シナリオ (ダメージ / スタック / display 上限 / 後片付け)、`tools/vfx-preview` によるフレーム目視、
`tools/e2e` の PersonaWeaponsE2eTest (実サーバ + ボット)。

## Consequences

### Positive
- ADR-0220 の基盤を 10 武器 × 4 スキルで実運用し、足りない機能 (無敵時間を貫通する多段、filter 条件、放射ノックバック、
  遅延ビートの現在位置解決、グループキーフレーム、sequence archetype 等) を洗い出して基盤側に戻せた。
- 武器追加が YAML のみで完結する。

### Negative / Trade-offs
- Display entity を多用するため、多人数戦での負荷は要観測 (VfxPlayer の 600 上限で頭打ち)。
- バランスは初期値。TTK は既存ロスターに合わせたが実プレイでの調整が必要。

### Mitigations
- game-sim で 1 キャストあたりの display ピークを assert している。

## Alternatives Considered

- **既存 6 武器の演出強化のみ**: 人格 = 固有スタック + 4 スキルの「キット」概念が表現できない。
- **Limbus のコイン判定 (確率パワー) をそのまま導入**: リアルタイム PvP では不透明なランダム性になるため、多段ヒット数 + スタック消費で表現。

## References
- ADR-0220 (display choreography / combo mechanics)
- ProjectMoon 公式 YouTube (Limbus Company Identity showcase 各動画)
