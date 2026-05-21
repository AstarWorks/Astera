---
status: Accepted
date: 2026-05-21
deciders: Wave 5s
supersedes: null
superseded_by: null
---

# ADR-0091: Crafting Recipes + Quest Chains (YAML registry unified)

## Status

Accepted

## Context

Vanilla Minecraft ships a recipe system (datapack JSON, NBT round-trip,
`RecipeManager` lookup) and no native multi-stage quest system. Astera needs:

- **Custom recipes** that produce Astera-defined items (resolved via
  `IItemRegistry` to an `ItemSpec` — full i18n / attributes / model overrides
  / skill bindings per ADR-0073), gate behind player progression, and execute
  at Astera-defined crafting surfaces (Hand / Workbench / Furnace / Enchant
  Table). Vanilla cannot represent any of those four requirements directly.
- **Multi-stage progression** above what Wave 5p Custom Advancement (single
  unlock) and Norma (match-bounded objective) cover. A quest "rescue the
  merchant" needs sequential stages with auto-advance, per-stage rewards,
  and shared criteria vocabulary with the rest of the progression system so
  YAML authors don't relearn predicates for each domain.
- **Cross-server consistency**. Recipes live in `content/recipes/*.yaml` so
  all cluster pods agree after a deploy. Quest progress crosses proxy hops
  via Postgres rows (ADR-0068), so the per-player counter must be
  database-backed at priority 100, with an in-memory default at priority 0
  for tests + Postgres-less dev.
- **Unified YAML registry surface**. Wave _items-1 / ADR-0073 + Wave 5p /
  ADR-0088 established `content/<category>/*.yaml` + `ReloadableContents` +
  `/astera reload <category>` (ADR-0060 / ADR-0065) as the substrate.
  Wave 5s adds two more categories (`recipes`, `quests`) on top — no new
  substrate, no new dispatcher, no new bootstrap protocol.

Recipes and quest chains are tightly coupled at the YAML-author level:

- Recipe unlock criteria use the same [AdvancementCriteria] vocabulary Wave 5p
  shipped (`KillCount`, `CurrencyEarned`, `MatchesWon`, `ItemAcquired`,
  `AllOf`, `AnyOf`).
- Quest stage criteria + rewards reuse the same vocabulary
  ([AdvancementCriteria] + [AdvancementReward]). A YAML author who learns
  the advancement schema can write recipes and quests without retraining.
- Quest rewards fan out through the same `AdvancementRewardGranted` event
  channel — feature plugins (items / stats) handle both advancement and
  quest grants uniformly with no duplicate listener.

ADR-0022's sibling-isolation matrix means the shared vocabulary stays in
`plugin:core`. No feature-plugin imports are introduced.

## Decision

Ship two coordinated Wave 5s domains, both in `plugin:core`, both YAML-driven,
both reloadable via the existing `/astera reload <category>` dispatcher.

### α. Crafting recipes (`core.domain.recipe`)

- `CustomRecipe(id / outputItem / outputCount / ingredients / craftingStation? /
  unlockCriteria?)` — Vanilla recipe replacement.
- `RecipeIngredients` sealed: `Shaped` (2D pattern + char→ItemId key),
  `Shapeless` (multiset), `Conversion` (1-to-1 smelt/augment, with ratio).
- `CraftingStation` enum: `Hand` / `AsteraWorkbench` / `AsteraFurnace` /
  `AsteraEnchantTable`.
- `ICraftingRecipeRegistry` (Contents marker) + `RecipeContents`
  (`YamlDynamicContents<RecipeId, CustomRecipe, RecipeYamlConfig>`).
- `CraftRecipeUseCase` resolves a recipe, checks the unlock criteria via
  `IAdvancementService`, verifies the player's inventory snapshot, and
  publishes [RecipeCrafted].
- `CraftError` sealed: `UnknownRecipe` / `Locked` / `InsufficientIngredients` /
  `WrongStation` / `NoInventory` — each carries an i18n `MessageKey`.

