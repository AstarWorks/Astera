---
status: Accepted
date: 2026-10-27
deciders: founder, Claude
supersedes: null
superseded_by: null
---

# ADR-0289: Norma levels unlock weapon rarity

## Status

Accepted. Supersedes the "Level gating" decision of ADR-0288 (per-binding `min_norma_level`: `*-s3` at Lv 2, `*-ego` at Lv 3).
The rest of ADR-0288 (no win by level, time-limit ranking, stars match-scoped) stands.

## Context

Founder 2026-10-27: 「ノルマLvで解放されるのは武器レアリティね」. The skill-kind gates of ADR-0288 hid parts of a weapon the
player already holds; the founder wants the level to decide which weapons are usable, ordered by the rarity every item already
declares (`rarity:` in `content/items`, enum `Rarity` COMMON..LEGENDARY).

## Decision

- `min_norma_level` is removed from `ItemSkillBinding`, the item YAML and its schema. Nothing is gated per binding.
- The ladder is content: `rarity_levels: "common:1,uncommon:2,rare:3,epic:4,legendary:5"` in `content/norma-layer/layer.yaml`
  (a mode may override it in `default_config`; a missing rarity keeps the default level).
- A weapon is usable once its holder's in-match Norma level is at least the level its rarity opens at. Every trigger of a
  weapon (attack and all skills, s3 and ego included) is refused below that; the input is swallowed, the weapon stays
  holdable. `HandleItemTriggerUseCase` asks the core port `IMatchLevelGate.denyIfLocked(player, rarity)` once per input; Match
  implements it. Outside a Norma match, or with no registration, nothing is gated.
- Feedback by rarity: the `norma-locked` presentation (deny sound + action bar "Lv N で <rarity> 解放"); the item lore shows a
  greyed "locked until Norma Lv N" line for gated weapons (`IMatchLevelGate.requiredLevel`); the level-up subtitle and private
  chat name the rarities it opened; the sidebar shows the next rarity above the current level.

## Consequences

- Level 1 players can only use common weapons; epic weapons need Lv 4 and legendary Lv 5. Anything handed out above a player's
  level (kits, shop purchases) is held but dead until the level is reached; balance of acquisition vs. level is a content follow-up.
- Game-sim: `NormaUnlockScenarioTest` covers an epic weapon refused at Lv 1-3 and cast at Lv 4, a common weapon at Lv 1, and
  no gate outside a match.
