package com.astarworks.astera.items.platform

import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.adapter.paper.event.BukkitEventAdapter
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.contents.ItemContents
import com.astarworks.astera.core.application.port.inbound.ICommandHandler
import com.astarworks.astera.core.application.port.outbound.ICooldownTracker
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.IReloadableContentsRegistry
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.service.InMemoryCooldownTracker
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.platform.AsteraCorePlugin
import com.astarworks.astera.items.adapter.paper.EnchantmentGateListener
import com.astarworks.astera.items.adapter.paper.IPaperItemIdResolver
import com.astarworks.astera.items.adapter.paper.ItemSkillTriggerListener
import com.astarworks.astera.items.adapter.paper.PaperItemDelivery
import com.astarworks.astera.items.adapter.paper.PaperItemIdResolver
import com.astarworks.astera.items.adapter.paper.PaperItemMaterializer
import com.astarworks.astera.items.application.port.outbound.IItemDelivery
import com.astarworks.astera.items.application.usecase.GiveItemUseCase
import com.astarworks.astera.items.application.usecase.HandleItemTriggerUseCase
import com.astarworks.astera.items.command.giveItemSubcommand
import org.bukkit.plugin.java.JavaPlugin

/**
 * Astera-Items plugin entrypoint (Wave _items-1, ADR-0073).
 *
 * Item-centric replacement for the legacy `AsteraWeaponsPlugin`. Resolves
 * cross-plugin ports from Astera-Core's [ServiceRegistry], wires the
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

    private val registrations = mutableListOf<ServiceRegistry.Registration>()

    override fun onEnable() {
        logger.info("Astera-Items enabling…")

        val core = (server.pluginManager.getPlugin("Astera-Core") as? AsteraCorePlugin)
            ?: run {
                logger.severe("Astera-Core not found; disabling Astera-Items.")
                server.pluginManager.disablePlugin(this); return
            }
        val services = core.services

        // Shared ports.
        val scheduler = services.getOrThrow<IScheduler>()
        val messages = services.getOrThrow<IMessageRenderer>()
        val playerGateway = services.getOrThrow<IPlayerGateway>()
        val commandHandler = services.getOrThrow<ICommandHandler>()
        val permService = services.getOrThrow<IPermissionService>()
        val mcServer = services.getOrThrow<IMcServer>()
        val eventBus = services.getOrThrow<IDomainEventBus>()

        // Item registry (Worker 1: ItemContents.bootstrap-ed onto content/items/).
        // Items-side bootstrap: replaces Core-side ItemContents wiring since
        // Stage 1 D moved item-loading to the dedicated plugin. Phase C
        // orchestrator switches Core to no-op item wiring.
        val itemsContentDir = core.contentDir.resolve("items")
        val itemContents = ItemContents()
        val itemReport = itemContents.bootstrap(itemsContentDir)
        logger.info("Items loaded: ${itemReport.loaded} ok, ${itemReport.failed} failed")
        val itemRegistry: IItemRegistry = itemContents

        // Register on the cross-plugin reload dispatcher so operators can
        // hot-swap items via `/astera reload items`.
        val reloadRegistry = services.getOrThrow<IReloadableContentsRegistry>()
        reloadRegistry.register(itemContents, itemsContentDir)

        // Cooldown tracker — shared with Combat (the registry returns
        // whichever impl was registered first; in-memory at priority 0).
        val cooldowns: ICooldownTracker =
            services.get<ICooldownTracker>()
                ?: InMemoryCooldownTracker(scheduler).also {
                    registrations += services.register<ICooldownTracker>(it, priority = 0)
                }

        // Paper-side item adapters.
        val materializer = PaperItemMaterializer(plugin = this, messages = messages)
        val itemDelivery: IItemDelivery = PaperItemDelivery(materializer, server)
        val itemIdResolver: IPaperItemIdResolver = PaperItemIdResolver(this, server)

        // Items-owned ports.
        registrations += services.register<IItemRegistry>(itemRegistry, priority = 10)
        registrations += services.register<IItemDelivery>(itemDelivery)
        registrations += services.register<IPaperItemIdResolver>(itemIdResolver)

        // Use cases.
        val giveItem = GiveItemUseCase(
            items = itemRegistry,
            players = playerGateway,
            server = mcServer,
            delivery = itemDelivery,
            msg = messages,
        )
        val handleTrigger = HandleItemTriggerUseCase(
            items = itemRegistry,
            cooldowns = cooldowns,
            eventBus = eventBus,
        )

        // Click → trigger sink (vendor-neutral).
        val triggerListener = ItemSkillTriggerListener(
            handleTrigger = handleTrigger,
            itemIdResolver = itemIdResolver,
        )
        val bukkitAdapter = BukkitEventAdapter { event -> triggerListener.handle(event) }
        server.pluginManager.registerEvents(bukkitAdapter, this)

        // Enchant-gate + keep-on-death listener (Bukkit-native).
        server.pluginManager.registerEvents(EnchantmentGateListener(this), this)

        // Command tree.
        commandHandler.register(
            CommandSpec.Literal(
                name = "astera-items",
                description = MessageKey("astera.items.command.root"),
                subcommands = listOf(giveItemSubcommand(giveItem, permService)),
            ),
        )

        logger.info("Astera-Items enabled.")
    }

    override fun onDisable() {
        registrations.forEach { it.unregister() }
        registrations.clear()
        logger.info("Astera-Items disabled.")
    }
}
