---
status: Draft
date: 2026-05-17
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0021: Declarative GUI Architecture

## Status

Draft

## Context

Phase 3 (Siege Warfare) needs a meaningful inventory-GUI surface — loadout
selection between rounds, post-match shop, anomaly-vote prompts, scoreboard
panels. Phase 4 (Web admin) wants to render the **same** specs to HTML, so
moderators can preview / author GUIs without launching Paper. The naive
"each plugin spawns its own Bukkit inventory + listener" approach blocks both
goals: every GUI ends up tied to Paper, and cross-plugin event listeners pin
plugin classloaders the moment they capture a lambda (the same retention
class that bit Wave 3 — see ADR-0020 §"Cross-plugin KClass identity").

By Wave 1 ε the GUI **domain** had been pre-declared:
`GuiSpec`/`GuiSlot`/`GuiIntent`/`GuiSize`/`GuiId`/`SlotIndex`/`BindingKey` in
`plugin/core/.../domain/gui/`, plus a Redux-style `GuiStore<S>` and a pure
`GuiDiffEngine` + `SlotPatch` reconciler in `plugin/core/.../application/gui/`.
Wave 4 (R3) fills in the missing adapter + intent routing + loader + content
schema so `/astera-weapons gui loadout` actually opens a 9-slot inventory.

Non-negotiables coming in:

1. Domain stays pure: no Bukkit, no `kotlinx.serialization` annotations
   (Konsist enforces both).
2. ADR-0002 vendor-neutral adapter layer: `core/adapter/api/gui/` is
   Bukkit-free; only `core/adapter/paper/gui/` may touch `org.bukkit.*`.
3. ADR-0020 cross-plugin classloader rule: every port + every value type
   touched by `services.get<T>()` lives in `plugin/core`.
4. ADR-0019 unified scheduler: all GUI mutation happens on the Paper main
   thread; the adapter does not internally schedule.

## Decision

### GUI as an immutable value tree

`GuiSpec` is the entire vocabulary the application speaks. Slots are sealed
(`Static` / `Border` / `Reactive` / `Empty`); clicks are **values**
(`GuiIntent(key: String, payload: Map<String, String>)`), never callbacks.
The lambda-free invariant is enforced by an existing Konsist rule
(`gui slot variants must not contain lambda fields`).

Why values, not callbacks:

- Phase 4 web admin can serialize a `GuiSpec` over the wire and render it as
  HTML; lambdas can't cross the boundary.
- Click handlers registered in one plugin's classloader and held by another
  retain the first plugin's classloader after disable. Routing through
  `IGuiIntentRouter.register(prefix, handler)` + explicit
  `Registration.unregister()` on `onDisable` makes the retention boundary
  explicit instead of accidental.

### Diff + patch reconciliation, adapter-owned memory

`GuiDiffEngine.diff(prev, next): List<SlotPatch>` is a pure function in the
application layer. The **adapter** holds the per-player "last spec shown" map
and feeds it to the engine when a use case calls `update(player, next)` —
callers never have to track `prev`. `SlotPatch.Set`/`Clear`/`TitleChange`
keep the wire small enough that future remote renderers (Phase 4 web) only
need to ship the delta.

### Ports + Paper impl layout

```
plugin/core/
├─ domain/gui/                         (Wave 1 ε pre-declared, pure)
├─ application/gui/                    (Wave 1 ε pre-declared, pure)
│   ├─ GuiDiffEngine
│   ├─ GuiStore<S>
│   └─ SlotPatch
├─ application/port/outbound/
│   ├─ IGuiAdapter                     (Wave 4)
│   └─ IGuiRegistry                    (Wave 4)
├─ application/port/inbound/
│   └─ IGuiIntentRouter                (Wave 4)
├─ application/service/
│   ├─ InMemoryGuiRegistry             (Wave 4)
│   ├─ GuiLoaderService                (Wave 4)
│   └─ DefaultGuiIntentRouter          (Wave 4)
├─ application/config/
│   └─ GuiYamlConfig                   (Wave 4)
├─ adapter/api/gui/
│   └─ IMcInventory                    (Wave 4)
└─ adapter/paper/gui/                  (Wave 4, Bukkit-allowed)
    ├─ PaperGuiAdapter
    ├─ PaperGuiListener
    ├─ PaperInventoryHolder
    └─ PaperItemTemplateRenderer
```

