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
import com.astarworks.astera.items.application.config.glow.GlowProfileFileYaml
import com.astarworks.astera.items.application.port.outbound.IItemDelivery
import com.astarworks.astera.items.application.port.outbound.IItemInstanceRepository
import com.astarworks.astera.items.application.port.outbound.IItemRenderer
import com.astarworks.astera.items.application.port.outbound.IRefinementRandomizer
import com.astarworks.astera.items.application.service.glow.InMemoryGlowProfileRegistry
import com.astarworks.astera.items.application.service.glow.RecordingItemRenderer
import com.astarworks.astera.items.application.service.refinement.InMemoryItemInstanceRepository
import com.astarworks.astera.items.application.service.refinement.KotlinRandomRefinementRandomizer
import com.astarworks.astera.items.application.service.refinement.RefinementProfileRegistry
import com.astarworks.astera.items.application.usecase.ApplyHeldItemGlowUseCase
import com.astarworks.astera.items.application.usecase.GiveItemUseCase
import com.astarworks.astera.items.application.usecase.HandleItemTriggerUseCase
import com.charleskorn.kaml.Yaml
import com.astarworks.astera.items.application.usecase.refinement.ApplyRefinementUseCase
import com.astarworks.astera.items.application.usecase.refinement.RerollSubStatsUseCase
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

        // ── Wave 5refine / ADR-0100 — Item Refinement Layer ──────────────
        // Per-instance state + per-item profile registry. Persistence will
        // overlay the in-memory repository at priority 100 via the V30
        // `item_instances` migration (Wave 3 / ADR-0020 pattern).
        val instanceRepo: IItemInstanceRepository =
            services.get<IItemInstanceRepository>()
                ?: InMemoryItemInstanceRepository().also {
                    registrations += services.register<IItemInstanceRepository>(it, priority = 0)
                }
        val refinementRandomizer: IRefinementRandomizer =
            services.get<IRefinementRandomizer>()
                ?: KotlinRandomRefinementRandomizer().also {
                    registrations += services.register<IRefinementRandomizer>(it, priority = 0)
                }
        val refinementProfiles = RefinementProfileRegistry()
        registrations += services.register(refinementProfiles, priority = 0)
        // The refinement use cases are exposed as services so the Wave 6
        // inspect-GUI subcommand (item-refine / item-reroll) can resolve
        // them without re-wiring. Phase 1: instances are constructed here;
        // the GUI will look them up via `services.get(...)`.
        val applyRefinement = ApplyRefinementUseCase(
            items = itemRegistry,
            instances = instanceRepo,
            eventBus = eventBus,
            refinementSpecResolver = refinementProfiles::resolve,
        )
        val rerollSubStats = RerollSubStatsUseCase(
            items = itemRegistry,
            instances = instanceRepo,
            randomizer = refinementRandomizer,
            eventBus = eventBus,
            refinementSpecResolver = refinementProfiles::resolve,
        )
        registrations += services.register(applyRefinement, priority = 0)
        registrations += services.register(rerollSubStats, priority = 0)
        logger.info(
            "Astera-Items: refinement wiring up (instance repo from " +
                "${if (services.get<IItemInstanceRepository>()?.javaClass?.simpleName == "PostgresItemInstanceRepository") "Postgres overlay" else "in-memory default"}).",
        )

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

        // ── Wave 5flex (ADR-0102 surface #1) — Item rarity glow ─────────
        // YAML-tunable per-rarity glow profile registry + in-memory
        // RecordingItemRenderer (Paper-side renderer overlays at higher
        // priority when ADR-0102 §1 Paper adapter lands in a follow-up wave).
        val glowProfiles = InMemoryGlowProfileRegistry()
        val glowFile = core.contentDir.resolve("flex").resolve("glow-profile.yaml")
        if (glowFile.toFile().exists()) {
            runCatching {
                val parsed = Yaml.default.decodeFromString(
                    GlowProfileFileYaml.serializer(),
                    glowFile.toFile().readText(Charsets.UTF_8),
                )
                glowProfiles.registerAll(parsed.toDomain())
                logger.info("Astera-Items: glow-profile.yaml loaded with ${parsed.profiles.size} override(s).")
            }.onFailure { t ->
                logger.warning("Astera-Items: glow-profile.yaml parse failed (${t.message}); using defaults.")
            }
        } else {
            logger.info("Astera-Items: glow-profile.yaml not found; using GlowProfile.DEFAULTS.")
        }
        registrations += services.register(glowProfiles, priority = 0)
        val itemRenderer: IItemRenderer =
            services.get<IItemRenderer>()
                ?: RecordingItemRenderer().also {
                    registrations += services.register<IItemRenderer>(it, priority = 0)
                }
        val applyHeldGlow = ApplyHeldItemGlowUseCase(
            items = itemRegistry,
            renderer = itemRenderer,
            profiles = glowProfiles,
        )
        registrations += services.register(applyHeldGlow, priority = 0)

        // Command tree.
        commandHandler.register(
            CommandSpec.Literal(
                name = "astera-items",
                description = MessageKey("astera.items.command.root"),
                subcommands = listOf(giveItemSubcommand(giveItem, permService)),
            ),
        )

        // Wave E 5admin-ops (ADR-0122) — `/astera-give` flat top-level alias.
        commandHandler.register(
            com.astarworks.astera.items.command.admin.adminGiveItemFlatCommand(giveItem, permService),
        )

        logger.info("Astera-Items enabled.")
    }

    override fun onDisable() {
        registrations.forEach { it.unregister() }
        registrations.clear()
        logger.info("Astera-Items disabled.")
    }
}
