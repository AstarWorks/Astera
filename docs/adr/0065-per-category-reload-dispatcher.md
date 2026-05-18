---
status: Accepted
date: 2026-05-19
deciders: orchestrator (Wave _infra-2b)
supersedes: null
superseded_by: null
---

# ADR-0065: Per-Category Reload Dispatcher (Core-Side)

## Status

Accepted

## Context

Wave _infra-2a (ADR-0060) landed the `YamlDynamicContents<K, V, C>` substrate
plus `ReloadableContents` interface and migrated `WeaponContents` as the
first concrete user. ADR-0060 explicitly deferred the cross-plugin dispatcher
question: each plugin's `/astera-<plugin> reload` was acceptable for the
single-plugin case (weapons), but extrapolating to nine more content types
(stages, skills, armor, status, items, gui, norma, anomaly, generators)
would produce nine per-plugin reload subcommands — fragmenting the operator
experience, duplicating permission gates, and tying each command shape to the
plugin's own command tree.

Wave _infra-2b migrates the remaining nine types and must answer:

1. **One command or many?** Operators reload content frequently during PoC
   authoring; ten different `/astera-<plugin> reload` flavours is a worse UX
   than one `/astera reload <category>` with tab-completion.
2. **Who owns the dispatcher?** It needs to know about content types in every
   feature plugin, which would inverse the dependency direction if it lived in
   a feature plugin. Core is the only module every feature depends on (per
   ADR-0022 sibling-isolation matrix).
3. **Permission?** One node (`astera.reload`) versus N nodes
   (`astera.weapons.reload`, `astera.combat.reload`, …). One node simplifies
   role definitions and matches typical "admin can reload anything" intent.
4. **How does the dispatcher know each category's on-disk directory?** Each
   plugin already knows its own `content/<category>/` dir at boot. Convention
   ("category name == directory name") is fragile if a plugin ever needs a
   custom layout. Passing the dir explicitly at registration is one extra
   parameter for zero ambiguity.
5. **Should `WeaponContents`' Wave _infra-2a `/astera-weapons reload` subcommand
   stay as an alias?** Per `.claude/rules/coding-posture.md` Phase 1 posture:
   no compatibility shims. Delete it outright; one command for everything.

## Decision

Introduce a new Core-owned port and impl:

- **`IReloadableContentsRegistry`** (plugin/core, `application/port/outbound/`)
  with three methods: `register(contents: ReloadableContents, dir: Path):
  Registration`, `categories(): Set<String>`, and `reload(categoryName: String):
  Result<ContentLoader.LoadReport, ReloadError>`. Sealed `ReloadError` carries
  `UnknownCategory` and `LoadFailed` variants, each with a `MessageKey` for
  rendering.
- **`InMemoryReloadableContentsRegistry`** (plugin/core,
  `application/service/`) — single Phase 1 impl, no priority overlay
  (categories are the namespace).
- **`/astera reload <category>`** — single Brigadier command registered in
  `AsteraCorePlugin` with tab-completion that reads
  `IReloadableContentsRegistry.categories()` at suggest-time. Permission:
  `astera.reload` (admin tier).
- **Per-plugin registration**: each feature plugin's `onEnable` does
  `services.getOrThrow<IReloadableContentsRegistry>().register(<contents>,
  <dir>)` after creating its `*Contents` instances.
- **Wave _infra-2a's `/astera-weapons reload`** is **deleted outright** —
  consolidated into `/astera reload weapons`. No alias, no deprecation period.

