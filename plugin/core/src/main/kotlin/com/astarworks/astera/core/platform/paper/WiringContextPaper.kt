package com.astarworks.astera.core.platform.paper

import com.astarworks.astera.core.adapter.paper.wiring.OwnedListeners
import com.astarworks.astera.core.platform.wiring.WiringContext
import org.bukkit.plugin.Plugin

/**
 * The Paper half of the registration funnel (ADR-0311): the [OwnedListeners] for [plugin] under this context's tag.
 * One instance per context (cached in [WiringContext.attachments]); every Bukkit listener goes through it.
 */
public fun WiringContext.listeners(plugin: Plugin): OwnedListeners =
    attachments.getOrPut("listeners") {
        OwnedListeners(plugin, tag).also { owned ->
            onClose { owned.unregisterAll() }
            probe("listeners") { owned.activeCount }
        }
    } as OwnedListeners
