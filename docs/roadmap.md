# Roadmap

Astera の Phase 計画。各 Phase は 1〜2 ヶ月目安。

詳細な背景は [計画書 (private)](/home/node/.claude/plans/) を参照。

## Phase 1: 基盤 (進行中)

**ゴール**: 疎結合プラグインアーキテクチャ + k8s manifest。ゲーム機能は最小限。

主要 deliverable:
- Hexagonal 多モジュール構成 (`domain` / `application` / `adapter-*` / `platform-*`)
- レイヤ依存方向を Detekt + Konsist で CI 強制
- `adapter/minecraft-api` (vendor-neutral) + `adapter/minecraft-impl-paper`
- k8s manifest (Velocity / Lobby / Game / Postgres / Redis / Grafana / ArgoCD)
- 参考武器 (バニラ完結) を YAML 1 枚で in-game に出せる
- docs/ 必須ファイル ([docs/INDEX.md](INDEX.md) 参照)

完了基準は [計画書 §9](/home/node/.claude/plans/https-github-com-y-ryuzu-ryuzutechnicalm-silly-knuth.md) を参照。

## Phase 1.5: 動的 Game Pod スケール

**ゴール**: matchmaking キュー長 → KEDA → Game Job 動的起動。

## Phase 2: 武器/スキル/状態異常システム本体

**ゴール**: RTM の skill / easing / scheduler を Astera ドメインに移植。
- 3 武器アーキタイプ (銃 / 剣 / 魔法) の参考実装
- 状態異常コンテナ、Norma、レベル

## Phase 3: Siege Warfare 1 モード

**ゴール**: 1 マップ + 攻撃/防衛非対称 + コア破壊 + 構造物破壊許可域。

## Phase 4: UGC マーケット + 投稿導線

**ゴール**: Web/Discord から投稿 → 自動レビュー → merge → 配布。独自通貨。

## Phase 5: Metaverse Place

**ゴール**: 常設ワールド + プロット + SNS + イベント層。

## Phase 6: 生成 AI パイプライン解禁

**ゴール**: テクスチャ / サウンド / モデル / ステージの AI 生成 skill/agent 整備。

## Phase 7: 運営自動化

**ゴール**: 季節イベント / バトルパス / release-noter / incident-responder の本番投入。
