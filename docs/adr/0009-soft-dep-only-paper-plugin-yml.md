---
status: Accepted
date: 2026-05-16
deciders: ryuzu
---

# ADR-0009: 外部プラグインは全て soft-dep

## Status

Accepted

## Context

RTM の `paper-plugin.yml` は ProtocolLib / PlaceholderAPI / FAWE / WorldGuard / Oraxen / MythicMobs / ModelEngine / UltimateAdvancementAPI / WeaponMechanics / Multiverse-Core / CommandAPI を**すべて required=true**で要求していた。

これは:
- どれか 1 つでも 1.21 非対応になると Astera 全体が起動できない
- バニラだけの test cluster でも全プラグインを揃える必要がある
- 「軽い構成で試したい」ができない

## Decision

`paper-plugin.yml` のすべての外部プラグイン依存を **soft-dep** に降格する。プラグインの有無は起動時に検出し、`adapter-providers/*` が機能フラグで動的に切り替える。

例:
- Oraxen 有り → `OraxenItemProvider`
- Oraxen 無し → `BukkitItemProvider` (バニラ実装)

## Consequences

### Positive
- バニラのみで起動可能 → test cluster や local dev が楽
- 外部プラグインの 1 つが壊れても Astera は起動できる
- MC バージョン追従時に「Oraxen がまだ最新 drop 非対応」でも進める
- Provider パターンによる拡張性

### Negative / Trade-offs
- バニラ代替実装を書く分のコードが増える
- 「Oraxen 専用機能」を露出させたい時に分岐が必要

### Mitigations
- バニラ代替は最小限の機能 (見た目だけバニラ素材で代替) で済ます
- Oraxen 専用機能は Oraxen Provider 経由でのみ呼ばれる API に隔離

## Alternatives Considered

- **hard-dep 維持 (RTM 流)**: 1 つ壊れたら全死。長期保守と矛盾
- **Provider なしで try/catch**: 散在し、テスト困難

## References

- 計画書 §3.1
- RTM `paper-plugin.yml`, `BukkitBlockProvider`/`OraxenBlockProvider`
