---
status: Accepted
date: 2026-05-16
deciders: ryuzu
---

# ADR-0002: vendor-neutral Minecraft Adapter 層

## Status

Accepted

## Context

[[adr/0001]] でドメインを Minecraft 非依存にすることを決めた。しかし、Minecraft 連携部分 (adapter 層) 自体も**サーバーコア (Paper / Spigot / Folia / Purpur / Velocity 等) によって挙動が大きく異なる**。

- Folia は region-based threading で BukkitScheduler の前提が違う
- Velocity は proxy で player/world API がそもそも違う
- Fabric は API が完全に別

これらに対し「Paper 専用」で書いてしまうと、将来別コアに乗り換える時に adapter 層を丸ごと書き直すことになる。

## Decision

Minecraft 連携 adapter を **2 段構え**にする:

1. **`adapter-minecraft-api`** — vendor-neutral な Minecraft 概念抽象
   - `IMcServer`, `IMcWorld`, `IMcPlayer`, `IMcEntity`, `IMcBlock`, `IMcEvent`, `IMcScheduler`, ...
   - Bukkit / Paper API を**一切 import しない**
   - `application` の outbound port (例: `IPlayerGateway`) を `IMc*` を使って実装する
2. **`adapter-minecraft-impl-{paper,folia,spigot,velocity,...}`** — サーバーコア固有実装
   - `IMc*` を該当サーバーコアの API で具象化
   - Paper 用は `adapter-minecraft-impl-paper`
   - Folia 用は `adapter-minecraft-impl-folia` (Phase 後半で追加)

サーバーコア切替は `platform-*` で Koin module を差し替えるだけで済むようにする。

## Consequences

### Positive
- サーバーコア切替コストが新規 `minecraft-impl-X` module 追加のみで済む
- Folia の region-based threading のような構造的な違いも、`IMcScheduler` の実装側で吸収できる
- ドメイン/application はサーバーコアを知らない

### Negative / Trade-offs
- 抽象化が 1 段増えるため、設計コストとファイル数が増える
- 「Bukkit の Player」と「Astera の IMcPlayer」と「ドメインの PlayerId」の 3 レイヤを行き来する
- `IMc*` の設計を間違えると後から修正が大変

### Mitigations
- Phase 1 では Paper のみ実装。Folia/Spigot/Velocity は構造だけ用意して空にする
- `IMc*` の API は Paper の API 形状をベースに作り、Folia 移行時に必要なら拡張する (YAGNI を守る)
- 依存ルールを Detekt で強制: `adapter-minecraft-api` で `org.bukkit.*` import を禁止

## Alternatives Considered

- **Paper API 直接利用**: 1 段省けるが [[adr/0001]] の長寿命目標と矛盾
- **Sponge SpongeAPI ベース**: 既存抽象を使うが活動停滞 + 自前で必要十分なものだけ作る方がコントロール可能

## References

- 関連 ADR: [[adr/0001-clean-architecture-with-mc-as-edge]]
- 関連 docs: [[architecture/mc-adapter-layer]]
- 計画書 §3.1, §3.1.1
