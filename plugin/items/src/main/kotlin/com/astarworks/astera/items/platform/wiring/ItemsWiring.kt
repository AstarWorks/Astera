package com.astarworks.astera.items.platform.wiring

import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.admin.panel.AdminPanelGuard
import com.astarworks.astera.core.application.admin.panel.IAdminPanelRegistry
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.items.application.admin.AdminGiveItemsUseCase
import com.astarworks.astera.items.application.admin.AdminItemsPanel
import com.astarworks.astera.core.application.port.outbound.ICooldownTracker
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.IResourcePoolService
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.application.service.InMemoryCooldownTracker
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.platform.wiring.WiringContext
import com.astarworks.astera.items.adapter.paper.IPaperItemIdResolver
import com.astarworks.astera.items.adapter.paper.ItemSkillTriggerListener
import com.astarworks.astera.items.application.config.glow.GlowProfileFileYaml
import com.astarworks.astera.core.application.port.outbound.item.IItemDelivery
import com.astarworks.astera.items.application.port.outbound.IItemInstanceRepository
import com.astarworks.astera.items.application.port.outbound.IItemRenderer
import com.astarworks.astera.items.application.service.training.TrainingRackService
import com.astarworks.astera.items.application.port.outbound.IRefinementRandomizer
import com.astarworks.astera.items.application.service.glow.InMemoryGlowProfileRegistry
import com.astarworks.astera.items.application.service.glow.RecordingItemRenderer
import com.astarworks.astera.items.application.service.refinement.InMemoryItemInstanceRepository
import com.astarworks.astera.items.application.service.refinement.KotlinRandomRefinementRandomizer
import com.astarworks.astera.items.application.service.refinement.RefinementProfileRegistry
import com.astarworks.astera.items.application.usecase.ApplyHeldItemGlowUseCase
import com.astarworks.astera.items.application.usecase.GiveItemUseCase
import com.astarworks.astera.items.application.usecase.HandleItemTriggerUseCase
import com.astarworks.astera.items.application.usecase.SweepItemOnSkillCooldownUseCase
import com.astarworks.astera.core.domain.event.SkillCooldownStarted
import com.astarworks.astera.items.application.usecase.refinement.ApplyRefinementUseCase
import com.astarworks.astera.items.application.usecase.refinement.RerollSubStatsUseCase
import com.astarworks.astera.items.command.giveItemSubcommand
import com.astarworks.astera.items.command.refreshItemsSubcommand
import com.charleskorn.kaml.Yaml

/** What the host needs after [ItemsWiring.install]. */
public class ItemsWiringResult(
    public val itemRegistry: IItemRegistry,
    public val itemDelivery: IItemDelivery,
    public val itemIdResolver: IPaperItemIdResolver,
    public val giveItem: GiveItemUseCase,
    public val handleTrigger: HandleItemTriggerUseCase,
    /** Vendor-neutral click sink. Paper feeds it from `PaperItemInputListener`; the simulator feeds `IMcEvent`s directly. */
    public val triggerListener: ItemSkillTriggerListener,
    /** F1-D: the weapons & items section of `/astera admin panel`. */
    public val adminItemsPanel: AdminItemsPanel,
    /** F3-11: the lobby training weapon rack; `null` when the host supplied no rack gateway. */
    public val trainingRack: TrainingRackService?,
    private val shutdownHooks: List<() -> Unit>,
) {
    /** Stops the rack's repair loop and removes its frames. Subscriptions are closed by `WiringContext.close()`. */
    public fun shutdown() {
        shutdownHooks.forEach { runCatching(it) }
    }
}

/**
 * Host-neutral Items wiring (ADR-0174). Pure construction / registration /
 * command specs — no Bukkit. The Paper-only step (click bridge, enchant gate)
 * lives in `platform/paper/ItemsPaperBindings`.
 */
public object ItemsWiring {

