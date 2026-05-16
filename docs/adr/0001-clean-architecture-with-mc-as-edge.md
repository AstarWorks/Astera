---
status: Accepted
date: 2026-05-16
deciders: ryuzu
---

# ADR-0001: Clean Architecture / Minecraft はエッジ

## Status

Accepted

## Context

Astera は当初 Minecraft プラグインとして始まるが、README のビジョンは "siege warfare PvP, community-driven UGC, metaverse-style 'place' design" であり、Minecraft 以外のフロントエンド (Web companion / Discord bot / 別ゲームエンジン) を将来排除したくない。

また、前身の RyuZUTechnicalMagic (RTM) は Bukkit/Paper に密結合で、Minecraft バージョン追従や依存プラグイン廃止に追従できない設計だった。

## Decision

**Astera の本体は Minecraft プラグインではなく、ゲームドメインそのもの**として定義する。Minecraft はそれを表現する**現在の主要エッジ (delivery channel)**であって、本体ではない。

具体的に:

- `domain` 層と `application` 層は Minecraft / Bukkit / Paper に**一切依存しない**
- ゲームロジック (攻城戦進行ルール、武器バランス計算、マッチング、経済、Place のプロット管理) は MC 非依存の純粋 Kotlin
- Minecraft 連携はすべて `adapter-minecraft-*` 配下に隔離
- 依存方向は **`domain` ← `application` ← `adapter` ← `platform`** の一方向のみ
- 依存逆転 (DIP): `application` が outbound port を定義、`adapter` が実装

## Consequences

### Positive
- ドメインのテストが Paper 起動なしで JUnit から実行可能 (高速 + 安定)
- MC バージョン更新や外部プラグイン廃止の影響範囲が adapter 層に閉じる
- 将来 Web / Discord / 別エンジンへの移植が新しい adapter 追加だけで済む
- LLM レビュー/生成 agent が Minecraft 知識ゼロでビジネスロジックを変更できる

### Negative / Trade-offs
- 「Bukkit の Player をそのまま使えば 1 行」が「PlayerId に変換して port 経由」になり、開発初期コストは増える
- レイヤを跨ぐ命名・型変換が必要 (PlayerId ↔ Bukkit.Player)
- Kotlin の表現力で薄い変換コードに留めても、ファイル数は増える

### Mitigations
- `adapter-minecraft-impl-paper/translator/` で変換を 1 箇所に集約
- ボイラープレートが目立つようなら extension function や DSL で軽量化を後付け
- 依存ルール違反は CI で fail させ、誤ってショートカットしないようにする ([[adr/0002]] 参照)

## Alternatives Considered

- **Plain Bukkit プラグイン (RTM 流継続)**: 早く書けるが、MC アップデートと外部プラグイン廃止に殉死する設計。Astera の長寿命目標と合わない
- **Sponge ベース**: Sponge も抽象化を提供するが活動停滞気味 + 学習コスト。自前 abstraction の方がコントロール可能

## References

- 関連 ADR: [[adr/0002-vendor-neutral-mc-adapter-layer]] (同じ思想を server core レベルにも適用)
- 関連 docs: [[architecture/principles]], [[architecture/layers]]
- 計画書 §1.0
