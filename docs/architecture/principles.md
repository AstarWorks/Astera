# Architecture Principles

Astera の設計を支配する原則。これらに反するコード/設計提案は基本的に拒否される。

## 0. 最上位原則: Minecraft はエッジ

**Astera の本体は Minecraft プラグインではなく、ゲームドメインそのもの**。Minecraft はそれを表現する現在の主要エッジ (delivery channel)。

詳細: [[adr/0001-clean-architecture-with-mc-as-edge]]

## 1. Clean Architecture / Hexagonal

- 4 層: `domain` / `application` / `adapter` / `platform`
- 依存方向は **上→下** のみ (`platform` → `adapter` → `application` → `domain`)
- 依存逆転 (DIP): `application` が outbound port を定義、`adapter` が実装

## 2. サーバーコアも差替え可能

- `adapter-minecraft-api` で **vendor-neutral な MC 概念抽象**を定義
- Paper / Folia / Spigot / Velocity 用 impl module を切り替えで対応

詳細: [[adr/0002-vendor-neutral-mc-adapter-layer]], [[mc-adapter-layer]]

## 3. SOLID の適用

| 原則 | Astera での具体化 |
|---|---|
| **S**RP | `Weapon` が DB 保存メソッドを持たない |
| **O**CP | 武器/スキル/モードは Strategy + Registry で拡張 |
| **L**SP | adapter 契約はテスト double でも保たれる |
| **I**SP | 太い `IGameService` は分割 (`IMatchLifecycle`, `IMatchQuery`, `IMatchScore`) |
| **D**IP | application は port のみ参照、具象 adapter は知らない |

## 4. Version Resilience First

- Bukkit/NMS 呼び出しは `adapter-minecraft-impl-paper/` に隔離
- `paperweight-userdev` は必要時のみ
- 月1の依存更新 PR で済む構造

詳細: [[adr/0003-kotlin-jdk25-paper-26]]

## 5. External Plugin Optionality

- 外部 Paper プラグインはすべて **soft-dep** ([[adr/0009]])
- Provider パターン (`OraxenItemProvider` / `BukkitItemProvider`)
- バニラのみでも全機能 (代替実装で) 起動可能

## 6. Configuration over Code

- 武器/スキル/ステージ/報酬/UI 文言は YAML
- Kotlin を書かずに新コンテンツを追加できる範囲を最大化

## 7. GitOps Native

- k8s manifest は **ArgoCD + Kustomize**
- `git push` がデプロイ

詳細: [[adr/0006-gitops-with-argocd-kustomize]]

## 8. AI-Ready Workflow

- `.claude/` を最初から確立
- 生成 AI は Phase 6 で本格解禁 ([[adr/0010]])

## 9. Contributor Friendliness (Non-IT)

- YAML / 画像 / schematics の投稿だけでマージできる導線
- Phase 1 では YAML 経路を docs で完成
- Phase 4 で Discord/Web 経路

## 10. Public/Private 分離

- public モノレポ + 非公開 submodule
- OSS 公開と運用機密の両立

詳細: [[adr/0008-monorepo-with-private-submodule]]

## 11. Architectural Enforcement

レイヤ依存方向と import 制約を **CI で強制**:

- Konsist (ArchUnit for Kotlin)
- Detekt カスタムルール
- Gradle module 間依存の制限

詳細: [[dependency-rules]]
