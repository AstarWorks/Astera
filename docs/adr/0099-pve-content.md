---
status: Draft
date: 2026-05-21
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0081: PvE Content (Encounter Zones + Drop Tables + Boss Mechanics)

## Status

Draft

## Context

Wave 12a (ADR-0080) で foundation (Mob domain + IMobSpawnService + EncounterZone
domain + LootDrop domain) が landing する。Wave 12b はその上に **実コンテンツ**
を載せる:

- 1+ playable PvE zone (HSB Slayer / 原神 World Boss / OSRS PvM の Astera 版)
- Drop table (HSB Slayer Tier 1-5 風の rare drop)
- Boss mechanic primitive (phase transition / immunity window / mechanic AoE)
- Group raid lock (party 必須 / max 4-5 人)
- Death penalty rule (per-zone YAML で safe death / item drop on death を切替)

リサーチ根拠: `docs/competitive-analysis/01-hypixel-skyblock.md` Slayer Tier
設計 + `04-osrs.md` Boss rare drop 1/100〜1/3000 帯 (Astera population スケール
で 1/34025 は許容不可、上限 1/2000 程度から開始)。

## Decision

Wave 12b で以下を land する:

- Content YAML schema:
  - `content/encounters/<zone>.yaml` — EncounterZone (region + spawn rule +
    difficulty curve)
  - `content/loot-tables/<table>.yaml` — Drop table (item ref + drop chance)
  - `content/bosses/<boss>.yaml` — Boss mechanic phase definitions
- 1+ playable zone (例: `content/encounters/blazing-pit.yaml`) — Slayer-style
  individual content
- 1+ playable boss (例: `content/bosses/scorched-titan.yaml`) — group raid
  (party 4-5 人 lock)
- `BossPhase` sealed (PhaseTransition / ImmunityWindow / AoeAttack)
- `IPartyService` port (Wave 5e Party domain 拡張) で raid lock enforce
- `IDeathPenaltyPolicy` port + zone-scoped impl (SafeDeath / ItemDropOnDeath)
- Drop chance は **deterministic test** で reproducible (既存
  `IDamageRandomizer` pattern 流用)

確率帯: rare drop は **1/100 〜 1/2000** で zone 別に YAML 設定可。OSRS の
1/34025 は population スケール不適合のため不採用。

## Consequences

### Positive

- HSB Slayer / 原神 World Boss / OSRS PvM の retention 軸が Astera に開通
- Wave 12c (Refinement) の grind target が確保される (drop した item を厳選)
- Wave 12d (多軸 skill BossSlayer) の XP source

### Negative / Trade-offs

- Content YAML 量が増える (encounter / loot table / boss mechanic 3 系列)
- Drop balance 調整は data-driven (Wave 12g Analytics 必須)

### Mitigations

- Wave 12c (Refinement) と並列開発可、依存はしない (Refinement YAML は別 schema)
- Wave 12g Analytics で drop rate の actual / expected を Prometheus 計測

## Alternatives Considered

- **Boss は instance ベース (Dungeon)**: Wave 5d (World geometry) の追加負荷大、
  Phase 8 後半 (Wave 12b-2 分割) で検討。
- **Drop table を item にひも付け**: ADR-0073 item-centric model と矛盾しないが、
  HSB は zone-centric drop table を採用しており universal 武器設計と整合性高い。
- **OSRS 級 1/34025 drop**: 拒否 (Astera population で実現不可、frustration 一辺倒)

## References

- 関連 ADR: [[adr/0080-pve-mob-boss-foundation]] / [[adr/0073-item-centric-content-model-and-skill-binding]] / [[adr/0034-session-social-tutorial-enderchest-reward-bridge-inventory-snapshot]] (Party)
- 関連 docs: [[competitive-analysis/01-hypixel-skyblock]] / [[competitive-analysis/04-osrs]] / [[competitive-analysis/07-cross-cutting]] §1.1
