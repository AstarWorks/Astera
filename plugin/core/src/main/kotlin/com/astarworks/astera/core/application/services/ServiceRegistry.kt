package com.astarworks.astera.core.application.services

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
 * - Late-override guard (ADR-0187): once a type has been resolved, a later
 *   registration that would outrank the resolved implementation means some
 *   consumer already captured the wrong one (overlay split brain). It is
 *   recorded in [lateOverrides] and reported to [lateOverrideListener].
 */
public class ServiceRegistry {

    private data class Entry<T : Any>(
        val type: KClass<T>,
        val impl: T,
        val priority: Int,
        var active: Boolean = true,
    )

    private val entries: MutableList<Entry<*>> = mutableListOf()

    /** Highest priority handed out so far per type (what consumers may have captured). */
    private val resolvedPriority: MutableMap<KClass<*>, Int> = mutableMapOf()

    /** A registration that outranks an implementation consumers were already given. */
    public data class LateOverride(
        val type: KClass<*>,
        val registeredPriority: Int,
        val resolvedPriority: Int,
    ) {
        val message: String
            get() = "ServiceRegistry: ${type.qualifiedName} registered at priority $registeredPriority " +
                "AFTER a consumer resolved priority $resolvedPriority. That consumer holds a stale " +
                "implementation (persistence overlay split brain, ADR-0187)."
    }

    private val _lateOverrides: MutableList<LateOverride> = mutableListOf()

    /** Every late override seen so far; tests assert this is empty. */
    public val lateOverrides: List<LateOverride> get() = _lateOverrides.toList()

    /** Called on every late override (production logs SEVERE). */
    public var lateOverrideListener: ((LateOverride) -> Unit)? = null

    public fun <T : Any> register(
        type: KClass<T>,
        impl: T,
        priority: Int = 0,
    ): Registration {
        resolvedPriority[type]?.let { seen ->
            if (priority > seen) {
                val late = LateOverride(type, priority, seen)
                _lateOverrides += late
                lateOverrideListener?.invoke(late)
            }
        }
        val entry = Entry(type, impl, priority)
        entries += entry
        return object : Registration {
            override fun unregister() {
                entry.active = false
                entries.remove(entry)
            }
            override val isActive: Boolean get() = entry.active
        }
    }

    @Suppress("UNCHECKED_CAST")
    public fun <T : Any> get(type: KClass<T>): T? {
        val winner = entries
            .filter { it.active && it.type == type }
            .maxByOrNull { it.priority }
            ?: return null
        resolvedPriority.merge(type, winner.priority, ::maxOf)
        return winner.impl as T
    }

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
