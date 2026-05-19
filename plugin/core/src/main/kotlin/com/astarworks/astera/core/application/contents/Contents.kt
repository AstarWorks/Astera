package com.astarworks.astera.core.application.contents

/**
 * Read-side view onto a typed registry of content entries.
 *
 * The substrate behind every `IItemRegistry` / `ISkillRegistry` / etc.
 * (Wave _infra-2 onward). Generic over key type [K] and value type [V] so each
 * content type keeps its existing domain identifiers — `ItemId`, `SkillId`,
 * … — rather than forcing a common `ContentKey` shape on the call sites.
 *
 * Phase 1 (Wave _infra-2a) intentionally does **not** introduce a separate
 * `ContentKey` value class (ADR-0017 Implementation Reference Plan, §"Use
 * net.kyori.adventure.key.Key"). Adventure is not yet on `plugin/core`'s
 * classpath and adding it solely for a string wrapper would be premature.
 * If cross-content-type lookup becomes necessary, Wave _infra-2b revisits.
 *
 * Implementations are typically:
 * - hand-registered (tests, programmatic stubs) via [YamlDynamicContents.register]
 * - bootstrap-loaded from `content/<category>/` via [YamlDynamicContents.bootstrap]
 * - hot-reloaded via [ReloadableContents.reload] (`/astera-<plugin> reload`)
 *
 * All three modes coexist on the same instance — there is no separate
 * "Mutable" interface.
 */
public interface Contents<K : Any, V : Any> {
    /** Returns the registered value for [key], or `null` if unknown. */
    public fun find(key: K): V?

    /** Snapshot of every currently-registered value. */
    public fun all(): Collection<V>
}
