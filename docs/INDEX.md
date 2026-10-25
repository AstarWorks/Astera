# Astera Docs Index

Astera は AstarWorks 公式 Minecraft プロジェクト。Siege Warfare PvP / Community UGC / Metaverse Place の 3 本柱を、Clean Architecture と AI 委託で実現する。

## クイックリンク

- **新しく参加した人へ** → [contributing/getting-started.md](contributing/getting-started.md)
- **アーキテクチャの全体像** → [architecture/overview.md](architecture/overview.md)
- **構想と今の範囲 (2026-10)** → [vision/VISION.md](vision/VISION.md) — 柱・現スコープ・範囲外・施主の判断尺度。領域別の現状/理想/未決は [vision/areas/](vision/areas/)。次にやることは Board (`.claude/rules/board.md`)
- **遊んでみる** → [contributing/playtest.md](contributing/playtest.md) — Docker 起動 → 武器 → マッチ
- **Limbus スキル再現チーム (director + 専門役 + reviewer)** → [contributing/skill-team/README.md](contributing/skill-team/README.md)
- **ヘッドレス・シミュレータ** → [`tools/game-sim/README.md`](../tools/game-sim/README.md) + [ADR-0174](adr/0174-host-neutral-wiring-and-headless-game-simulator.md)
- **コマンド (`/astera <area> <action>` 単一ツリー)** → [ADR-0184](adr/0184-single-astera-command-tree.md) — 権限スキーム・補完・help・追加方法
- **Board と領域仕様が tracker/roadmap を置き換えた経緯** → [adr/0255-board-and-area-specs-replace-trackers.md](adr/0255-board-and-area-specs-replace-trackers.md)
- **RTM との設計差** → [architecture/rtm-divergence.md](architecture/rtm-divergence.md)
- **トラック運用 (Board / 所有表 / 統合窓)** → [`.claude/CLAUDE.md`](../.claude/CLAUDE.md) + [`.claude/rules/board.md`](../.claude/rules/board.md) + [`.claude/rules/tracks.md`](../.claude/rules/tracks.md) + [`.claude/rules/wave-execution.md`](../.claude/rules/wave-execution.md)
- **インフラ構成** → [infrastructure/overview.md](infrastructure/overview.md)
- **用語が分からない** → [glossary.md](glossary.md)
- **CAD × LLM サーベイ** → [research/model-gen-llm-survey.md](research/model-gen-llm-survey.md) — 3D 生成 SOTA + Astera 改善ロードマップ

## セクション

| ディレクトリ | 内容 |
|---|---|
| [`adr/`](adr/) | Architecture Decision Records (なぜそう決めたか) — 0001〜0255 (欠番あり) |
| [`vision/`](vision/) | 構想 (VISION.md) と領域別仕様 (areas/): 現状・理想形・未決 |
| [`architecture/`](architecture/) | 設計の現在地。コードと同期して育てる生きた仕様 |
| [`infrastructure/`](infrastructure/) | k8s / GitOps / 監視 / スケーリング |
| [`operations/`](operations/) | 運用 runbook |
| [`incidents/`](incidents/) | インシデント事後分析 |
| [`contributing/`](contributing/) | コントリビュータ参加導線 (YAML / Kotlin / Web / Discord) |
| [`game-design/`](game-design/) | ゲーム企画。Siege / UGC / Place / 武器 / 経済など |
| [`ai-pipeline/`](ai-pipeline/) | AI 委託パイプライン (Phase 6 で本格化) |
| [`research/`](research/) | 業界調査 / サーベイ (CAD × LLM, モデル生成手法 等) |
| [`security/`](security/) | 公開可能な範囲のセキュリティ方針 |
| [`reference/`](reference/) | 自動生成リファレンス (YAML / port / event) |
| [`reference/vfx/`](reference/vfx/README.md) | VFX 資料集 (桜井・失敗事例・業界技法・Minecraft 技法・Re:DIVERSE・改善案。skill を作る/直す前に読む) |

## トップレベル原則 (一覧)

1. **Minecraft はエッジ** — ドメインは MC 非依存
2. **サーバーコアも差替え可能** — Paper/Spigot/Folia/Velocity を adapter で切替
3. **Version Resilience First** — 月1の依存更新 PR で済むこと
4. **External Plugin Optionality** — すべて soft-dep + 機能フラグ
5. **Configuration over Code** — YAML だけでコンテンツ追加可
6. **GitOps Native** — ArgoCD + Kustomize で k8s 宣言的管理
7. **AI-Ready Workflow** — `.claude/` を最初から
8. **Contributor Friendliness (Non-IT)** — YAML / Web / Discord で参加可能
9. **Public/Private 分離** — OSS と運用セキュリティの両立
10. **Architectural Enforcement** — レイヤ依存方向を CI で強制

詳細は [architecture/principles.md](architecture/principles.md)。
