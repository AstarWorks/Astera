package com.astarworks.astera.core.application.wiring

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.domain.gui.GuiIntent
import java.util.concurrent.ConcurrentHashMap

/**
 * The GUI intent router as one wiring generation sees it (ADR-0311): records every handler under [tag],
 * so [unregisterAll] removes exactly what this generation registered.
 */
public class OwnedGuiIntents(private val inner: IGuiIntentRouter, public val tag: WiringTag) : IGuiIntentRouter {

    private val live: MutableSet<ServiceRegistry.Registration> = ConcurrentHashMap.newKeySet()

    public val activeCount: Int get() = live.size

    override fun register(prefix: String, handler: (GuiIntent, IMcPlayer) -> Unit): ServiceRegistry.Registration {
        val raw = inner.register(prefix, handler)
        val tracked = object : ServiceRegistry.Registration {
            override fun unregister() {
                raw.unregister()
                live.remove(this)
            }
            override val isActive: Boolean get() = raw.isActive
        }
        live += tracked
        return tracked
    }

    override fun dispatch(player: IMcPlayer, intent: GuiIntent) {
        inner.dispatch(player, intent)
    }

    override fun hasHandler(key: String): Boolean = inner.hasHandler(key)

    public fun unregisterAll() {
        live.toList().forEach { it.unregister() }
    }
}
