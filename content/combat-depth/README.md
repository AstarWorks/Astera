# content/combat-depth/

Wave 5combat-depth (ADR-0105) content sinks.

## Layout

### `combos/`

YAML-authored [`ComboRoute`](../../plugin/combat/src/main/kotlin/com/astarworks/astera/combat/domain/combo/ComboRoute.kt)
entries. Loaded at boot by `ComboRouteContents` and forwarded into
`IComboTracker`. See `example-sword-finisher.yaml` for the schema.

Per-file shape:

| field                     | required | meaning                                         |
|---------------------------|----------|-------------------------------------------------|
| `id`                      | yes      | lower-kebab `ComboRouteId`                      |
| `weapon_id`               | yes      | `ItemId` the combo binds to                     |
| `pattern.type`            | yes      | `hit_count` / `skill_sequence` / `timing_window`|
| `finisher`                | yes      | `SkillId` cast on combo completion              |
| `bonus_damage_multiplier` | no (1.0) | multiplier on the next strike (cap: 5.0)        |

### `boss-extensions/`

Forward-looking sample bosses exercising the new
[`BossPhaseTransition`](../../plugin/core/src/main/kotlin/com/astarworks/astera/core/domain/mob/boss/BossPhaseTransition.kt)
variants (`TimerExpired`, `MultiPlayerThreshold`, `MechanicCompleted`,
`AllOf`). **NOT loaded by the Phase 1 lifecycle service** — they live
here as the YAML schema reference. Copy into `content/pve/bosses/`
once Wave 6b teaches `IBossLifecycleService` to evaluate the new
triggers via `BossPhaseTransitionEvaluator`.

## Conventions

- Lower-kebab-case ids (a-z, 0-9, `-`). MatchId / ItemId / SkillId /
  ComboRouteId all enforce this at construction.
- One file per entry; the filename is documentation only — the `id`
  field is authoritative.
- No persistence — combo / focus-fire / target-lock state is
  session-scoped (ADR-0105 §"No persistence for trackers").
