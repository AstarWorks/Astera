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
import java.util.logging.Logger

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
            registrations,
        )

    /** Unregisters / unsubscribes everything recorded, newest first. */
    public fun close() {
        subscriptions.asReversed().forEach { runCatching { it.cancel() } }
        registrations.asReversed().forEach { runCatching { it.unregister() } }
        subscriptions.clear()
        registrations.clear()
    }
}
