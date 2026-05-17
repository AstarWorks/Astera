package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.domain.gui.GuiIntent
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Default implementation of [IGuiIntentRouter] (ADR-0021 / Wave 4 R3).
 *
 * # Dispatch policy
 *
 * Longest-prefix match: among all registered handlers whose [Entry.prefix] is a
 * `startsWith` of the incoming [GuiIntent.key], the one with the longest prefix
 * wins. Ties (identical prefix) resolve to first-registered, which falls out
 * naturally from preserving insertion order in [entries]. Unmatched intents log
 * at WARN and are dropped — never thrown — so a misconfigured GUI doesn't
 * crash the click listener.
 *
 * # Built-in handler
 *
 * `"gui.close"` is self-registered in the `init` block; it closes the player's
 * current GUI through [IGuiAdapter]. Feature plugins MUST NOT shadow this
 * prefix (the [IGuiIntentRouter] doc-comment is authoritative on that).
 *
 * # Lazy adapter wiring
 *
 * The adapter is supplied via [adapterProvider] (a `() -> IGuiAdapter` lambda)
 * because the router and adapter are constructed in a circular order during
 * plugin enable: the router needs to call `adapter.close(player)` from the
 * built-in close handler, but the adapter does not depend on the router.
 * Phase C wiring passes `{ guiAdapter }` after both are constructed so the
 * lambda is only ever invoked at click time when both are live.
 *
 * # Classloader retention
 *
 * Handlers are registered as bare lambdas; per [IGuiIntentRouter] caller
 * contract, plugins MUST call `Registration.unregister()` on their `onDisable`
 * to release retention of their classloader.
 */
public class DefaultGuiIntentRouter(
    private val adapterProvider: () -> IGuiAdapter,
) : IGuiIntentRouter {

    private val log = LoggerFactory.getLogger(this::class.java)

    private data class Entry(
        val prefix: String,
        val handler: (GuiIntent, IMcPlayer) -> Unit,
    )

    private val entries: MutableList<Entry> = CopyOnWriteArrayList()

    /**
     * Strong reference to the built-in `gui.close` registration so it is not
     * eligible for GC even if no other caller holds the [ServiceRegistry.Registration].
     */
    @Suppress("unused")
    private val builtinCloseRegistration: ServiceRegistry.Registration =
        register("gui.close") { _, player -> adapterProvider().close(player) }

    override fun register(
        prefix: String,
        handler: (GuiIntent, IMcPlayer) -> Unit,
    ): ServiceRegistry.Registration {
        val entry = Entry(prefix, handler)
        entries += entry
        return object : ServiceRegistry.Registration {
            override fun unregister() {
                entries.remove(entry)
            }
            override val isActive: Boolean
                get() = entries.contains(entry)
        }
    }

    override fun dispatch(player: IMcPlayer, intent: GuiIntent) {
        // Longest-prefix-wins; first-registered breaks ties since we iterate
        // in insertion order and `maxByOrNull` is stable against the first
        // element that achieves the max.
        val match = entries
            .asSequence()
            .filter { intent.key.startsWith(it.prefix) }
            .maxByOrNull { it.prefix.length }
        if (match == null) {
            log.warn("No handler registered for GuiIntent key '{}'", intent.key)
            return
        }
        match.handler(intent, player)
    }
}
