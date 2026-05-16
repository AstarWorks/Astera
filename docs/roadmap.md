# Roadmap

Astera の Phase 計画。各 Phase は 1〜2 ヶ月目安。

詳細な背景は [計画書 (private)](/home/node/.claude/plans/) を参照。

## Phase 1: 基盤 ✅ (コード/マニフェスト完了、実機検証は実クラスタ次第)

**ゴール**: 疎結合プラグインアーキテクチャ + k8s manifest。ゲーム機能は最小限。

成果 (2026-05-16 時点):

| Deliverable | 状態 |
|---|---|
| Hexagonal 多モジュール構成 (`domain` / `application` / `adapter-*` / `platform-*`) | ✅ |
| レイヤ依存方向を Konsist で CI 強制 (Detekt は JDK 25 対応待ち) | ✅ |
| `adapter/minecraft-api` (vendor-neutral) + `adapter/minecraft-impl-paper` | ✅ |
| 参考武器 (バニラ完結) を YAML 1 枚で in-game に出せる経路 | ✅ |
| `docker/` (Dockerfile + compose) で local Paper + Postgres + Redis 起動 | ✅ (要 docker compose up 実機検証) |
| `deploy/` k8s manifest 44 ファイル (ArgoCD GitOps 構成) | ✅ (要 kind / 実クラスタ検証) |
| `.github/workflows/{ci,image}.yml` CI + GHCR push | ✅ (PR で実行確認は次回) |
| Konsist negative test (domain で bukkit import → fail) | ✅ 実証済 |
| JP/EN i18n リソース | ✅ |
| `docs/` 必須ファイル ([docs/INDEX.md](INDEX.md) 参照) | ✅ |
| README "5-minute setup" + docker README | ✅ |

完了基準: [計画書 §9 + §10](/home/node/.claude/plans/https-github-com-y-ryuzu-ryuzutechnicalm-silly-knuth.md) を参照。

### 次のアクション (Phase 1 残りタスク)

- `docker compose up` を手元で実行 → in-game `/astera give @p example-sword` で動作確認
- ローカル kind cluster で `kubectl apply -f deploy/apps/argocd-apps.yaml` 検証
- `AstarWorks/AsteraPrivate` (非公開 submodule) 作成 + 環境固有 overlay 切出し

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
