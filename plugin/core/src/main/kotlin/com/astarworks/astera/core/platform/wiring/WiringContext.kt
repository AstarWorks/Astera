package com.astarworks.astera.core.platform.wiring

import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IReloadableContentsRegistry
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.wiring.OwnedEventBus
import com.astarworks.astera.core.application.wiring.OwnedGuiIntents
import com.astarworks.astera.core.application.wiring.OwnedReloadables
import com.astarworks.astera.core.application.wiring.OwnedScheduler
import com.astarworks.astera.core.application.wiring.WiringOwner
import com.astarworks.astera.core.application.wiring.WiringTag
import com.astarworks.astera.core.domain.proxy.ServerName
import com.astarworks.astera.core.domain.proxy.ServerRole
import java.nio.file.Path
import com.astarworks.astera.core.application.wiring.WiringCounts
import java.util.logging.Logger
import kotlin.reflect.KClass

/**
 * Host-neutral handle every plugin's pure wiring (`<Plugin>Wiring.install`)
 * receives (ADR-0174). The Paper plugin and the headless simulator
 * (`:tools:game-sim`) both build one and call the same wiring functions, so
 * the simulator boots exactly the production object graph.
 *
 * It is also the single funnel for registrations (ADR-0311): [services], [eventBus], [scheduler], [intents],
 * [reloadables], [commandMount] and (Paper side) `listeners(plugin)` stamp every registration with this context's
 * [tag] (owner + generation). Wiring code never touches the root registry, bus or scheduler directly
 * (Konsist `RewireOwnershipTest`).
 *
 * Wiring code appends what it registers / subscribes to [registrations] /
 * [subscriptions]; the host closes them on shutdown.
 */