`PaperGuiAdapter` implements `IGuiAdapter`; `DefaultGuiIntentRouter`
implements `IGuiIntentRouter`; `InMemoryGuiRegistry` implements
`IGuiRegistry`. All three are registered onto `ServiceRegistry` from
`AsteraCorePlugin.onEnable` so feature plugins resolve them via
`services.getOrThrow<X>()`.

### `PaperInventoryHolder` identifies "our" inventories

Every `Bukkit.createInventory(holder, …)` call uses a
`PaperInventoryHolder(guiId: GuiId)` instance. `PaperGuiListener` filters
every `InventoryClickEvent` / `InventoryDragEvent` /
`InventoryCloseEvent` by checking `event.inventory.holder is
PaperInventoryHolder`. Without this marker, the listener would have to
race-resolve the player against `PaperGuiAdapter`'s session map and
mis-identify Bukkit shulker / chest interactions as our GUIs.

### Title change → close + reopen

Bukkit 1.21 has no public API to mutate an open inventory's title.
NMS reflection would violate ADR-0002. Therefore when
`GuiDiffEngine.diff(prev, next)` includes `SlotPatch.TitleChange`,
`PaperGuiAdapter.update` closes the current inventory, builds a new one
with the new title, fully repopulates every slot, and reopens it. The
adapter maintains a `reopening: MutableSet<UUID>` guard that
`PaperGuiListener.onClose` consults; if the player's UUID is in the set,
the close event is the adapter's own, not a real user close, and the
session entry is preserved.

This is a small UX cost (~50 ms blink) but the only Phase 1 / Phase 3
option. If Adventure ever ships a live-title API the close-reopen path
collapses to a single `inv.setTitle(component)` call without touching any
other code.

### Click cancellation is unconditional

`PaperGuiListener.onClick` does `event.isCancelled = true` for **every**
click inside a `PaperInventoryHolder` inventory — Static-with-onClick,
Static-with-null-onClick, Border, Reactive, Empty, shift-click from the
player's own inventory targeting our GUI — every slot, every action. Only
after cancelling does the listener resolve the slot from the adapter's
current spec and dispatch the `GuiIntent` (if the slot was a Static with
`onClick != null`). Without unconditional cancel, a player could
shift-click a Border out of the GUI and pocket a coloured glass pane.

Pure bottom-inventory clicks (rearranging the player's own hotbar while
our GUI is open) are *not* cancelled — only the
`MOVE_TO_OTHER_INVENTORY` action which would push items up into our GUI.
Players keep agency over their own inventory.

### `IGuiIntentRouter` is longest-prefix-match + Registration

Plugins claim namespaces (`weapons.loadout.`, `shop.`, `match.vote.`) with
a single `register(prefix, handler)` call. The router stores
`(prefix, handler)` entries in insertion order; `dispatch` finds all entries
where `intent.key.startsWith(entry.prefix)` and picks the one with the
longest prefix (first-registered wins ties). Unmatched intents log at WARN
and are dropped — a misconfigured GUI never crashes the click listener.

Built-in `"gui.close"` is self-registered by `DefaultGuiIntentRouter` in a
field initializer; feature plugins MUST NOT shadow it. The handler closes
the player's open GUI through `IGuiAdapter.close`.

### Lazy `adapterProvider: () -> IGuiAdapter`

`DefaultGuiIntentRouter`'s built-in close handler needs an `IGuiAdapter`,
but the adapter is constructed *after* the router in current wiring (the
adapter has no router dependency; only the listener does). Passing the
adapter through a `() -> IGuiAdapter` lambda defers resolution until click
time, when both are guaranteed alive. The construction order in
`AsteraCorePlugin.onEnable` is renderer → adapter → router → listener.

### `GuiStore<S>` is deferred from Wave 4