Recipes are content-only (no per-player state); the inventory snapshot store
from Wave 5e is the authoritative ingredient counter in Phase 1.

### β. Quest chains (`core.domain.quest`)

- `QuestChain(id / titleKey / stages)` — multi-stage progression.
- `QuestStage(id / titleKey / descriptionKey / criteria / rewards /
  followingStageId?)` — gates on [AdvancementCriteria], rewards are
  [AdvancementReward], `followingStageId == null` marks the terminal stage.
- `PlayerQuestProgress(playerId / chainId / currentStageId / stageProgress /
  completedAt?)` — one row per (player, chain), persisted to V27
  `player_quest_progress`.
- `IQuestChainRegistry` (Contents marker) + `QuestChainContents`.
- `IQuestService` (inbound port): `startChain` / `progressCurrent` /
  `activeChains` / `allChains`. Implemented by `ProgressQuestUseCase` +
  `StartQuestChainUseCase`.
- `GrantQuestRewardUseCase` mirrors `GrantAdvancementRewardUseCase`'s
  two-channel dispatch (currency synchronous via `ICurrencyLedger`,
  item / title / achievement fan-out via `AdvancementRewardGranted` events
  with a synthetic `quest-<chainId>-<stageId>` advancement-id).
- `IPlayerQuestProgressRepository` (outbound port): in-memory default at
  priority 0 (`AsteraCorePlugin`), Postgres overlay at priority 100
  (`AsteraPersistencePlugin`) backed by V27.
- New events: [QuestChainStarted], [QuestStageCompleted], [QuestChainCompleted].

### Shared decisions

- **Synthetic advancement id for quest rewards**. The
  `AdvancementRewardGranted` event payload requires an `AdvancementId`.
  Quest-driven rewards reuse the channel with the synthetic id
  `quest-<chainId>-<stageId>` (lower-kebab-case so `AdvancementId`'s
  validation passes) — subscribers can pattern-match on the prefix to
  disambiguate without touching the event schema.
- **Dominant-leaf HUD scalar for composite quest criteria**. `AllOf` uses
  the max-target leaf; `AnyOf` uses the min-target leaf. Mirrors
  `ProgressAdvancementUseCase`'s HUD simplification — composite quests get
  consistent numbers in both surfaces without per-leaf sub-rows in Phase 1.
- **No retroactive composite per-leaf rows**. Composite quest criteria
  collapse to a single scalar `stageProgress` in `player_quest_progress`.
  If composite quests prove common we will revisit and adopt the per-leaf
  sub-row pattern from `ProgressAdvancementUseCase` (Phase 4).
- **Phase 1 PoC defers inventory mutation**. `CraftRecipeUseCase` returns
  `CraftResult.consumed` but does not mutate the player's live Bukkit
  inventory — Wave 6a wires an `IInventoryWriter` Paper adapter that
  subscribes to `RecipeCrafted` and performs the actual `ItemStack` work.
- **Phase 1 PoC unlock oracle**. Recipes with `unlockCriteria` are treated
  as unlocked iff the player has completed any advancement at all. Trades
  precision for simplicity — Wave 6a replaces this with a dedicated
  `IAdvancementService.evaluate(criteria)` once that surface exists.

## Consequences

### Positive

- **Two more progression surfaces** without any new substrate or dispatcher.
- **YAML authors reuse one criteria + reward vocabulary** across advancements,
  quests, and recipe unlocks.
- **Cross-server quest state** preserved via V27 + ADR-0068 contract.
- **Konsist-enforced purity**: `core.domain.recipe.*` and `core.domain.quest.*`
  cannot regress to Bukkit / persistence / serialization imports (new
  dedicated test on top of the existing `core domain must not depend on any
  platform or framework` rule).
- **Pre-release-friendly**. No `@Deprecated` aliases, no fallback shims,
  no migration period. Quest progress shape can evolve freely until Phase 1
  PoC closes.

### Negative / Trade-offs

