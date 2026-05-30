---
status: Accepted
date: 2026-05-30
deciders: orchestrator (Wave M 5polish-quickwin)
supersedes: null
superseded_by: null
---

# ADR-0130: Polish quick wins (friend tile / achievement viewer / hotbar bind / tooltip providers / population HUD)

## Status

Accepted

## Context

Round 14 closed Wave I (match listener wire-up fix) and Wave J
(server-content ops). A final pre-PoC audit surfaced five **polish quick
wins** — each individually small, but each filling a gap that made the
server feel half-finished even though the substrate had landed:

1. **Friend tile binding had no resolver.** Wave 5lobby-content shipped
   `FriendsListGuiSpec` (Phase 1 skeleton) and Wave 5flex extended the
   `IGuiBindingResolver` surface (`ResolvedReactiveSlot`), but no
   `player.friends.list.*` resolver was wired. Opening the friends list
   showed only the close / add / pending buttons; the 45-slot grid was
   blank.
2. **Achievement viewer had nowhere to live.** Wave 5e shipped the
   `IAchievementRepository` + 5 milestone listeners; players could *unlock*
   achievements but had no in-game GUI to *browse* them. Both PoC and
   private-beta playtesters asked "where do I see my achievements?".
3. **Hotbar binding gateway was unused.** Wave 5gui-depth (ADR-0114)
   shipped `IGuiModalGateway` + `GuiModalSpec.QuickbarSelector`; Wave 5e
   added `PlayerSettings.quickCastSlots: Map<Int, String>`. Neither was
   actually wired — there was no operator-facing way to *set* the binding.
4. **Tooltip registry had zero providers registered.** Wave 5gui-depth
   shipped `ITooltipRegistry` + `ITooltipExtensionProvider` infrastructure
   but no plugin published any provider. The registry's `lookup` always
   returned an empty list; tooltip-extension dispatch had nothing to
   dispatch.
5. **No server population HUD.** Wave 5w `LobbyWaitingRoomHud` rendered
   the matchmaking queue but not the "N players online" sidebar that
   every Hypixel-class server uses. Playtesters could not tell at a glance
   if the lobby was active.

CLAUDE.md §Pre-release posture frames this as the right kind of wave to
land now: pure additions on top of existing ports, no schema break, no
Konsist rule change, no DB migration. Each item ships in 1-2 files plus
its plugin wiring; the boundary stays clean enough to unblock Wave 6's
Paper sweep.

## Decision

Land all five gaps in one wave, scoped by the existing sibling-isolation
matrix (ADR-0022):

- **§A Friend tile dynamic binding** — `:plugin:core`. New
  `PlayerFriendsListGuiBindingResolver` implements `IGuiBindingResolver`
  for `player.friends.list.0..8`. Reads `IFriendshipRepository` (Wave 5e)
  + `IPlayerSessionRepository` (Wave 5e) lazily; returns tile + status
  indicator per friend. New `CompositeGuiBindingResolver` stacks the new
  resolver in front of `DefaultGuiBindingResolver` and exposes a mutable
  `addDelegate(IGuiBindingResolver)` for §B to extend. Core's existing
  `services.register<IGuiBindingResolver>` wiring stays — the composite
  is registered under the same key, with the concrete type also exposed
  so feature plugins can append.
- **§B Achievement viewer GUI** — `:plugin:core` (declarative spec) +
  `:plugin:core` (intent handler) + `:plugin:stats` (binding resolver).
  `AchievementViewerGuiSpec` declares 45 reactive tiles bound to
  `player.achievement.list.<n>`. `LobbyAchievementShowIntentHandler` opens
  it under intent prefix `lobby.achievement.`. The data-side resolver
  `PlayerAchievementListGuiBindingResolver` lives in `:plugin:stats`
  (can read `IAchievementRepository`) and appends itself to the
  composite at `AsteraStatsPlugin.onEnable`. `content/gui/achievement-viewer.yaml`
  ships as a reload-aware companion.
- **§C Hotbar bind UI** — `:plugin:core` (handler) +
  `:plugin:combat` (wiring). `BindQuickbarSlotIntentHandler` opens a
  `GuiModalSpec.QuickbarSelector` modal listing available skills; the
  modal's `onSelect` posts `lobby.hotbar.bind.commit.<N>` with payload
  `skillId`, and the handler writes the entry to `PlayerSettings.quickCastSlots`
  via `IPlayerSettingsRepository.upsert`. Skill enumeration is a
  `() -> List<String>` lambda — Combat plugin supplies it from
  `SkillContents.all()` because Core cannot import Combat
  (ADR-0022 sibling-isolation), and the wiring lives in
  `AsteraCombatPlugin.onEnable`.
- **§D Tooltip provider register (sample 2)** — `:plugin:items`. Two
  providers registered at `AsteraItemsPlugin.onEnable`:
  `RarityGlowTooltipProvider` (scope `AllItems`, surfaces a rarity badge)
  and `RefinementTierTooltipProvider` (scope `ByRarity(RARE/EPIC/LEGENDARY)`,
  surfaces the viewer's highest layer count across owned instances of
  the item). The providers stay vendor-neutral; the registry handles
  scope evaluation and per-viewer fan-out per Wave 5gui-depth.
