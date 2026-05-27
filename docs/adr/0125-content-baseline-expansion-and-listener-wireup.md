---
status: Accepted
date: 2026-05-27
deciders: orchestrator (Wave H 5content-depth)
supersedes: null
superseded_by: null
---

# ADR-0125: Content baseline expansion + Quest/Recipe/Achievement listener wire-up

## Status

Accepted

## Context

Round 11 closed Wave G (game-system completion / admin ops / server
hardening). Two Phase 1 PoC gaps remained:

1. **Sample-content thinness.** The Wave 5s (recipes / quests) +
   Wave 5p (advancements) + Wave 5cinematic (music) + Wave 5pve (mobs) +
   Wave 5e (tutorials) waves each landed with a single canonical sample
   (e.g. `recipes/iron-sword.yaml` only, `quests/tutorial-chain.yaml`
   only, `advancements/first-kill.yaml` + `master-of-blades.yaml`). The
   PoC demo loops needed at least ~5 entries per category so authors
   could read the schema by example rather than chase the parser.
2. **Silent-drop events.** `QuestStageCompleted` (ADR-0091),
   `RecipeCrafted` (ADR-0091), and `AchievementUnlocked` (Wave 5e)
   were all published by their respective use cases — and the only
   subscriber in the tree was `RefreshNameplateOnAchievementUnlockedListener`
   (Wave 5flex / ADR-0102, badge-projection concern). Stage transitions,
   crafts, and achievement unlocks were invisible to the player.

CLAUDE.md §Pre-release posture frames this as the right moment to close
the gap: content YAML is freely additive (no schema break) and the
listeners are pure additions to the in-process `IDomainEventBus` fan-out
(no API surface change).

## Decision

Land both gaps in a single wave:

- **30 new content YAMLs** across 10 categories, each lifting the
  category's file count to ≥3 (target: 5+ where the existing samples
  already approached that bar). New files: skills (+3: flame-burst,
  frost-shard, lightning-bolt), mobs (+3: dark-zombie, cave-spider,
  nether-ghast), stages (+2: forest-arena, boss-arena), quests (+2:
  forest-exploration, item-collection), recipes (+3: iron-armor-set
  representative iron-helmet entry, diamond-pickaxe, golden-apple),
  advancements (+3: first-mob-kill, kills-100, boss-defeat), shops (+2:
  iron-merchant, rare-merchant), music (+2: boss-theme, victory-fanfare),
  status (+3: burn, freeze, poison), tutorials (+2: combat-tutorial,
  skill-tutorial).
- **3 new listeners**:
  - `UpdateQuestProgressOnQuestStageCompletedListener` in
    `:plugin:core` (`application/eventbus/listeners/quest/`), subscribes
    to the core-domain `QuestStageCompleted` event and pushes an
    ActionBar HUD stub through `IHudGateway` keyed by whether the
    chain is now complete.
  - `NotifyOnRecipeCraftedListener` in
    `:plugin:core` (`application/eventbus/listeners/recipe/`), subscribes
    to `RecipeCrafted` and sends a chat confirmation via
    `IPlayerGateway.sendMessage` + `IMessageRenderer.render` plus a
    Subtitle HUD stub.
  - `NotifyOnAchievementUnlockedListener` in
    `:plugin:stats` (`application/eventbus/listeners/`), subscribes
    to the stats-domain `AchievementUnlocked` event and surfaces the
    same chat + Subtitle pair. Lives in `:plugin:stats` because
    ADR-0022 sibling-isolation forbids `:plugin:core` from importing
    `:plugin:stats.domain.event`. Parallel to the existing
    `RefreshNameplateOnAchievementUnlockedListener` — separate concern,
    independent subscriber.
