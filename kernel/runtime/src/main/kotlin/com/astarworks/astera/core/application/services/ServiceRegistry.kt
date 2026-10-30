package com.astarworks.astera.core.application.services

import com.astarworks.astera.core.application.wiring.WiringTag
import kotlin.reflect.KClass

/**
 * In-process service registry used to wire cross-plugin port implementations
 * (ADR-0022). Each feature plugin registers its outbound port impls here on
 * `onEnable`; other plugins resolve them via [get] / [getOrThrow].
 *
 * - Registration is priority-ordered: higher [priority] wins. Persistence
 *   plugin registers Postgres-backed impls with priority 100 to override
 *   feature plugins' in-memory defaults (priority 0).
 * - Cancellable via [Registration]; plugins call `unregister()` on
 *   `onDisable` to clean up when the server shuts a single plugin.
 * - NOT thread-safe; called only from the Paper main thread.
 * - Every registration carries the [WiringTag] of the view that made it (ADR-0311). [scoped] returns a view over
 *   the same store that stamps its tag; `WiringContext.services` is such a view, so wiring code needs no change.
 *   A registration on the unscoped root is recorded as [WiringTag.UNTAGGED]; main code must not do that
 *   (Konsist `RewireOwnershipTest`).
 * - Late-override guard (ADR-0195): once a type has been resolved, a later
 *   registration that would outrank the resolved implementation means some
 *   consumer already captured the wrong one (overlay split brain). It is
 *   recorded in [lateOverrides] and reported to [lateOverrideListener].
 */
public class ServiceRegistry private constructor(
    private val store: Store,
    private val tag: WiringTag,
    private val decorate: ((KClass<*>, Any) -> Any)?,
) {

    public constructor() : this(Store(), WiringTag.UNTAGGED, null)

    /** Per-view cache of decorated resolutions, keyed by type and checked against the winning implementation. */
    private val decorated: MutableMap<KClass<*>, Pair<Any, Any>> = mutableMapOf()

    private data class Entry<T : Any>(
        val type: KClass<T>,
        val impl: T,
        val priority: Int,
        val tag: WiringTag,
        var active: Boolean = true,
    )

    /** State shared by the root and every [scoped] view. */
    private class Store {
        val entries: MutableList<Entry<*>> = mutableListOf()

        /** Highest priority handed out so far per type (what consumers may have captured). */
        val resolvedPriority: MutableMap<KClass<*>, Int> = mutableMapOf()
        val lateOverrides: MutableList<LateOverride> = mutableListOf()
        var lateOverrideListener: ((LateOverride) -> Unit)? = null
    }

    /**
     * A view over the same registrations whose [register] calls are recorded under [tag] (ADR-0311). When [decorate]
     * is given, [get] hands the view's consumers `decorate(type, impl)` instead of `impl` (used to give a wiring
     * generation owner-tagged event bus / scheduler / intent router / reload registry); the result is cached per
     * winning implementation.
     */
    public fun scoped(tag: WiringTag, decorate: ((KClass<*>, Any) -> Any)? = null): ServiceRegistry =
        ServiceRegistry(store, tag, decorate)

    /** What [tag] has registered and not yet unregistered, as `type -> priority`. */
    public fun registeredBy(tag: WiringTag): List<Pair<KClass<*>, Int>> =
        store.entries.filter { it.active && it.tag == tag }.map { it.type to it.priority }

    /** Every tag that currently holds a registration. */
    public fun tags(): Set<WiringTag> = store.entries.filter { it.active }.mapTo(linkedSetOf()) { it.tag }

    /** Unregisters everything [tag] registered (one wiring generation). Returns how many entries went. */
    public fun unregisterTag(tag: WiringTag): Int {
        val mine = store.entries.filter { it.tag == tag }
        mine.forEach { it.active = false }
        store.entries.removeAll(mine.toSet())
        return mine.size
    }

    /** A registration that outranks an implementation consumers were already given. */
    public data class LateOverride(
        val type: KClass<*>,
        val registeredPriority: Int,
        val resolvedPriority: Int,
    ) {
        val message: String
            get() = "ServiceRegistry: ${type.qualifiedName} registered at priority $registeredPriority " +
                "AFTER a consumer resolved priority $resolvedPriority. That consumer holds a stale " +
                "implementation (persistence overlay split brain, ADR-0195)."
    }

    /** Every late override seen so far; tests assert this is empty. */
    public val lateOverrides: List<LateOverride> get() = store.lateOverrides.toList()

    /** Called on every late override (production logs SEVERE). */
    public var lateOverrideListener: ((LateOverride) -> Unit)?
        get() = store.lateOverrideListener
        set(value) { store.lateOverrideListener = value }

    public fun <T : Any> register(
        type: KClass<T>,
        impl: T,
        priority: Int = 0,
    ): Registration {
        store.resolvedPriority[type]?.let { seen ->
            if (priority > seen) {
                val late = LateOverride(type, priority, seen)
                store.lateOverrides += late
                store.lateOverrideListener?.invoke(late)
            }
        }
        val entry = Entry(type, impl, priority, tag)
        store.entries += entry
        return object : Registration {
            override fun unregister() {
                entry.active = false
                store.entries.remove(entry)
            }
            override val isActive: Boolean get() = entry.active
        }
    }

    @Suppress("UNCHECKED_CAST")
    public fun <T : Any> get(type: KClass<T>): T? {
        val winner = winnerEntry(type) ?: return null
        store.resolvedPriority.merge(type, winner.priority, ::maxOf)
        val wrap = decorate ?: return winner.impl as T
        val cached = decorated[type]
        if (cached != null && cached.first === winner.impl) return cached.second as T
        val made = wrap(type, winner.impl)
        decorated[type] = winner.impl to made
        return made as T
    }

    /**
     * The highest priority wins. Among equal priorities the first registration wins, except that a newer generation
     * of the same owner outranks its older one (ADR-0311): during a rewire the new generation resolves its own
     * entries while the old generation is still registered.
     */
    private fun winnerEntry(type: KClass<*>): Entry<*>? {
        val top = store.entries.filter { it.active && it.type == type }
        val best = top.maxOfOrNull { it.priority } ?: return null
        val tied = top.filter { it.priority == best }
        val first = tied.first()
        return tied.filter { it.tag.owner == first.tag.owner }.maxByOrNull { it.tag.gen } ?: first
    }

    /** Tag and priority of the registration [get] would hand out for [type], or null when none is registered. */
    public fun winnerOf(type: KClass<*>): Pair<WiringTag, Int>? = winnerEntry(type)?.let { it.tag to it.priority }

    public fun <T : Any> getOrThrow(type: KClass<T>): T =
        get(type) ?: error("No service registered for ${type.qualifiedName}")

    public interface Registration {
        public fun unregister()
        public val isActive: Boolean
    }
}

public inline fun <reified T : Any> ServiceRegistry.register(
    impl: T,
    priority: Int = 0,
): ServiceRegistry.Registration = register(T::class, impl, priority)

public inline fun <reified T : Any> ServiceRegistry.get(): T? = get(T::class)

public inline fun <reified T : Any> ServiceRegistry.getOrThrow(): T = getOrThrow(T::class)
