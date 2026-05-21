---
status: Accepted
date: 2026-05-21
deciders: Wave 5p
supersedes: null
superseded_by: null
---

# ADR-0088: Custom Advancement + NPC Dialog Tree + Tutorial Sequencer (YAML registry unified)

## Status

Accepted

## Context

Vanilla Minecraft ships an "advancements" system (datapack JSON, NBT round-trip,
per-world scope) and bespoke dialogs are typically per-plugin one-offs (Citizens
NPC trait classes, custom chat handlers). Astera's content surface requires:

- **Cross-server progression**. Wave _persist-1 / ADR-0068 commits to
  preserving player state across proxy hops. Vanilla advancements stay
  world-local; we need our own row store.
- **Astera-specific signals**. Criteria like `CurrencyEarned(GOLD, 100)` /
  `MatchesWon(5)` have no Vanilla equivalent — they reference Astera domain
  events (`CurrencyChanged`, `MatchEnded`).
- **Composable rewards** that dispatch into Astera service ports
  (`ICurrencyLedger`, `IItemDelivery`, `IAchievementRepository`) rather than
  Vanilla's `recipes` / `function` callouts.
- **NPC dialog trees** that span multiple steps with branching, intent
  emission (open a shop, queue for matchmaking), and predicate gating
  ("if you have the key, …, else …"). Wave 5g shipped a thin shop-only
  `IShopVendor`; Wave 5l (ADR-0082) generalised to
  `INpcInteractionRouter` (click→intent), but a one-shot click→intent map
  cannot express a stateful conversation.
- **Tutorial sequencer**. Wave 5e shipped a basic `TutorialStep` runner with
  4 variants and in-memory state. To complete the onboarding UX we need
  step types like `ShowDialog` (chain into the NPC dialog domain),
  `AwaitInteraction` / `AwaitMovement` / `AwaitSkillCast`, plus a typed
  `GrantItem` and an `AwaitPredicate` with shared predicate vocabulary
  from the dialog system. State must persist across restart + proxy hop.
- **Unified YAML registry surface**. Wave _items-1 / ADR-0073 + Wave 5e
  established `content/<category>/*.yaml` + `ReloadableContents` +
  `/astera reload <category>` (ADR-0060 / ADR-0065) as the substrate.
  Wave 5p adds three more categories on top — no new substrate.

The four pieces are tightly coupled — dialogs reference advancements
(`HasAdvancement` predicate); tutorials reference dialogs (`ShowDialog`
step); advancement rewards may include items, titles, achievements that
sit across plugin boundaries. ADR-0022's sibling-isolation matrix means
the shared vocabulary has to live in `plugin:core`, not bleed into
feature plugins.

## Decision

Ship three coordinated Wave 5p domains, all in `plugin:core`, all
YAML-driven, all reloadable via the existing `/astera reload <category>`
dispatcher:

### α. Custom Advancement (`core.domain.advancement`)

- `CustomAdvancement` (id / titleKey / descriptionKey / iconItemId /
  criteria / rewards / parent / frame / hidden) — Vanilla replacement.
- `AdvancementCriteria` sealed: `KillCount`, `CurrencyEarned`,
  `MatchesWon`, `ItemAcquired`, `AllOf`, `AnyOf` (composable tree).
- `AdvancementReward` sealed: `GrantCurrency`, `GrantItem`, `GrantTitle`,
  `GrantAchievement`. Item / title / achievement reward types live in
  core (`AchievementId` / `TitleId` relocated from stats; `ItemId` already
  in core).
- `AdvancementFrame` enum: TASK / GOAL / CHALLENGE.
- `IAdvancementService` (inbound port) + `IAdvancementRegistry` (Contents)
  + `IPlayerAdvancementProgressRepository` (in-memory default at
  `priority=0`, Postgres overlay at `priority=100` via V26 migration).
- `ProgressAdvancementUseCase` evaluates composite criteria via per-leaf
  sub-rows; on completion publishes `AdvancementCompleted` and dispatches
  `GrantAdvancementRewardUseCase` (currency credited synchronously via
  lazy `ICurrencyLedger`; other reward types fan out via
  `AdvancementRewardGranted` events for feature-plugin subscribers).

### β. NPC Dialog Tree (`core.domain.dialog`)

- `NpcDialogTree` (id / rootNodeId / nodes map) + `DialogNode` sealed:
  `Text`, `Choice` (with `DialogChoice` options), `Condition` (predicate
  branching, walked transparently), `Intent` (dispatches `GuiIntent` via
  Wave 5e router), `End` (with `DialogEndReason`).
