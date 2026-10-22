# Astera Docs Index

Astera は AstarWorks 公式 Minecraft プロジェクト。Siege Warfare PvP / Community UGC / Metaverse Place の 3 本柱を、Clean Architecture と AI 委託で実現する。

## クイックリンク

- **新しく参加した人へ** → [contributing/getting-started.md](contributing/getting-started.md)
- **アーキテクチャの全体像** → [architecture/overview.md](architecture/overview.md)
- **今の到達点と範囲 (2026-10 core vision)** → [plans/2026-10-core-vision-backlog.md](plans/2026-10-core-vision-backlog.md) — 作業パッケージ一覧。Phase 4+ は範囲外
- **遊んでみる** → [contributing/playtest.md](contributing/playtest.md) — Docker 起動 → 武器 → マッチ
- **ヘッドレス・シミュレータ** → [`tools/game-sim/README.md`](../tools/game-sim/README.md) + [ADR-0174](adr/0174-host-neutral-wiring-and-headless-game-simulator.md)
- **コマンド (`/astera <area> <action>` 単一ツリー)** → [ADR-0184](adr/0184-single-astera-command-tree.md) — 権限スキーム・補完・help・追加方法
- **進行中の Phase** → [roadmap.md](roadmap.md)
- **実装ロードマップ (SSOT)** → [IMPLEMENTATION-ROADMAP.md](IMPLEMENTATION-ROADMAP.md) — 12-tier、leverage-ordered
- **RTM パリティ追跡** → [RTM-PARITY-TRACKER.md](RTM-PARITY-TRACKER.md) — feature × state × wave 配賦
- **RTM との設計差** → [architecture/rtm-divergence.md](architecture/rtm-divergence.md)
- **Astera-original Content backlog (Phase 4+ speculative — 範囲外)** → [CONTENT-ROADMAP.md](CONTENT-ROADMAP.md) — RTM パリティ追跡と分離。Writerside `コンテンツ案一覧.md` の Astera 版相当
- **Wave 実行プロトコル** → [`.claude/CLAUDE.md`](../.claude/CLAUDE.md) + [`.claude/rules/wave-execution.md`](../.claude/rules/wave-execution.md)
- **インフラ構成** → [infrastructure/overview.md](infrastructure/overview.md)
- **用語が分からない** → [glossary.md](glossary.md)
- **CAD × LLM サーベイ** → [research/model-gen-llm-survey.md](research/model-gen-llm-survey.md) — 3D 生成 SOTA + Astera 改善ロードマップ

## セクション

| ディレクトリ | 内容 |
|---|---|
| [`adr/`](adr/) | Architecture Decision Records (なぜそう決めたか) — 0001〜0243 (欠番あり) |
| [`plans/`](plans/) | 作業計画 / バックログ (現行: core vision backlog) |
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