- **3 listener tests** under each owning plugin's `src/test/`.
- **i18n keys** added in parallel to `content/languages/{en,ja}.yaml`:
  - `astera.advancement.{first_mob_kill,100_kills,boss_defeat}.{title,description}`
  - `astera.quest.{forest-exploration,item-collection}.*.{title,description}`
  - `astera.quest.progress.{stage-completed,chain-completed}` (listener)
  - `astera.recipe.crafted.{chat,subtitle}` (listener)
  - `astera.achievement.unlocked.{chat,subtitle}` (listener, new top-level namespace)
  - `astera.tutorial.{combat_tutorial,skill_tutorial}.title` + per-step keys

No new Konsist rule. Konsist count unchanged at 53.

## Consequences

### Positive

- Authors can read each category by ≥3 worked examples instead of one
  prototype, which matches the "show, don't tell" bar `content/*/README.md`
  references but never met for half the categories.
- Phase 1 PoC demos can call out actual quest progress / craft / unlock
  on screen instead of demonstrating the event-bus flow through a
  `logger.info` line.
- Existing nameplate listener stays untouched — the achievement-unlocked
  surface now has two narrow subscribers (badge projection + player
  notification) instead of one mixed-concern handler.
- Pure additive: no field rename, no schema rewrite, no
  `@Deprecated` shim.

### Negative / Trade-offs

- Some new content references items / loot tables that don't exist yet
  (e.g. `iron-helmet`, `diamond-pickaxe`, `astera:dark-zombie-drops`).
  Recipe / mob / loot-table loaders accept dangling refs at load time
  by design — the gap surfaces at execution time, which is acceptable
  for PoC content.
- Three categories (status, stage, shop) still ship without per-file
  i18n display keys in `content/languages/`. This matches the existing
  convention (Wave 5p / 5s / 5wireup all left those un-resolved, and
  the renderer's missing-key fallback is robust). Future tightening
  via a referential-integrity test is left to a later wave.

### Mitigations

- Each new content file carries a `# Wave H 5content-depth (ADR-0125)`
  header so the lineage is greppable.
- Listener placement is documented in the KDoc of each class plus
  ADR-0022 ↔ ADR-0125 cross-link below (the achievement listener
  cannot move to `:plugin:core` without violating sibling isolation).

## Alternatives Considered

- **Move `AchievementUnlocked` to `:plugin:core.domain.event`.** Would
  have let all 3 listeners live in `:plugin:core/application/eventbus/listeners/`
  uniformly. Rejected: the event references `AchievementId`, which is
  already in core, but the publisher (`UnlockAchievementUseCase`) and
  every existing subscriber (badge refresh, telemetry) live in stats.
  Moving the event would split a feature surface across two plugins
  for symmetry alone — ADR-0022 sibling-isolation was designed exactly
  to avoid that.
- **Inline display keys in each new YAML file (status / stage / shop).**
  Rejected for this wave: the existing samples never added them, the
  renderer falls back gracefully on missing keys, and inflating
  `content/languages/{en,ja}.yaml` with another ~40 stub keys this
  round is busywork. Add a follow-up wave + a Konsist referential-
  integrity rule when that becomes the bottleneck.
- **Single combined "NotifyOnX" listener.** Rejected: each event lives
  in a different plugin (quest + recipe in core, achievement in stats),
  and the surfaces differ (Quest uses ActionBar; Recipe + Achievement
  use Subtitle + chat). One class per concern is cleaner.

## References

- 関連 ADR: [[adr/0091-craft-recipe-and-quest-chain]] (event publishers),
  [[adr/0088-advancement-dialog-tutorial-sequencer]] (advancement domain),
  [[adr/0097-hud-glyph-renderer-and-resource-pack]] (IHudGateway),
  [[adr/0102-nameplate-flex-surface]] (existing achievement listener),
  [[adr/0022-multi-plugin-paper-layout]] (sibling-isolation),
  [[adr/0116-e2e-gameplay-loop-wireup]] (HUD listener wiring pattern).
- 関連 docs: [[architecture/principles]], `content/*/README.md`,
  `docs/IMPLEMENTATION-ROADMAP.md` §Round 12.
