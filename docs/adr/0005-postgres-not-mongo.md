---
status: Accepted
date: 2026-05-16
deciders: ryuzu
---

# ADR-0005: 永続 DB は PostgreSQL (Mongo 廃止)

## Status

Accepted

## Context

RTM は **MongoDB (永続) + Redis (一時/キュー)** だった。Re-DIVERSE_infrastructure は **PostgreSQL** を使い、`playerdata_snapshot` のような時系列スナップショットを SQL で簡潔に書けている。

Astera で扱うデータ:
- プレイヤープロファイル / Loadout (構造化)
- 武器・スキル設定 (構造化、JSON で良い)
- マッチ履歴 / リーダーボード (時系列、集計頻繁)
- UGC マーケットアイテム (構造化 + 検索)
- 経済トランザクション (整合性必須)

これらは「整合性 + 集計 + 関係」のニーズが強く、Mongo の柔軟スキーマよりも Postgres の SQL + JSONB の方が後の困難が少ない。

## Decision

永続 DB は **PostgreSQL 17** に統一。半構造データは JSONB カラムで持つ。Redis は引き続き一時データと Pub/Sub に使う。

## Consequences

### Positive
- リーダーボード / 集計を素直な SQL で書ける
- 経済データの ACID トランザクションが明示的
- Re-DIVERSE と運用ノウハウを共有できる
- 周辺ツール (pgAdmin, pg_dump, Grafana datasource) が充実

### Negative / Trade-offs
- RTM の Mongo スキーマを Postgres に変換する必要がある
- 完全に非構造なデータには Mongo より書きづらい

### Mitigations
- 非構造データは JSONB に格納
- スキーマ migration は Flyway か Liquibase で管理 (詳細は Phase 1 着手時に決定)

## Alternatives Considered

- **Mongo 継続**: RTM 資産活用には楽だが、Re-DIVERSE の運用ノウハウや SQL の表現力を捨てるのは惜しい
- **SQLite**: 単一サーバーなら可、しかし複数 Pod から接続するのが面倒

## References

- 計画書 §3.2, §3.3
- Re-DIVERSE_infrastructure `platform/databases/postgresql/`
