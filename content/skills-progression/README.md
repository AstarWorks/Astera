# Multi-skill progression registry (Wave 5multi-skill / ADR-0101)

Each `*.yaml` file defines one progression axis: the XP curve and the level-gated perks.

## Schema

```yaml
skill: <skill_kind_token>     # one of: combat, boss_slayer, mining, fishing,
                              #         farming, refining, trading, exploration,
                              #         tournament, crafting
curve:                        # optional; default = SkillXpCurve.exponential()
  thresholds: [100, 224, ...] # explicit per-level cumulative XP thresholds
  # OR generate via formula:
  base: 100
  factor: 1.12
  max_level: 100
perks:                        # optional, sorted ascending by level
  - id: <kebab-case>
    level: <int >= 1>
    display_name_key: astera.progression.<axis>.<perk_id>   # optional i18n key
    effect:
      type: stat_multiplier   # or stat_additive | unlock_feature
      stat: damage            # for stat_*
      multiplier: 1.05        # for stat_multiplier (must be > 0)
      additive: 0.10          # for stat_additive
      feature_id: foo         # for unlock_feature
```

## Loading

`SkillProgressionContents` (`tools/architecture-test` enforces serialisation
annotations stay out of domain) reloads via `/astera admin reload skills-progression`
through the standard `IReloadableContentsRegistry` path.

## Phase 1 status

Perk *application* is deferred (see `GrantPerksOnSkillLevelUpListener` KDoc).
This wave lands the catalog + XP-gain + level-up events so Phase 2 can
attach the attribute-modifier applier without further schema churn.

Axes with no YAML file resolve to `SkillXpCurve.exponential()` defaults and
have no perks. The 10 axes are still defined in `SkillKind` so they can be
queried / iterated from day one.
