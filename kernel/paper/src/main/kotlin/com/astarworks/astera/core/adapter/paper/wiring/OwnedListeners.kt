package com.astarworks.astera.core.adapter.paper.wiring

import com.astarworks.astera.core.application.wiring.WiringTag
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Bukkit listener registration as one wiring generation sees it (ADR-0311): every listener goes through [register]
 * and is recorded under [tag], so [unregisterAll] removes exactly this generation's listeners (per listener, not
 * `HandlerList.unregisterAll(plugin)`, so two generations can coexist during a swap).
 */
public class OwnedListeners(private val plugin: Plugin, public val tag: WiringTag) {

    private val live: MutableList<Listener> = CopyOnWriteArrayList()

    public val activeCount: Int get() = live.size

    public fun <L : Listener> register(listener: L): L {
        plugin.server.pluginManager.registerEvents(listener, plugin)
        live += listener
        return listener
    }

    public fun unregisterAll() {
        live.toList().forEach { HandlerList.unregisterAll(it) }
        live.clear()
    }
}