- **Synthetic advancement-id leakage**. The reuse of
  `AdvancementRewardGranted` for quest rewards means subscribers may see
  events with `advancementId.value` of the form `quest-<chainId>-<stageId>`
  that do not exist in the `IAdvancementRegistry`. Subscribers must tolerate
  unresolvable ids — documented in the use-case KDoc.
- **Composite-criteria HUD imprecision**. Phase 1 collapses to a scalar
  dominant-leaf target; players whose `AllOf` quest has split progress will
  see a single number that lags reality.
- **Recipe unlock oracle is too permissive in Phase 1**. Any completed
  advancement unlocks every recipe — acceptable for the small PoC content
  set, will need fixing before Phase 2 expands the catalog.

### Mitigations

- The dispatch contract (synthetic id + same channel) is documented in
  `GrantQuestRewardUseCase` KDoc and on each new domain event.
- The composite-criteria simplification is called out explicitly in
  `ProgressQuestUseCase.dominantTarget` and in this ADR.
- The unlock-oracle stub is gated behind a `Wave 6a TODO` marker so the
  reviewer's grep catches it before Phase 2.

## Alternatives Considered

- **Reuse `ProgressAdvancementUseCase` for quest stages**. Rejected: would
  require multi-tenant `AdvancementId` collision avoidance and would muddy
  the advancement use case's contract with stage-transition logic that no
  other consumer needs.
- **Per-leaf sub-rows for composite quest criteria**. Rejected for Phase 1:
  the V27 schema would have to mirror V26's `<canonical>-l<index>` synthetic
  key, doubling storage on what is still a hypothetical use case. Quests
  authored in Phase 1 are flat scalar criteria; if composite becomes the
  norm Phase 4 can revisit without breaking the table.
- **Drop `CraftingStation` and treat every recipe as Hand**. Rejected: the
  station tag is part of the YAML wire format from day one so existing
  recipes don't need to be rewritten when Wave 6a wires the workbench GUI.
  The cost is one nullable field in the domain.
- **Reuse Wave 5p `GrantAdvancementRewardUseCase` for quest rewards via
  a polymorphic source parameter**. Rejected: would force advancement-only
  callers to thread an irrelevant source tag and would couple two use cases
  that today share zero state. Two small parallel use cases are cheaper to
  maintain.
- **Adopt vanilla Minecraft recipes via Paper's `RecipeManager`**. Rejected
  per ADR-0073's rationale: vanilla output is an `ItemStack` with no
  Astera-attribute / model / skill-binding hooks; the recipe system is
  inseparable from the item system here.

## References

- [ADR-0017](0017-adopt-datadriven-contents-pattern.md) — Data-driven contents.
- [ADR-0060](0060-datadriven-contents-substrate-partial-pull-forward.md) —
  `Contents` + `YamlDynamicContents` substrate.
- [ADR-0065](0065-per-category-reload-dispatcher.md) — `/astera reload <category>`
  dispatcher.
- [ADR-0068](0068-cross-server-persistence-boundary.md) — Cross-server
  persistence contract; V27 quest progress sits inside this boundary.
- [ADR-0073](0073-item-centric-content-model-and-skill-binding.md) — Item-first
  content model; recipe outputs are resolved through `IItemRegistry`.
- [ADR-0088](0088-custom-advancement-npc-dialog-tutorial-sequencer-yaml-registry.md) —
  Wave 5p Custom Advancement; recipe `unlockCriteria` + quest stage
  `criteria` reuse [AdvancementCriteria], rewards reuse [AdvancementReward].
- `plugin/core/src/main/kotlin/com/astarworks/astera/core/domain/recipe/` —
  Recipe domain.
- `plugin/core/src/main/kotlin/com/astarworks/astera/core/domain/quest/` —
  Quest domain.
- `plugin/persistence/src/main/resources/db/migration/V27__player_quest_progress.sql` —
  Quest progress migration.
- `tools/architecture-test/src/test/kotlin/com/astarworks/astera/architecture/ArchitectureTest.kt` —
  `recipe and quest domains must stay pure (Wave 5s, ADR-0091)` Konsist rule.
