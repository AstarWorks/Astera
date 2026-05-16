---
status: Accepted
date: 2026-05-16
deciders: ryuzu
---

# ADR-0010: 生成 AI パイプラインは Phase 6 まで後回し

## Status

Accepted

## Context

Astera の README は「100% AI-developed Minecraft server」と謳い、テクスチャ・サウンド・3D モデル・ステージなどを AI 生成する構想がある。

初期計画書ドラフトでは Phase 1 から AI 生成を組み込んでいたが、検討の結果:

- 土台 (疎結合アーキテクチャ + k8s) が脆弱な状態で生成 AI を載せても、生成物を組み込めない
- 生成 AI 部分は API 利用コストと安全レビューが必要で、土台より優先度が低い
- Claude Code Multi-Agent 一本化方針 ([[adr/0004]] と整合) では、エンジン側は後付け可能

## Decision

Phase 1〜5 では**生成 AI を使わない**。Phase 6 で本格解禁する。

Phase 1〜5 で行うこと:
- `.claude/` ディレクトリ規約のみ確立
- 生成 AI なしで動くコンテンツ (バニラ素材 + 既存プラグイン素材) で MVP
- アセット投稿の手動導線を整える

Phase 6 で:
- texture / sound / model / stage の AI 生成 skill/agent 整備
- 自動レビュー + 安全性検証
- API コスト管理

## Consequences

### Positive
- 土台の品質に集中できる
- API コストが Phase 1〜5 で発生しない
- 生成 AI なしでも動く Astera が手元にできる (バックアップ計画)

### Negative / Trade-offs
- 「100% AI 開発」という対外メッセージとは部分的に矛盾する期間がある (Phase 1〜5)

### Mitigations
- Phase 1〜5 でも**コード生成・レビュー・ドキュメント自動更新**は Claude Code で実施 (これも AI 委託の一部)
- 対外発信では「Phase 6 で生成 AI 解禁」のロードマップを明示

## Alternatives Considered

- **Phase 1 から並行**: 土台が固まる前に生成物が積もり、後で全部書き直しになるリスク

## References

- 計画書 §1.3, §4