    public fun install(ctx: WiringContext, ports: ItemsPlatformPorts): ItemsWiringResult {
        val services = ctx.services
        val logger = ctx.logger
        val registrations = ctx.registrations

        // Shared ports.
        val scheduler = services.getOrThrow<IScheduler>()
        val messages = services.getOrThrow<IMessageRenderer>()
        val playerGateway = services.getOrThrow<IPlayerGateway>()
        val commands = ctx.commandMount("Astera-Items")
        val mcServer = services.getOrThrow<IMcServer>()
        val eventBus = services.getOrThrow<IDomainEventBus>()

        // Item registry: Core already loaded content/items/ and registered it (and its
        // /astera reload entry). A second ItemContents here would be a second copy that
        // Core's consumers never see (ADR-0195), so reuse the registered one.
        val itemRegistry: IItemRegistry = services.getOrThrow<IItemRegistry>()

        // Cooldown tracker — shared with Combat (the registry returns
        // whichever impl was registered first; in-memory at priority 0).
        if (services.get<ICooldownTracker>() == null) {
            registrations += services.register<ICooldownTracker>(InMemoryCooldownTracker(scheduler), priority = 0)
        }

        // Host-side item adapters (Paper materializer / simulator fakes).
        val itemDelivery: IItemDelivery = ports.itemDelivery(services)
        val itemIdResolver: IPaperItemIdResolver = ports.itemIdResolver

        // Items-owned ports.
        registrations += services.register<IItemDelivery>(itemDelivery)
        // F2-5 (ADR-0196): the combat plugin's admin Skills page hands out skill testers through this.
        registrations += services.register<com.astarworks.astera.core.application.port.outbound.ISkillTesterDelivery>(
            com.astarworks.astera.items.application.admin.SkillTesterDelivery(itemRegistry, itemDelivery),
        )
        registrations += services.register<IPaperItemIdResolver>(itemIdResolver)
        registrations += services.register<com.astarworks.astera.core.application.port.outbound.item.IOwnedItemQuery>(
            com.astarworks.astera.core.application.port.outbound.item.IOwnedItemQuery { itemIdResolver.inventoryItemIds(it) },
        )

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
        val refinementReport = com.astarworks.astera.core.application.service.ContentLoader(
            com.astarworks.astera.core.application.service.ContentLoader.DEFAULT_PARSERS,
            com.astarworks.astera.items.application.config.refinement.RefinementProfileYaml.serializer(),
        ).loadFrom(ctx.contentDir.resolve("refinement"), refinementProfiles::registerFromYaml)
        logger.info("Refinement profiles loaded: ${refinementReport.loaded} ok, ${refinementReport.failed} failed")
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
            eventBus = eventBus,
            resourcePool = services.get<IResourcePoolService>(),
        )

        // ADR-0197 — a skill going on cooldown (primary binding / reload) runs the hotbar sweep.
        val sweepOnCooldown = SweepItemOnSkillCooldownUseCase(itemRegistry, ports.itemCooldownSweep)
        ctx.subscriptions += eventBus.subscribe(SkillCooldownStarted::class) { sweepOnCooldown.handle(it) }

        // Click → trigger sink (vendor-neutral). The host feeds it IMcEvents.
        val triggerListener = ItemSkillTriggerListener(
            handleTrigger = handleTrigger,
            itemIdResolver = itemIdResolver,
            nowTick = scheduler::currentTick,
        )

        // ── Wave 5flex (ADR-0102 surface #1) — Item rarity glow ─────────
        // YAML-tunable per-rarity glow profile registry + in-memory
        // RecordingItemRenderer (Paper-side renderer overlays at higher
        // priority when ADR-0102 §1 Paper adapter lands in a follow-up wave).
        val glowProfiles = InMemoryGlowProfileRegistry()
        val glowFile = ctx.contentDir.resolve("flex").resolve("glow-profile.yaml")
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

        // F1-D (ADR-0183): `/astera admin panel` weapons & items catalog. Core owns the
        // hub / guard / registry; this plugin contributes its tile + `admin.items.` handler.
        val adminItemsPanel = AdminItemsPanel(
            guard = services.getOrThrow<AdminPanelGuard>(),
            registry = services.getOrThrow<IAdminPanelRegistry>(),
            items = itemRegistry,
            give = AdminGiveItemsUseCase(itemRegistry, itemDelivery),
            guiAdapter = services.getOrThrow<IGuiAdapter>(),
        )
        registrations += adminItemsPanel.install(services.getOrThrow<IGuiIntentRouter>())

