package com.astarworks.astera.items.platform

import com.astarworks.astera.core.platform.paper.listeners
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.platform.AsteraCorePlugin
import com.astarworks.astera.core.application.wiring.WiringOwner
import com.astarworks.astera.core.platform.wiring.WiringContext
import com.astarworks.astera.items.adapter.paper.PaperItemDelivery
import com.astarworks.astera.items.adapter.paper.PaperItemCooldownSweepGateway
import com.astarworks.astera.items.adapter.paper.PaperItemIdResolver
import com.astarworks.astera.core.adapter.paper.item.PaperItemMaterializer
import com.astarworks.astera.core.adapter.paper.item.PaperItemTooltipGuard
import com.astarworks.astera.core.application.service.item.ItemBlueprintFactory
import com.astarworks.astera.items.adapter.paper.PaperTrainingInventory
import com.astarworks.astera.items.adapter.paper.PaperTrainingRackGateway
import com.astarworks.astera.items.platform.wiring.ItemsWiringResult
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.register
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
 * the `/astera items give` command tree.
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
    private var itemsResult: ItemsWiringResult? = null

    override fun onEnable() {
        logger.info("Astera-Items enabling…")

        val core = (server.pluginManager.getPlugin("Astera-Core") as? AsteraCorePlugin)
            ?: run {
                logger.severe("Astera-Core not found; disabling Astera-Items.")
                server.pluginManager.disablePlugin(this); return
            }

        val ctx = WiringContext(WiringOwner("Astera-Items"), 0, core.services, core.contentDir, logger, core.serverEnvironment.role, core.serverEnvironment.name)
        wiringContext = ctx

        // Paper-side item adapters (ADR-0174: injected into the host-neutral wiring).
        var materializer: PaperItemMaterializer? = null
        var rackFrames: PaperTrainingRackGateway? = null
        val ports = ItemsPlatformPorts(
            itemDelivery = { services ->
                val built = PaperItemMaterializer(
                    plugin = this,
                    blueprints = services.getOrThrow<ItemBlueprintFactory>(),
                )
                materializer = built
                // ADR-0241: Core's GUI renderer builds every icon through this same materializer.
                services.register<PaperItemMaterializer>(built)
                PaperItemDelivery(built, server, PaperItemIdResolver(this, server))
            },
            itemIdResolver = PaperItemIdResolver(this, server),
            trainingRack = { services ->
                val registry = services.getOrThrow<IItemRegistry>()
                PaperTrainingRackGateway(this) { id -> registry.find(id)?.let { spec -> materializer?.buildFor(spec, null, 1) } }
                    .also { rackFrames = it }
            },
            trainingInventory = { PaperTrainingInventory(server) },
            itemCooldownSweep = PaperItemCooldownSweepGateway(server),
        )

        val wired = ItemsWiring.install(ctx, ports)
        itemsResult = wired
        val listeners = ctx.listeners(this)
        PaperItemTooltipGuard { materializer }.start(this, listeners)
        ItemsPaperBindings.register(this, listeners, wired, checkNotNull(rackFrames), { core.services.get<com.astarworks.astera.core.application.port.outbound.lobby.ILobbyTrainingRange>()?.world?.value })

        logger.info("Astera-Items enabled.")
    }

    override fun onDisable() {
        itemsResult?.shutdown()
        itemsResult = null
        wiringContext?.close()
        wiringContext = null
        logger.info("Astera-Items disabled.")
    }
}