The pre-declared `GuiStore<S>` (Redux-style reducer + renderer +
`StateFlow<GuiSpec>` projection) is not exercised in Wave 4. The Wave 4
demo (`/astera-weapons gui loadout`) uses a simple per-player
`ConcurrentHashMap<PlayerId, Boolean>` toggle state inside
`LoadoutToggleHandler`, calling `adapter.update(player, newSpec)` directly.
Wave 5+ work that genuinely needs reactive views (`Reactive` binding
resolution, multi-player synchronized GUIs) will switch to `GuiStore`.

This deferral is recorded so future readers don't think `GuiStore` is
dead code — it's a load-bearing primitive whose first real consumer
hasn't shipped yet.

### `Reactive` slot binding is placeholder-only

`GuiDiffEngine` renders `GuiSlot.Reactive(binding)` as
`SlotPatch.Set(idx, ItemId(binding.value), count=1)` — i.e. the binding
string is parsed as an `ItemId` and resolved through the existing item
registry. Since `BindingKey` allows dots (`player.coins`) and `ItemId`
does not, `PaperItemTemplateRenderer` catches `IllegalArgumentException`
from the `ItemId` constructor and falls back to `Material.BARRIER` so a
typo doesn't kill the entire render.

This is structural support only — Wave 4 does not introduce a view-model
or binding-resolution mechanism. Real binding resolution lands in Wave 5+
once we have a clearer story for "what data flows into a Reactive slot."

### Cross-plugin classloader rule (ADR-0020 §"Cross-plugin KClass identity")

All Wave 4 ports + value types live in `plugin/core`:

- `IGuiAdapter`, `IGuiIntentRouter`, `IGuiRegistry` (ports)
- `IMcInventory` (vendor-neutral adapter API)
- `GuiSpec`, `GuiSlot`, `GuiIntent`, `GuiId`, `GuiSize`, `SlotIndex`,
  `BindingKey` (domain, pre-existing)

Feature plugin `shadowJar`s exclude `plugin/core` (already configured in
Wave 3), so every plugin sees the same `Class<IGuiAdapter>` through
`Astera-Core`'s classloader. Handler lambdas registered via
`IGuiIntentRouter.register` are `Function2<GuiIntent, IMcPlayer, Unit>`
instances belonging to the registering plugin's classloader; the
`kotlin.jvm.functions.FunctionN` interface itself lives in `kotlin-stdlib`
(shared parent loader), so the dispatch call works across classloaders.
However, the lambda **pins the registering plugin's classloader** until
its `Registration.unregister()` is called — `IGuiIntentRouter`'s contract
requires plugins to invoke it from `onDisable`.

A new Konsist rule "feature plugins must not import
`com.astarworks.astera.core.adapter.paper.gui`" (Wave 4) closes the
boundary on the static side: feature code is only ever allowed to depend
on the ports + adapter-api types, never on the Paper impl.

## Consequences

### Positive

- A single `GuiSpec` can drive a Bukkit inventory today and an HTML
  preview in Phase 4 with no change to the spec or the diff engine.
- New plugins add GUIs by writing a `*.yaml` under `content/gui/` and
  registering an intent handler; no Bukkit code required.
- Click-cancellation is enforced at the listener so no plugin can
  accidentally let a player walk out with a decorative item.
- The diff engine keeps inventory updates cheap (no full repopulate per
  state tick), which matters once Reactive bindings start firing on every
  player-stat change.

### Negative / trade-offs

- Title changes blink the inventory shut for ~1 tick. Acceptable for
  Phase 1-3 demo GUIs; if Phase 4 wants smooth title animation we'll need
  either Adventure live-title support (forthcoming) or a Paper PR.
- `Reactive` is structurally supported but doesn't resolve real bindings
  yet; a casual reader of `content/gui/*.yaml` could write
  `binding: player.coins` and get a BARRIER + WARN log instead of the
  expected number. Document this in `content/gui/README.md` (Wave 5
  follow-up) once binding resolution lands.
