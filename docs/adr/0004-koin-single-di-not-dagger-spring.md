---
status: Accepted
date: 2026-05-16
deciders: ryuzu
---

# ADR-0004: DI は Koin (KSP) 単一

## Status

Accepted

## Context

RTM は **Dagger 2 + Koin + Spring Boot** の 3 重 DI で構成されていた。これは:

- 起動が遅い (Paper サーバーで Spring を立てるオーバーヘッド)
- 依存解決の起点が複数あり、debug が困難
- KSP / annotation processor が複数走るためビルドが遅い
- 学習コストが高い

## Decision

Astera では **Koin (KSP annotation) を単一の DI とする**。Dagger と Spring は使わない。

## Consequences

### Positive
- 起動が高速 (Spring 起動の数秒が消える)
- 依存解決の起点が 1 つ。debug が楽
- ビルドが速い
- Kotlin idiomatic

### Negative / Trade-offs
- Spring の `@Transactional` 等の便利機能は自前で書く必要がある
- Dagger の compile-time DI 検証は失われる (Koin は runtime 検証)

### Mitigations
- 永続化は jOOQ or Exposed で `@Transactional` 不要に
- 起動時に Koin の module を verify するテストを書き、runtime 失敗を防ぐ

## Alternatives Considered

- **Dagger 単一**: compile-time 検証は強いが Kotlin との相性 (KSP) でも boilerplate 多
- **Spring Boot 単一**: フル機能だが Paper プラグインには重すぎる
- **Manual DI**: 単純だが module 結線の見通しが悪化

## References

- 計画書 §3.2
- RTM `RyuZUTechnicalMagic.kt` (DI 3 重の参考)