- `DialogPredicate` sealed: `HasItem`, `HasCurrency`, `HasAdvancement`,
  `PermissionCheck`. Evaluated by `IDialogPredicateEvaluator` (default
  composes core-side outbound services).
- `INpcDialogService` (inbound port) + `INpcDialogRegistry` (Contents) +
  `INpcDialogBinding` (`EntityHandle → DialogTreeId` map; separate from
  Wave 5l's `INpcInteractionRouter` because dialog is stateful where
  intent dispatch is one-shot).
- `RunNpcDialogUseCase` walks the tree, transparently consumes Condition
  nodes, dispatches Intent nodes through `IGuiIntentRouter`, publishes
  `NpcDialogNodeEntered` / `NpcDialogEnded`. Cycle-cap guard prevents
  pathological recursion.

### γ. Tutorial Sequencer (extension of `core.domain.tutorial`)

- `TutorialStep` extended with Wave 5p variants: `ShowDialog`,
  `AwaitInteraction`, `AwaitMovement`, `AwaitSkillCast`, `GrantItem`
  (typed), `AwaitPredicate` (taking a `TutorialPredicate` mirroring
  `DialogPredicate`'s vocabulary as a parallel sealed hierarchy — see
  rationale in `TutorialPredicate` KDoc).
- `ITutorialSequencerService` (inbound port) +
  `IPlayerTutorialStateRepository` (in-memory default, Postgres-overlay-
  capable). The sequencer co-exists with Wave 5e's `RunTutorialUseCase`
  (which keeps using in-memory state for the legacy 4-variant flow);
  Wave 6a will unify.
- `RunTutorialSequencerUseCase` consumes the existing `TutorialContents`
  registry. `AwaitPredicate` auto-advances when the predicate is already
  true (Condition-node analog), with a cap to prevent runaway loops.

### δ. Cross-plugin progress fan-out

`TrackAdvancementProgressListener` lives in `plugin:stats` (the deepest
plugin per the sibling-isolation matrix that can see Combat + Match +
Core events): subscribes `PlayerKilled` / `CurrencyChanged` / `MatchEnded`
/ `ItemAcquired` and calls `IAdvancementService.progress(...)` for
matching criteria.

### Cross-cutting decisions

1. **`AchievementId` / `TitleId` relocated from `plugin:stats` to
   `plugin:core`** so `AdvancementReward.GrantAchievement` / `GrantTitle`
   can reference them without core depending on stats (forward-only rename
   per Phase 1 PoC posture).
2. **`CurrencyChanged` relocated from `plugin:economy` to `plugin:core`**
   so the stats-side advancement listener can subscribe without economy
   becoming a Stats dep.
3. **`ItemAcquired`** is a **new** `core.domain.event` published by
   `GiveItemUseCase` (and by Wave 6 inventory pickup listeners) so
   advancement progress + tutorial sequencer await steps can react.
4. **Lazy ledger resolution**. `GrantAdvancementRewardUseCase` and
   `DefaultDialogPredicateEvaluator` take a `() -> ICurrencyLedger?`
   provider instead of the eager instance, because economy plugin loads
   after core; missing ledger gracefully degrades to "skip credit" /
   "predicate=false" (no exception).
5. **V26 migration** adds `player_advancement_progress (player_id,
   advancement_id, current_count, target_count, completed_at, updated_at)`.
   Dialog + tutorial in-flight state are intentionally **not** persisted in
   V26 — dropped sessions restart cleanly on next interaction; revisit if
   mid-dialog proxy hops become common.

## Consequences

### Positive

- Single substrate (`YamlDynamicContents` + `ReloadableContents` +
  `/astera reload <category>`) covers four categories
  (`advancements` / `dialogs` / `tutorials` already + Wave 5e). Operator
  UX is one command per content type.
- Vanilla advancement is replaced wholesale — Astera owns the schema,
  storage, criteria, rewards. No NBT round-trip with Mojang's format;
  Phase 4 UGC marketplace can later swap in a DB-backed `IAdvancementRegistry`
  without touching use cases.
- NPC dialog is a separate concern from intent dispatch (Wave 5l/5g
  `INpcInteractionRouter` stays). One NPC can have both bound — the
  Paper-side listener (Wave 6b) tries dialog first, then intent
  fallback — clean layering.
- Tutorial sequencer uses the same predicate vocabulary as dialogs,
  reducing cognitive load for content authors. The state repository
  port enables cross-server resume in Phase 4 with zero use-case changes.
- Persistence overlay pattern preserved: in-memory default at
  `priority=0` in core, Postgres at `priority=100` in persistence; env-
  var-less servers work out of the box.
- Konsist suite stays green (no rule relaxed). i18n parity holds.

### Negative / Trade-offs

