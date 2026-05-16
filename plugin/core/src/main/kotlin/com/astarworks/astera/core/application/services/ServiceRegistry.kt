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
 */
public class ServiceRegistry {

    private data class Entry<T : Any>(
        val type: KClass<T>,
        val impl: T,
        val priority: Int,
        var active: Boolean = true,
    )

    private val entries: MutableList<Entry<*>> = mutableListOf()

    public fun <T : Any> register(
        type: KClass<T>,
        impl: T,
        priority: Int = 0,
    ): Registration {
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
    public fun <T : Any> get(type: KClass<T>): T? =
        entries
            .filter { it.active && it.type == type }
            .maxByOrNull { it.priority }
            ?.impl as T?

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
