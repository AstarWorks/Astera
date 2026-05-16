---
status: Accepted
date: 2026-05-16
deciders: ryuzu
---

# ADR-0008: モノレポ + 非公開 submodule

## Status

Accepted

## Context

Astera は OSS 公開する。ただし運用機密 (k8s host, secrets, anti-cheat ルール) は非公開にしたい。

選択肢:
1. 全 public モノレポ (機密もダミー化して公開)
2. 全機能を別リポに分割 (Astera-Core / Astera-Infra / Astera-Web)
3. **モノレポ + 非公開 submodule** (本決定)

Claude Code 視点では「1 つのワーキングディレクトリに全てがある」が最も親切。リポジトリ間調整は AI agent の負担。

## Decision

- **`AstarWorks/Astera`** (public モノレポ): プラグイン本体 / k8s base manifest / docs / content
- **`AstarWorks/astera-private`** (非公開 submodule): 環境固有 overlay / secrets / anti-cheat / runbook 機密部分

`Astera/private/` に submodule で mount。OSS コントリビュータは public 部分のみで `docker compose up` できる。

## Consequences

### Positive
- Claude Code は単一作業ディレクトリで全てが見える
- OSS 公開と運用機密の両立
- 構造変更時のクロスリポ調整が最小

### Negative / Trade-offs
- submodule 運用の認知負荷 (`git submodule update` 等)
- private がないと完全な再現はできない (が、これは意図的)

### Mitigations
- `Makefile` か `setup.sh` で submodule 初期化を自動化
- private 不要で動くフォールバックを `docker compose up` に用意
- public 部分のみで CI が通るように

## Alternatives Considered

- **全 public**: 運用機密の管理が複雑になる
- **多リポ分割**: 整合性管理コストが高い、AI agent には親切でない

## References

- 計画書 §2
