---
status: Draft
date: 2026-05-21
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0086: Player Engagement Dashboard

## Status

Draft

## Context

User 戦略意図:

> プレイヤーの分析基盤はある程度ほしいな。バランス調整的に

既存 plan は以下を計上済:
- Wave 7c — Balance analytics events (DamageDealt aggregation → balance.csv)
- Wave _ops-4 — Micrometer + Prometheus exporter
- Wave _ops-5 — Structured JSON logs (ECS)
- Wave _ops-6 — Grafana dashboards
- Wave 8b — REST API + WebSocket events (server-wide stats)

しかし **per-player drill-down** + **engagement metric 専用 dashboard** は
未計上。本 ADR は **横断 view** + **per-player insight** + **retention metric
標準セット** を追加する位置づけ。

リサーチ根拠: `docs/competitive-analysis/07-cross-cutting.md` §8
(プレイヤー分析基盤 + retention metric 標準セット)。HSB sky.shiiyu.moe / 原神
HoYoLAB profile / OSRS hiscore.runescape.com の Astera 版。

## Decision

Wave 12g で以下を land する:

### REST endpoint (Wave 8b 拡張)

```
GET /api/players/<id>/engagement
  → playtime / 各 skill 進捗 / spend / login streak / D1/D7/D30 retention bin
GET /api/balance/heatmap
  → DamageDealt aggregated by weapon × archetype × rarity (Wave 7c balance.csv の REST 化)
GET /api/retention
  → cohort 別 D1 / D7 / D30 retention rate (admin only)
GET /api/players/<id>/profile
  → public view (Wave 12e 連動、equipment + cosmetic visible)
```

### Web admin (Wave 8a 拡張)

- player search + drill-down (engagement detail view)
- cohort filter (signup date / first match / last seen)
- balance heatmap chart (weapon usage / win rate / damage distribution)

### Metric 標準セット

- **D1 / D7 / D30 retention** (cohort analysis)
- **DAU / MAU** (stickiness)
- **Time-to-second-session** (onboarding quality)
- **Per-skill engagement** (Wave 12d 多軸 skill 偏り検出)
- **Refinement attempt rate per player** (Wave 12c 厳選 grind engagement)
- **Cosmetic equip diversity** (Wave 8e + Wave 12e 承認欲求 surface 充足度)

### Prometheus / Grafana

- 新 Prometheus metric:
  - `astera_player_engagement_score{playerId}` (gauge)
  - `astera_balance_damage_dealt{weapon, archetype, rarity}` (counter)
  - `astera_retention_cohort{cohort, day}` (gauge)
- 新 Grafana dashboard: `deploy/grafana/engagement.json`
  - 5 panel: retention curve / DAU-MAU / per-skill heatmap / refinement attempt
    / cosmetic diversity
- 既存 Wave _ops-6 dashboards (player count / match throughput / error rate) と
  並列

### Privacy

- public view (`/api/players/<id>/profile`) は player opt-in 必須
  (Wave 5e PlayerSettings の `publicProfileEnabled` field 追加)
- `/api/retention` は admin only (Wave 5e permission key 必須)

## Consequences

### Positive

- data-driven balance 調整 (Wave 12d skill 偏り / Wave 12c refinement balance /
  Wave 12b drop balance を全て data で見える化)
- per-player insight (Wave 12e Power Index も dashboard に統合)
- HSB sky.shiiyu.moe 級の player engagement transparency

### Negative / Trade-offs

- REST endpoint 増加でセキュリティ surface 拡大 (rate limit / permission 厳格化)
- Prometheus per-player metric は cardinality 爆発リスク (player 数 × metric 数)

### Mitigations

- per-player metric は `astera_player_*` を集計 (top-N / percentile bucket) で
  cardinality 制御
- public profile は opt-in (default disable)
- Wave 8b token bucket rate limit を `/api/players/*` にも適用

## Alternatives Considered

- **Wave 7c balance.csv のみで完結**: 拒否 (CSV は ops のみ閲覧可能、player /
  community 視点の transparency が欠ける)
- **Wave _ops dashboard 拡充のみ**: 部分採用、Wave 12g はそれに加えて **player
  視点 view** を追加 (Web admin / Public profile)
- **3rd party analytics (Mixpanel / Amplitude)**: 拒否 (data sovereignty +
  cost + ADR-0010 AI delegation policy と整合性検討)

## References

- 関連 ADR: [[adr/0040-...]] (Wave 8b REST) / [[adr/0039-...]] (Wave 8a Web admin) / [[adr/0083-multi-skill-progression]] (Wave 12d skill metric) / [[adr/0082-item-refinement-layer]] (Wave 12c refinement metric) / [[adr/0084-flex-multi-surface]] (Wave 12e Power Index)
- 関連 docs: [[competitive-analysis/07-cross-cutting]] §8
- 関連: Wave 7c (balance.csv) / Wave _ops-4 (Prometheus) / Wave _ops-5 (Logs) / Wave _ops-6 (Grafana)
