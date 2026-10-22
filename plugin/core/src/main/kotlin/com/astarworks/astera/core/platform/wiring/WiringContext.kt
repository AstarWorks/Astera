package com.astarworks.astera.core.platform.wiring

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.services.ServiceRegistry
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
 * Wiring code appends what it registers / subscribes to [registrations] /
 * [subscriptions]; the host closes them on shutdown.
 */
public class WiringContext(
    public val services: ServiceRegistry,
    public val contentDir: Path,
    public val logger: Logger,
    /** Which part of the game this backend hosts (lobby / game / all-in-one). */
    public val serverRole: ServerRole = ServerRole.ALL,
    /** This backend's name in the Velocity `[servers]` table. */
    public val serverName: ServerName = ServerEnvironment.DEFAULT_NAME,
) {
    public val registrations: MutableList<ServiceRegistry.Registration> = mutableListOf()
    public val subscriptions: MutableList<IDomainEventBus.Subscription> = mutableListOf()

    /** Unregisters / unsubscribes everything recorded, newest first. */
    public fun close() {
        subscriptions.asReversed().forEach { runCatching { it.cancel() } }
        registrations.asReversed().forEach { runCatching { it.unregister() } }
        subscriptions.clear()
        registrations.clear()
    }
}