        // F3-11 (ADR-0200): lobby training weapon rack, claimed by right-clicking its frames.
        val shutdownHooks = mutableListOf<() -> Unit>()
        var trainingRack: TrainingRackService? = null
        val rackGateway = ports.trainingRack?.invoke(services)
        val trainingInventory = ports.trainingInventory?.invoke(services)
        if (rackGateway != null && trainingInventory != null) {
            val rack = TrainingRackService(
                range = { services.get<com.astarworks.astera.core.application.port.outbound.lobby.ILobbyTrainingRange>() },
                items = itemRegistry,
                delivery = itemDelivery,
                server = mcServer,
                poses = { services.get<com.astarworks.astera.core.application.port.outbound.IPlayerPoseLookup>() },
                inventory = trainingInventory,
                gateway = rackGateway,
                players = playerGateway,
                messages = messages,
                matches = { services.get<com.astarworks.astera.core.application.port.outbound.IMatchRepository>() },
            )
            ctx.subscriptions += eventBus.subscribe(com.astarworks.astera.core.domain.event.MatchStarted::class) { rack.onMatchStarted(it) }
            ctx.subscriptions += eventBus.subscribe(com.astarworks.astera.core.domain.event.MatchFormed::class) { rack.onMatchFormed(it) }
            // Players join a match after MatchStarted fires, so also sweep: nobody in a match keeps a training weapon.
            val sweepLoop = scheduler.runRepeatingTicks(initialDelayTicks = 20L, periodTicks = 20L) { rack.sweep() }
            shutdownHooks += { sweepLoop.cancel() }
            // The lobby world does not exist yet at enable time (it is created on ServerLoad); the loop also repairs lost frames.
            val rackLoop = scheduler.runRepeatingTicks(initialDelayTicks = 40L, periodTicks = 100L) { rack.ensureRendered() }
            shutdownHooks += { rackLoop.cancel() }
            shutdownHooks += { rackGateway.removeAll() }
            trainingRack = rack
        }

        // Command tree.
        // `/astera items give <target> <item> [amount]` (ADR-0184).
        commands.register("items", giveItemSubcommand(giveItem, itemRegistry))
        commands.register("items", refreshItemsSubcommand(itemDelivery, itemRegistry))

        // ── Wave M 5polish-quickwin (ADR-0130 §D) ────────────────────────
        // Sample tooltip providers:
        //  (1) RarityGlowTooltipProvider — every item, rarity badge line.
        //  (2) RefinementTierTooltipProvider — Rare/Epic/Legendary only,
        //      surfaces the viewer's highest refinement layer count.
        // Both register against Core's ITooltipRegistry, which fires the
        // matching scope per item / viewer on every tooltip render.
        val tooltipRegistry = services.get<
            com.astarworks.astera.core.application.port.outbound.guidepth.ITooltipRegistry,
            >()
        if (tooltipRegistry != null) {
            val rarityGlow = com.astarworks.astera.items.application.service.tooltip
                .RarityGlowTooltipProvider(itemRegistry)
            tooltipRegistry.register(
                com.astarworks.astera.core.domain.guidepth.TooltipScope.AllItems,
                rarityGlow,
            )

            val refinementTier = com.astarworks.astera.items.application.service.tooltip
                .RefinementTierTooltipProvider(instanceRepo)
            // Rare-and-up: register once per qualifying rarity tier.
            for (rare in listOf(
                com.astarworks.astera.core.domain.item.Rarity.RARE,
                com.astarworks.astera.core.domain.item.Rarity.EPIC,
                com.astarworks.astera.core.domain.item.Rarity.LEGENDARY,
            )) {
                tooltipRegistry.register(
                    com.astarworks.astera.core.domain.guidepth.TooltipScope.ByRarity(rare),
                    refinementTier,
                )
            }
            logger.info(
                "Wave M 5polish-quickwin (ADR-0130 §D) wired: " +
                    "RarityGlowTooltipProvider (AllItems) + " +
                    "RefinementTierTooltipProvider (Rare/Epic/Legendary).",
            )
        } else {
            logger.warning(
                "Wave M 5polish-quickwin (ADR-0130 §D) skipped: " +
                    "ITooltipRegistry not registered in core.",
            )
        }

        return ItemsWiringResult(
            itemRegistry = itemRegistry,
            itemDelivery = itemDelivery,
            itemIdResolver = itemIdResolver,
            giveItem = giveItem,
            handleTrigger = handleTrigger,
            triggerListener = triggerListener,
            adminItemsPanel = adminItemsPanel,
            trainingRack = trainingRack,
            shutdownHooks = shutdownHooks,
        )
    }
}