- **Two parallel predicate hierarchies**. `DialogPredicate` and
  `TutorialPredicate` have identical leaf variants in Wave 5p; the
  sequencer lifts one to the other in a one-line adapter. The duplication
  costs ~80 lines of YAML DTO + sealed hierarchy. The win is independent
  evolution if either side adds a unique variant; the loss is one more
  place to update when a leaf is added to both. We accept the duplication
  as cheaper than the abstraction.
- **Two tutorial runners**. Wave 5e's `RunTutorialUseCase` (4 step
  variants, in-memory) and Wave 5p's `RunTutorialSequencerUseCase` (10
  variants, persisted state) coexist. They share the `TutorialContents`
  registry but answer different `ITutorialRegistry` / `ITutorialSequencerService`
  ports. Wave 6a will unify into a single runner; until then admins
  decide which is wired in core. Net: more code in flight during the
  transition.
- **Per-leaf sub-rows for composite criteria**. To evaluate `AllOf` /
  `AnyOf` faithfully across restarts, each leaf has its own per-(player,
  advancement) row keyed `<canonical>-l<index>`. The canonical row gets
  the latch on completion. This doubles row count for any composite-
  criteria advancement; mitigated by the `IAdvancementService.completed(playerId)`
  filter that hides the synthetic ids from the HUD.
- **Tutorial sequencer auto-advance loop** can in theory iterate as many
  times as the script has steps. Cap is `steps.size + 4`. A
  pathologically-authored script with `AwaitPredicate(true)` × N could
  briefly hold the tick; in practice no real script does this and the
  cap catches it.
- **CurrencyChanged + AchievementId / TitleId relocations** are breaking
  changes for sibling-wave worktrees. Per Phase 1 PoC posture, no shim
  is provided; sibling waves rebase and update imports. The volume is
  small (~12 import lines across the codebase).

### Mitigations

- A future Wave 6a unification of the two tutorial runners is on the
  roadmap; this ADR will be revisited then (likely a `Superseded` flip
  with a 0089+ ADR).
- The dialog→tutorial predicate adapter is the single point that needs
  updating if either side diverges; the parallel hierarchies document
  the policy in their KDoc.
- The relocated events / IDs document the Wave 5p relocation reason in
  their KDoc so a future reader doesn't try to "fix" the package layout.

## Alternatives Considered

- **Use Vanilla Minecraft advancements directly**. Rejected — no
  cross-server preservation (ADR-0068), no Astera-specific criteria,
  fights Mojang's NBT round-trip semantics.
- **Layer dialog on top of `INpcInteractionRouter`**. Rejected — would
  conflate stateful conversation with one-shot intent dispatch and force
  the router's per-click model onto multi-step flows. Two services with
  clear single responsibilities is cleaner.
- **Single unified `Predicate` sealed**. Rejected for the reasons above —
  the cost of duplicate variants is lower than the cost of forcing one
  shape on two surfaces that may diverge.
- **Persist dialog + tutorial in-flight state in V26 too**. Rejected for
  Phase 1 PoC — UX of "your dialog restarted" is acceptable; the
  complexity of restoring an in-flight runner across restart isn't
  justified yet. `IPlayerTutorialStateRepository` is the forward port if
  Phase 4 needs it.
- **Single tutorial runner, breaking Wave 5e's existing in-memory
  variant**. Rejected — Wave 5p ships clean alongside Wave 5e to keep
  the existing tests + YAMLs working; the unify happens in Wave 6a once
  every site has migrated.
- **Listener in economy plugin** (so it could see `CurrencyChanged`
  natively). Rejected — economy doesn't have `combat.domain.event.PlayerKilled`
  as a compile dep, so stats remains the right deepest-plugin home. The
  relocation of `CurrencyChanged` to core is the cleaner fix.

## References

- 関連 ADR:
  - [[adr/0022-multi-plugin-portable-architecture]] — sibling-isolation matrix
  - [[adr/0060-datadriven-contents-substrate-partial-pull-forward]] — YAML registry substrate
  - [[adr/0065-per-category-reload-dispatcher]] — `/astera reload <category>` surface
  - [[adr/0068-cross-server-persistence-boundary]] — proxy-hop persistence policy
  - [[adr/0073-item-centric-content-model-and-skill-binding]] — first-class item model
  - [[adr/0082-entity-spawning-and-npc-two-tier]] — Wave 5l NPC + `EntityHandle`
- 関連 docs:
  - `docs/IMPLEMENTATION-ROADMAP.md` — Wave 5p row
  - `docs/RTM-PARITY-TRACKER.md` — advancement / dialog / tutorial rows
  - `plugin/persistence/src/main/resources/db/migration/V26__advancements_dialog_tutorial.sql`
