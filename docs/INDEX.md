# Astera Docs Index

Astera は AstarWorks 公式 Minecraft プロジェクト。Siege Warfare PvP / Community UGC / Metaverse Place の 3 本柱を、Clean Architecture と AI 委託で実現する。

## クイックリンク

- **新しく参加した人へ** → [contributing/getting-started.md](contributing/getting-started.md)
- **アーキテクチャの全体像** → [architecture/overview.md](architecture/overview.md)
- **進行中の Phase** → [roadmap.md](roadmap.md)
- **インフラ構成** → [infrastructure/overview.md](infrastructure/overview.md)
- **用語が分からない** → [glossary.md](glossary.md)

## セクション

| ディレクトリ | 内容 |
|---|---|
| [`adr/`](adr/) | Architecture Decision Records (なぜそう決めたか) |
| [`architecture/`](architecture/) | 設計の現在地。コードと同期して育てる生きた仕様 |
| [`infrastructure/`](infrastructure/) | k8s / GitOps / 監視 / スケーリング |
| [`operations/`](operations/) | 運用 runbook |
| [`incidents/`](incidents/) | インシデント事後分析 |
| [`contributing/`](contributing/) | コントリビュータ参加導線 (YAML / Kotlin / Web / Discord) |
| [`game-design/`](game-design/) | ゲーム企画。Siege / UGC / Place / 武器 / 経済など |
| [`ai-pipeline/`](ai-pipeline/) | AI 委託パイプライン (Phase 6 で本格化) |
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