- **§E Server population HUD listener** — `:plugin:core`.
  `UpdatePopulationHudOnPlayerJoinedListener` +
  `UpdatePopulationHudOnPlayerQuitListener` subscribe `PlayerJoinedServer`
  / `PlayerQuitServer` respectively. Both recompute the active-session
  count from `IPlayerSessionRepository.all()` (excluding `Disconnected`)
  and push a `HudLayout.Sidebar` for every Lobby-state player via
  `IHudGateway.showFor`. Separate listener pattern (not a `LobbyWaitingRoomHud`
  extension) keeps the queue + population concerns decoupled.

No new Konsist rule. No DB migration. i18n adds
`astera.gui.binding.friend.*` / `astera.gui.achievement-viewer.*` /
`astera.tooltip.rarity.*` / `astera.tooltip.refinement.*` /
`astera.population.online.*` / `astera.hotbar.bind.*` /
`astera.gui.friends-list.tile.tooltip` keys in en + ja parity.

## Consequences

### Positive

- The friends list, achievement viewer, and tooltip extensions all render
  live data without any further wiring at Wave 6 (Paper sweep). The
  declarative GUI specs + reactive bindings are exactly the Wave 4 R3
  pattern Wave 6 expects.
- `CompositeGuiBindingResolver` gives every feature plugin a clean
  extension point for its own binding namespace — the renderer keeps a
  single resolver reference (no rewiring) and Stats / Items / Combat can
  contribute prefixes incrementally.
- The hotbar bind handler exercises Wave 5gui-depth's modal gateway
  end-to-end; the Wave 6 Paper modal adapter ships with a real
  consumer instead of a synthetic test.
- Population HUD makes the lobby feel alive at zero ongoing cost — the
  `IPlayerSessionRepository.all()` call is O(N) per join/quit (rare
  events), no tick subscription.
- Five visible polish improvements ship in a single commit, sized for
  one wave session and small enough to keep the diff reviewable.

### Negative / Trade-offs

- §A caps friends at 9 (Phase 1 PoC); pagination is an explicit Phase 2
  follow-up. Players with more than 9 accepted friends see only the
  first 9 by `(status, createdAt)` sort order.
- §B caps the achievement viewer at 45 tiles (one `CHEST_54` minus
  the close button + reserved bottom row). RTM's Phase 1 target catalog
  is < 45 so this is non-binding for now, but Phase 2 must add pagination
  or a category navigator before catalog growth.
- §C lacks a "what is currently bound to this slot?" preview. The modal
  shows every skill; the player picks one and the binding overwrites
  silently. Phase 2 should surface the current binding in the modal title
  + add a "clear binding" option.
- §D's `RefinementTierTooltipProvider` collapses across the viewer's
  multiple instances of the same item id by reporting the *highest* layer
  count. Phase 1 PoC heuristic; Wave 6+ should widen the tooltip-lookup
  signature to carry `ItemInstanceId` so the actually-hovered instance is
  resolved.
- §E's listener uses a fresh `HudLayout.Sidebar` every push — the Wave 6
  Paper adapter must dedupe identical layouts or the scoreboard will
  flicker on every join/quit. The in-memory gateway's
  `HudShown` event payload is sufficient for the dedupe; documented in
  the listener KDoc.

### Mitigations

- All five sub-scopes degrade gracefully on missing dependencies: each
  resolver / handler short-circuits to `null` / chat-warning / no-op if a
  collaborator is not yet wired. A misconfigured plugin order will not
  crash the server.
- The mutable `CompositeGuiBindingResolver.addDelegate` is idempotent
  (instance identity check) so a Stats double-enable does not double-route.

## Alternatives Considered

- **Single resolver overhaul instead of a composite.** Rejected: would
  rewrite `DefaultGuiBindingResolver` (Wave 4.5 + 5f) and tangle the
  test surface with friend / achievement provider mocks. The composite
  is additive and the new resolvers stay narrowly scoped.
- **Wire the hotbar bind handler in core with a hardcoded skill list.**
  Rejected: Konsist sibling-isolation would still block core's compile
  if we tried to `services.get<ISkillRegistry>()` from a core class. The
  lambda-injection approach lets the handler live in core and the
  enumeration live in combat — no Konsist diff, no test gymnastics.
- **Per-rarity tooltip provider instead of `AllItems` + `ByRarity` pair.**
  Rejected: Wave 5gui-depth's registry intentionally separates "global"
  from "per-bucket" providers so designers can disable the global
  rarity badge without touching the refinement summary. The two-provider
  split keeps each concern toggleable per registration.
- **Extend `LobbyWaitingRoomHud` to also render population.** Rejected:
  the queue concern and the population concern have different lifetimes
  (queue is per-player, population is server-wide). Coupling them would
  force the queue HUD to re-render on every join/quit, defeating the
  Wave 5w polling-rate budget.

## References

- 関連 ADR:
  - [[adr/0027]] Wave 4.5 GUI binding resolver (extended by §A)
  - [[adr/0035]] Wave 5f reactive numeric bindings (extended by §A)
  - [[adr/0114]] Wave 5gui-depth modal + tooltip foundation (consumed by §C / §D)
  - [[adr/0119]] Wave 5lobby-content intent handler pattern (mirrored by §B)
  - [[adr/0124]] Wave G player prefs (`quickCastSlots` field consumed by §C)
  - [[adr/0125]] Wave H content baseline + listener wire-up (similar shape)
- 関連 docs:
  - `docs/RTM-PARITY-TRACKER.md` — Round 14 polish row