- The lazy `adapterProvider` lambda inside `DefaultGuiIntentRouter` is a
  small smell — it papers over a construction-order quirk that future
  refactors might be tempted to "fix" by making the adapter depend on the
  router. Don't: the router needs to call `adapter.close` for the
  built-in `gui.close` intent, the adapter has no symmetric need for the
  router, so the dependency direction is correct. The lambda is the
  cheapest way to express it.
- `Function2<GuiIntent, IMcPlayer, Unit>` lambdas pin the registering
  plugin's classloader. Forgetting to `unregister()` in `onDisable` is a
  latent leak that wouldn't surface until someone reloads a single
  feature plugin in dev. The `IGuiIntentRouter` KDoc calls this out
  loudly; we may consider a Konsist rule that walks `*Plugin.kt`'s
  `onDisable` and verifies all `intentRouter.register(...)` calls
  produce a `Registration` captured into the plugin's `registrations`
  list — but the rule is non-trivial to express and the current
  `AsteraWeaponsPlugin` follows the pattern correctly, so it's left for
  a future ADR.

### Operational

- `content/gui/*.yaml` schema is auto-generated by
  `tools/schema-gen:generateSchemas` (Wave 4 adds the `gui` SchemaEntry).
  Editors get IDE completion the same way they do for weapons / items /
  stages / norma.
- New plugin scaffolding (the `astera.kotlin-paper` convention) should
  bake in the `Registration` cleanup pattern so future plugins don't need
  to remember it manually.

## Alternatives considered

- **Triumph-GUI / PaperUI (third-party GUI libraries)**: pulls Bukkit
  imports into the application layer (Konsist violation), and the spec
  format is library-specific so Phase 4 web reuse is impossible.
- **Lambda-based slot click handlers**: prevents Phase 4 serialization
  and pins plugin classloaders by default. The whole `GuiIntent` value
  detour exists to avoid this.
- **NMS reflection for live title mutation**: violates ADR-0002 (vendor
  neutrality) and would tie us to specific Paper versions. The
  close-reopen blink is a cheap-enough fallback.
- **Per-feature-plugin GUI adapter**: would let each plugin own its own
  Bukkit listener, but multiplies the cancellation logic and the
  classloader retention surface area. Centralizing both in
  `Astera-Core` is the lesser evil.

## Relationship to other ADRs

- ADR-0001 — Clean Architecture (motivation: GUI as pure domain + thin
  adapter)
- ADR-0002 — Vendor-neutral MC adapter layer (`adapter/api/gui/` no
  Bukkit; only `adapter/paper/gui/` allowed)
- ADR-0009 — Soft-dep only Paper plugin.yml (the GUI feature has no soft
  deps; everything is internal)
- ADR-0015 — External plugin interrogation policy (we don't depend on
  CommandAPI / ACF; `CommandSpec` is enough for the gui subcommand)
- ADR-0016 — Content schema SSoT (gui schema auto-generated from
  `GuiYamlConfig`)
- ADR-0019 — Unified Tick Scheduler (adapter mutations are main-thread
  only; collectors must funnel through `IScheduler.mainDispatcher`)
- ADR-0020 — Persistence Architecture (cross-plugin KClass identity rule
  applied here too: ports + value types in `plugin/core`, feature
  shadowJars exclude core)
- ADR-0022 — Multi-plugin Portable Architecture (the GUI ports register
  through the same `ServiceRegistry` other ports use)
- ADR-0025 — Event Wiring & Broadcaster/EventBus Split (intent dispatch
  follows the same in-process subscriber model)

## Notes for Wave 5+

- `Reactive` binding resolution: introduce an `IGuiBindingResolver` port
  that takes `(BindingKey, IMcPlayer) -> ItemId?` (or `Component?`) and
  is consulted by the renderer for every `Reactive` slot at update time.
  `GuiStore<S>` becomes the natural place to wire it.
- `GuiStore<S>` first real consumer: the Phase 3 anomaly-vote prompt,
  where a 10-second countdown updates a slot every second and a final
  click closes the GUI + dispatches `match.vote.<choice>`.
- Phase 4 web admin: a second `IGuiAdapter` impl whose `open` emits the
  spec over a WebSocket and whose `update` ships the `SlotPatch` list.
  The intent dispatch flows back the same channel.