public class WiringContext(
    /** The plugin that owns this wiring run (plugin.yml name). */
    public val owner: WiringOwner,
    /** Generation of that plugin's wiring; 0 until a rewire exists. */
    public val gen: Int,
    private val rootServices: ServiceRegistry,
    public val contentDir: Path,
    public val logger: Logger,
    /** Which part of the game this backend hosts (lobby / game / all-in-one). */
    public val serverRole: ServerRole = ServerRole.ALL,
    /** This backend's name in the Velocity `[servers]` table. */
    public val serverName: ServerName = ServerEnvironment.DEFAULT_NAME,
) {
    /** Owner and generation stamped on everything this context registers. */
    public val tag: WiringTag = WiringTag(owner, gen)

    /**
     * The service registry as this generation sees it: [ServiceRegistry.register] is recorded under [tag], and the
     * event bus, scheduler, GUI intent router and reload registry resolve as owner-tagged views (see [eventBus] etc.),
     * so existing `services.getOrThrow<IDomainEventBus>()` call sites are tagged without change.
     */
    public val services: ServiceRegistry = rootServices.scoped(tag) { type, impl ->
        when (type) {
            IDomainEventBus::class -> OwnedEventBus(impl as IDomainEventBus, tag)
            IScheduler::class -> OwnedScheduler(impl as IScheduler, tag)
            IGuiIntentRouter::class -> OwnedGuiIntents(impl as IGuiIntentRouter, tag)
            IReloadableContentsRegistry::class -> OwnedReloadables(impl as IReloadableContentsRegistry, tag)
            else -> impl
        }
    }

    // Resolved on first use: the substrate objects are registered before any wiring except Core's own.
    public val eventBus: OwnedEventBus get() = services.getOrThrow<IDomainEventBus>() as OwnedEventBus
    public val scheduler: OwnedScheduler get() = services.getOrThrow<IScheduler>() as OwnedScheduler
    public val intents: OwnedGuiIntents get() = services.getOrThrow<IGuiIntentRouter>() as OwnedGuiIntents
    public val reloadables: OwnedReloadables
        get() = services.getOrThrow<IReloadableContentsRegistry>() as OwnedReloadables

    /** Host-specific per-generation state (the Paper host keeps its `OwnedListeners` here). */
    public val attachments: MutableMap<String, Any> = mutableMapOf()

    public val registrations: MutableList<ServiceRegistry.Registration> = mutableListOf()
    public val subscriptions: MutableList<IDomainEventBus.Subscription> = mutableListOf()

    /** Handle for mounting this plugin's subtrees under `/astera` (ADR-0184); removed again by [close]. */
    public fun commandMount(): com.astarworks.astera.core.application.command.CommandMount =
        com.astarworks.astera.core.application.command.CommandMount(
            rootServices.getOrThrow<com.astarworks.astera.core.application.port.inbound.IAsteraCommandRegistry>(),
            owner.id,
            gen,
            registrations,
        )

    /** Host hooks run by [close] (the Paper host unregisters this generation's Bukkit listeners here). */
    private val closers: MutableList<() -> Unit> = mutableListOf()

    /** Host-supplied live counts reported by [counts] (`listeners`). */
    private val probes: MutableMap<String, () -> Int> = mutableMapOf()

    /** Runs [action] when this generation is closed. */
    public fun onClose(action: () -> Unit) {
        closers += action
    }

    /** Lets the host report a live count under [name] in [counts]. */
    public fun probe(name: String, count: () -> Int) {
        probes[name] = count
    }

    /** A context for [otherOwner] / [otherGen] over the same registry, content dir and server identity. */
    public fun forOwner(otherOwner: WiringOwner, otherGen: Int): WiringContext =
        WiringContext(otherOwner, otherGen, rootServices, contentDir, logger, serverRole, serverName)

    /**
     * Resolves in-memory state that survives a rewire (ADR-0311 state carry). Nothing registered: [create] it and
     * register it under this generation. The winner belongs to an older generation of the same owner: adopt that
     * very instance and register it under this generation, so it outlives the old generation's teardown. Anything
     * else (another owner's overlay, e.g. a persistence repository, or this generation's own entry) is returned
     * unchanged.
     */
    public fun <T : Any> stateful(type: KClass<T>, priority: Int = 0, create: () -> T): T {
        val winner = rootServices.winnerOf(type)
        if (winner == null) {
            return create().also { registrations += services.register(type, it, priority) }
        }
        val (winnerTag, winnerPriority) = winner
        val current = services.getOrThrow(type)
        if (winnerTag.owner == owner && winnerTag.gen < gen) {
            registrations += services.register(type, current, winnerPriority)
        }
        return current
    }

    public inline fun <reified T : Any> stateful(priority: Int = 0, noinline create: () -> T): T =
        stateful(T::class, priority, create)

    /** What this generation currently has registered (a rewire must leave these equal). */
    public fun counts(): WiringCounts = WiringCounts(
        services = rootServices.registeredBy(tag).size,
        subscriptions = runCatching { eventBus.activeCount }.getOrDefault(0),
        intents = runCatching { intents.activeCount }.getOrDefault(0),
        tasks = runCatching { scheduler.activeCount }.getOrDefault(0),
        reloadables = runCatching { reloadables.activeCount }.getOrDefault(0),
        listeners = probes["listeners"]?.invoke() ?: 0,
    )

    /**
     * Tears this whole generation down (ADR-0311): scheduler tasks, event subscriptions, GUI intents, reload
     * bindings, host hooks, then every service and command registration stamped with [tag]. Newest first.
     */
    public fun close() {
        runCatching { scheduler.cancelAll() }
        runCatching { eventBus.cancelAll() }
        runCatching { intents.unregisterAll() }
        runCatching { reloadables.unregisterAll() }
        closers.asReversed().forEach { runCatching { it() } }
        subscriptions.asReversed().forEach { runCatching { it.cancel() } }
        registrations.asReversed().forEach { runCatching { it.unregister() } }
        rootServices.unregisterTag(tag)
        closers.clear()
        subscriptions.clear()
        registrations.clear()
    }
}
