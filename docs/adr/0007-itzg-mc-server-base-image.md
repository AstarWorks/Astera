---
status: Accepted
date: 2026-05-16
deciders: ryuzu
---

# ADR-0007: Minecraft Server 基底イメージは itzg/minecraft-server

## Status

Accepted

## Context

RTM は自前 Dockerfile で paper.jar を `curl` ダウンロードする方式だった。シンプルだが:
- バージョン管理が脆い
- プラグイン配置が手作業
- JVM 最適化が自前

Re-DIVERSE_infrastructure は **`itzg/minecraft-server:java21`** と **`itzg/mc-proxy:latest`** を採用 (現時点では Paper 1.21.4 / JDK 21 用)。Aikar Flags、自動 EULA、プラグイン自動配置、バージョン指定など完備。

Astera は最新マイクラ (Java Edition 26.1.x / JDK 25 必須、[[adr/0003]] 参照) を採用するため、**`itzg/minecraft-server:java25`** タグまたは相当の最新タグを使う。

## Decision

- **Game サーバー**: `itzg/minecraft-server:java25` (または itzg 側で提供される最新 JDK 25 対応タグ) を base
- **Velocity proxy**: `itzg/mc-proxy:latest` を base
- Astera プラグイン jar は `initContainer` で `/data/plugins/` にコピー、または自前の `astera/minecraft-server:VERSION` を `itzg/minecraft-server:java25` から `FROM` して焼く
- Phase 1 着手時に itzg/docker-minecraft-server の最新 README で JDK 25 タグの正確な名前を確認

## Consequences

### Positive
- JVM チューニング (Aikar Flags) が自動
- バージョン指定が env だけ
- Re-DIVERSE と運用パターンを共有
- Velocity 連携も整備済み

### Negative / Trade-offs
- itzg のメンテナンスに依存
- イメージサイズが大きめ

### Mitigations
- itzg は活発にメンテされている (Bukkit/Paper/Forge/Fabric 対応の業界標準)
- イメージサイズは k8s 配信では問題にならない

## Alternatives Considered

- **自前 Dockerfile (RTM 流継続)**: バージョン追従と JVM チューニングを自前運用するコストが高い
- **公式 Paper Docker (なし)**: そもそも公式は無い

## References

- 計画書 §3.3
- https://github.com/itzg/docker-minecraft-server
- Re-DIVERSE_infrastructure `minecraft/servers/lobby/lobby-deployment.yaml`
