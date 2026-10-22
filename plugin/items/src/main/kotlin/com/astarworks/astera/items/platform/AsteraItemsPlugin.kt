package com.astarworks.astera.items.platform

import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.platform.AsteraCorePlugin
import com.astarworks.astera.core.platform.wiring.WiringContext
import com.astarworks.astera.items.adapter.paper.PaperItemDelivery
import com.astarworks.astera.items.adapter.paper.PaperItemIdResolver
import com.astarworks.astera.items.adapter.paper.PaperItemMaterializer
import com.astarworks.astera.items.platform.paper.ItemsPaperBindings
import com.astarworks.astera.items.platform.wiring.ItemsPlatformPorts
import com.astarworks.astera.items.platform.wiring.ItemsWiring
import org.bukkit.plugin.java.JavaPlugin

/**
 * Astera-Items plugin entrypoint (Wave _items-1, ADR-0073).
 *
 * Item-centric replacement for the legacy `AsteraWeaponsPlugin`. Resolves
 * cross-plugin ports from Astera-Core's ServiceRegistry, wires the
 * Items-owned use cases (give-item, handle-item-trigger), registers the
 * vendor-neutral click sink + Bukkit enchant-gate listener, and publishes
 * the `/astera-items give` command tree.
 *
 * Lifecycle: Bukkit guarantees Astera-Core finishes `onEnable` before this
 * plugin's `onEnable` runs (the `depend: [Astera-Core]` entry in plugin.yml).
 *
 * Konsist matrix: Items depends on Core only — no sibling-plugin imports.
 * Combat / Match are listed as `softdepend` purely so plugin load order is
 * predictable when they exist; Items doesn't read their packages.
 */
public class AsteraItemsPlugin : JavaPlugin() {

    private var wiringContext: WiringContext? = null

    override fun onEnable() {
        logger.info("Astera-Items enabling…")

        val core = (server.pluginManager.getPlugin("Astera-Core") as? AsteraCorePlugin)
            ?: run {
                logger.severe("Astera-Core not found; disabling Astera-Items.")
                server.pluginManager.disablePlugin(this); return
            }

        val ctx = WiringContext(core.services, core.contentDir, logger, core.serverEnvironment.role, core.serverEnvironment.name)
        wiringContext = ctx

        // Paper-side item adapters (ADR-0174: injected into the host-neutral wiring).
        val ports = ItemsPlatformPorts(
            itemDelivery = { services ->
                val materializer = PaperItemMaterializer(
                    plugin = this,
                    messages = services.getOrThrow<IMessageRenderer>(),
                )
                PaperItemDelivery(materializer, server)
            },
            itemIdResolver = PaperItemIdResolver(this, server),
        )

        val wired = ItemsWiring.install(ctx, ports)
        ItemsPaperBindings.register(this, wired)

        logger.info("Astera-Items enabled.")
    }

    override fun onDisable() {
        wiringContext?.close()
        wiringContext = null
        logger.info("Astera-Items disabled.")
    }
}