`ReloadableContents.reload(dir)`'s all-or-nothing contract from ADR-0060 is
preserved; the dispatcher delegates and surfaces `LoadFailed` when
`report.failed > 0` (the previous in-memory state stays intact in that case,
per the substrate's atomic-swap guarantee).

## Consequences

### Positive

- **Single command** for operators across all ten content types
  (`weapons / skills / armor / status / stages / items / gui / norma /
  anomaly / generators`). Tab-completion shows them all.
- **One permission node** (`astera.reload`) instead of ten.
- **Future content types** add zero command surface: `services.register(...)` +
  `reloadRegistry.register(...)` in `onEnable` is the entire integration.
- **Zero dependency inversion**: feature plugins still only depend on Core
  (per ADR-0022). The dispatcher pulls registrations in by interface; it does
  not know about any feature plugin at compile time.
- **Atomic-swap semantics preserved**: the dispatcher is a thin delegator;
  the substrate's `final override fun reload(dir)` keeps its all-or-nothing
  guarantee.

### Negative / Trade-offs

- **No per-category permissions**. An operator with `astera.reload` can
  reload anything. Phase 2+ may want `astera.reload.weapons` granularity (e.g.
  trusted content authors limited to specific categories); deferred until a
  real-world need surfaces.
- **No "reload one file" support**. The substrate's `reload(dir)` re-reads
  the whole directory. ADR-0060 already documented this as Phase 1 scope; the
  dispatcher inherits the same constraint.
- **Tab-completion list grows linearly** with category count. Acceptable for
  ten categories; if it ever crosses ~50, paginate or scope by prefix.
- **The dispatcher has no priority overlay**. If a future plugin wanted to
  "override" a category's reload behaviour (e.g., persistence-backed reload),
  re-registering replaces the prior binding. This matches the in-memory map
  semantics but differs from `ServiceRegistry`'s priority-based highest-wins.

### Mitigations

- **Per-category permissions** can be retrofitted by extending the executor
  to check `astera.reload.<category>` *additionally* before falling back to
  `astera.reload`. No interface change needed.
- **Tab-completion overflow** is a UX concern, not a correctness one. The
  command still works without completions.
- **Dispatcher overlay** can be added by introducing
  `register(..., priority: Int = 0)` and storing per-priority entries; the
  current single-impl design leaves room.

## Alternatives Considered

- **Each plugin keeps its own `/astera-<plugin> reload` subcommand.**
  Rejected: fragments operator UX, duplicates permission gates, and ten
  separate command trees grow stale independently. Wave _infra-2a's
  `/astera-weapons reload` was acceptable for one plugin but does not scale.

- **Static category→plugin map in Core.** Rejected: would force Core to know
  every feature plugin's content layout at compile time, inverting the
  ADR-0022 dependency direction.

- **Use `ServiceRegistry` directly, keyed by `KClass<? extends
  ReloadableContents>`.** Rejected: each content type would need its own
  marker subtype (`WeaponReloadable`, `SkillReloadable`, …) just so the
  dispatcher can look them up by class. Cleaner to key by the string the
  operator types anyway.

- **Convention-only dir resolution** (`core.contentDir.resolve(categoryName)`).
  Rejected (initially considered in the plan): saves one argument but adds an
  implicit convention that future custom-layout categories would have to break
  with a separate registration path. The two-arg `register(contents, dir)`
  costs nothing today and stays flexible.

- **Keep `/astera-weapons reload` as a thin alias for `/astera reload
  weapons`.** Rejected per Phase 1 pre-release posture (`.claude/rules/
  coding-posture.md`): no compatibility shims, no deprecation period. One
  forward-only command.

## References

- Related ADRs:
  - [[adr/0017-adopt-datadriven-contents-pattern]] (substrate intent;
    superseded by ADR-0060)
  - [[adr/0022-multi-plugin-layout]] (sibling-isolation matrix +
    Core's role as the lone dependency root)
  - [[adr/0059-fake-mc-server-scenario-dsl]] (the `AsteraTestHarness` whose
    `MutableXRegistry` fields this wave updated)
  - [[adr/0060-datadriven-contents-pattern]] (substrate impl; this ADR
    extends the dispatcher question deferred there)
- Related docs:
  - `.claude/rules/coding-posture.md` §"Pre-release breaking changes (Phase 1)"
  - `docs/IMPLEMENTATION-ROADMAP.md` §"ADR allocation per wave"
  - `plugin/core/.../application/contents/YamlDynamicContents.kt` (substrate)
  - `plugin/core/.../application/port/outbound/IReloadableContentsRegistry.kt`
    (this ADR's port)
  - `plugin/core/.../application/service/InMemoryReloadableContentsRegistry.kt`
    (this ADR's impl)
  - `plugin/core/.../platform/AsteraCorePlugin.kt` (dispatcher registration +
    `/astera reload` command registration)
