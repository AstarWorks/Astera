---
status: Accepted
date: 2026-05-16
deciders: ryuzu
---

# ADR-0003: Kotlin 2.x / JDK 25 / Paper 26.x

## Status

Accepted

## Context

RTM は Kotlin + JDK 17 + Paper 1.20.4 で書かれていた。Re-DIVERSE_infrastructure は JDK 21 + Paper 1.21.4 を採用。

2026年5月時点での状況:

- Minecraft Java Edition は **26.1.2** (2026-04-09) が最新。2026年から "year.drop.hotfix" 体系に移行し、1.21/1.22 などの番号体系は廃止
- **JDK 25 (LTS, 2025-09 リリース) が必須**。Paper 最新ビルドも JDK 25 を要求
- Re-DIVERSE_infrastructure の JDK 21 / Paper 1.21.4 構成は既に旧版

Astera は「常に最新マイクラ追従可能」を目標にしているため、最新の要求バージョンに揃える。

## Decision

- **言語**: Kotlin 2.x
- **JVM**: **JDK 25 (Eclipse Temurin, LTS)**
- **Paper**: **`paper-api 26.1.x` (compileOnly のみ)**
- **NMS**: 基本使わない。必要時のみ `paperweight-userdev` を該当モジュールだけに

## Consequences

### Positive
- 最新マイクラ (Java Edition 26.1.x) に直接対応
- JDK 25 LTS で長期保守が安定
- 仮想スレッドなど新機能の活用余地
- Re-DIVERSE が JDK 25 + 26.x に更新すれば再び運用ノウハウを共有できる

### Negative / Trade-offs
- Re-DIVERSE の現行 manifest (JDK 21 / 1.21.4) をそのまま流用できない。base image を `itzg/minecraft-server:java25` (要 itzg 側対応確認) または相当に置換する必要がある
- 2026年版マイクラの新バージョン体系 (year.drop.hotfix) は周辺ツール (プラグインの version 互換チェック) が追いついていない可能性

### Mitigations
- `gradle/libs.versions.toml` に集中管理し、月1の依存更新 PR で済む構造にする ([[adr/0006]] と整合)
- itzg/minecraft-server の最新タグを Phase 1 着手時に確認 ([[adr/0007]] 参照)
- 周辺プラグイン互換は機能フラグ + soft-dep で吸収 ([[adr/0009]])

## Alternatives Considered

- **JDK 21 + Paper 1.21.4 (Re-DIVERSE 互換)**: 動作実績はあるが「常に最新追従」の目標と矛盾。今後の更新ですぐ陳腐化
- **JDK 21 + Paper 26.x (旧 JDK + 新 MC)**: Paper 26.x が JDK 25 必須なので不可能

## References

- 関連 ADR: [[adr/0007-itzg-mc-server-base-image]]
- 計画書 §3.2
- [Minecraft Java Edition 26.1.2](https://minecraft.wiki/w/Java_Edition_26.1.2)
- [Minecraft new version numbering system](https://www.minecraft.net/en-us/article/minecraft-new-version-numbering-system)
- [PaperMC Java install docs](https://docs.papermc.io/misc/java-install/)
- Re-DIVERSE_infrastructure (https://github.com/Re-DIVERSE/Re-DIVERSE_infrastructure)
