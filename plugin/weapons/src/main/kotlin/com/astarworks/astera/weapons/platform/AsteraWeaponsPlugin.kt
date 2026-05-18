package com.astarworks.astera.weapons.platform

import com.astarworks.astera.core.adapter.api.item.IMcItemTemplate
import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.adapter.paper.event.BukkitEventAdapter
import com.astarworks.astera.core.application.command.ArgumentType
import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.command.requirePermission
import com.astarworks.astera.core.application.port.inbound.ICommandHandler
import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.application.port.outbound.IBroadcaster
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IGuiRegistry
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.ILoadoutRepository
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.weapons.application.service.InMemoryLoadoutRepository
import com.astarworks.astera.weapons.application.usecase.loadout.LoadoutFetchUseCase
import com.astarworks.astera.weapons.application.usecase.loadout.LoadoutSaveUseCase
import com.astarworks.astera.weapons.application.usecase.loadout.MatchStartLoadoutApplyListener
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.platform.AsteraCorePlugin
import com.astarworks.astera.weapons.adapter.PaperWeaponDelivery
import com.astarworks.astera.weapons.adapter.WeaponClickListener
import com.astarworks.astera.core.application.port.outbound.ICooldownTracker
import com.astarworks.astera.core.application.service.InMemoryCooldownTracker
import com.astarworks.astera.weapons.application.port.outbound.IWeaponDelivery
import com.astarworks.astera.weapons.application.port.outbound.IWeaponRegistry
import com.astarworks.astera.weapons.application.service.WeaponContents
import com.astarworks.astera.weapons.application.usecase.FireWeaponUseCase
import com.astarworks.astera.weapons.application.usecase.GiveWeaponUseCase
import com.astarworks.astera.weapons.application.usecase.loadout.LoadoutToggleHandler
import com.astarworks.astera.weapons.application.usecase.loadout.OpenGuiUseCase
import com.astarworks.astera.weapons.command.guiSubcommand
import org.bukkit.plugin.java.JavaPlugin

/**
 * Astera-Weapons plugin entrypoint (ADR-0022).
 *
 * Resolves shared ports from `Astera-Core`'s [ServiceRegistry], constructs
 * weapon-specific singletons (registry, cooldown tracker, delivery port,
 * use cases, click listener), and wires the `/astera-weapons give` command
 * via Core's [ICommandHandler]. Click-to-fire is registered through Core's
 * `BukkitEventAdapter` (vendor-neutral event sink + the Weapons-side
 * `WeaponClickListener`).
 *
 * Lifecycle: Bukkit guarantees `Astera-Core` finishes `onEnable` before
 * this plugin's `onEnable` runs because of the `depend: [Astera-Core]`
 * entry in `plugin.yml`. If that contract somehow breaks the plugin
 * disables itself rather than NPE on missing services.
 */
public class AsteraWeaponsPlugin : JavaPlugin() {

    private val registrations = mutableListOf<ServiceRegistry.Registration>()

    @Suppress("UNCHECKED_CAST")
    override fun onEnable() {
        logger.info("Astera-Weapons enabling…")

        // 1. Resolve Astera-Core ------------------------------------------------
        val core = (server.pluginManager.getPlugin("Astera-Core") as? AsteraCorePlugin)
            ?: run {
                logger.severe("Astera-Core not found; disabling Astera-Weapons.")
                server.pluginManager.disablePlugin(this); return
            }
        val services = core.services

        // 2. Get shared services -----------------------------------------------
        val scheduler = services.getOrThrow<IScheduler>()
        val messages = services.getOrThrow<IMessageRenderer>()
        val broadcaster = services.getOrThrow<IBroadcaster>()
        val itemRegistry = services.getOrThrow<IItemRegistry<IMcItemTemplate>>()
        val playerGateway = services.getOrThrow<IPlayerGateway>()
        val commandHandler = services.getOrThrow<ICommandHandler>()
        val permService = services.getOrThrow<IPermissionService>()
        val mcServer = services.getOrThrow<IMcServer>()
        val guiAdapter = services.getOrThrow<IGuiAdapter>()
        val guiRegistry = services.getOrThrow<IGuiRegistry>()
        val guiIntentRouter = services.getOrThrow<IGuiIntentRouter>()
        val eventBus = services.getOrThrow<IDomainEventBus>()
        // IMatchRepository is softdepend (Astera-Match plugin). Listener wires only when present.
        val matchRepository: IMatchRepository? = services.get<IMatchRepository>()

        // Wave 4.5: get-or-create the loadout repository. Postgres overlay (priority 100)
        // wins automatically when Astera-Persistence is loaded.
        val loadoutRepository: ILoadoutRepository =
            services.get<ILoadoutRepository>()
                ?: InMemoryLoadoutRepository().also {
                    registrations += services.register<ILoadoutRepository>(it, priority = 0)
                }

        // 3. Build Weapons-owned impls -----------------------------------------
        // Wave _infra-2a: WeaponContents (Contents<WeaponId, WeaponSpec> +
        // ReloadableContents) replaces the legacy MutableWeaponRegistry +
        // WeaponLoaderService pair. bootstrap() is best-effort; reload() is
        // wired via the /astera-weapons reload subcommand below.
        val weaponContents = WeaponContents()
        val weaponReport = weaponContents.bootstrap(core.contentDir.resolve("weapons"))
        logger.info("Weapons loaded: ${weaponReport.loaded} ok, ${weaponReport.failed} failed")
        val weaponRegistry: IWeaponRegistry = weaponContents

        val cooldowns = InMemoryCooldownTracker(scheduler)
        val delivery: IWeaponDelivery = PaperWeaponDelivery(itemRegistry)

        // 4. Register Weapons ports onto the shared registry -------------------
        registrations += services.register<IWeaponRegistry>(weaponRegistry)
        registrations += services.register<ICooldownTracker>(cooldowns)
        registrations += services.register<IWeaponDelivery>(delivery)

        // 5. Use cases ----------------------------------------------------------
        val fireWeapon = FireWeaponUseCase(broadcaster, weaponRegistry)
        val giveWeapon = GiveWeaponUseCase(
            weapons = weaponRegistry,
            players = playerGateway,
            server = mcServer,
            delivery = delivery,
            msg = messages,
        )

        // 6. Bukkit click listener → FireWeaponUseCase -------------------------
        val weaponClickListener = WeaponClickListener(
            fireWeapon = fireWeapon,
            cooldowns = cooldowns,
            weapons = weaponRegistry,
            itemRegistry = itemRegistry,
            messages = messages,
        )
        val bukkitAdapter = BukkitEventAdapter { event -> weaponClickListener.handle(event) }
        server.pluginManager.registerEvents(bukkitAdapter, this)

        // 7. GUI + Loadout use cases (Wave 4 R3 / Wave 4.5 ADR-0027) ----------
        val openGui = OpenGuiUseCase(adapter = guiAdapter, registry = guiRegistry, server = mcServer)
        val loadoutSave = LoadoutSaveUseCase(repository = loadoutRepository, eventBus = eventBus)
        val loadoutFetch = LoadoutFetchUseCase(repository = loadoutRepository)
        val loadoutToggle = LoadoutToggleHandler(
            adapter = guiAdapter,
            registry = guiRegistry,
            saveUseCase = loadoutSave,
        )
        registrations += guiIntentRouter.register("weapons.loadout.") { intent, player ->
            loadoutToggle.handle(intent, player)
        }

        // 8. MatchStartLoadoutApplyListener: apply persisted loadout on match start
        // Only when Astera-Match is loaded (softdepend); offline-safe otherwise.
        if (matchRepository != null) {
            val matchStartListener = MatchStartLoadoutApplyListener(
                fetch = loadoutFetch,
                matchRepository = matchRepository,
                server = mcServer,
                weaponRegistry = weaponRegistry,
                delivery = delivery,
                itemRegistry = itemRegistry,
            )
            eventBus.subscribe(MatchStarted::class) { event ->
                matchStartListener.handle(event)
            }
            logger.info("MatchStartLoadoutApplyListener wired (Astera-Match present).")
        } else {
            logger.info("Astera-Match not loaded; loadout-on-match-start listener skipped.")
        }

        // 9. /astera-weapons {give, gui loadout, reload} command ---------------
        // Wave _infra-2a: `reload` hot-swaps content/weapons/ atomically via
        // WeaponContents.reload(). Permission: astera.weapons.reload (admin).
        val weaponsContentDir = core.contentDir.resolve("weapons")
        commandHandler.register(
            buildRootCommandSpec(
                giveWeapon = giveWeapon,
                openGui = openGui,
                permService = permService,
                weaponContents = weaponContents,
                weaponsContentDir = weaponsContentDir,
            ),
        )

        logger.info("Astera-Weapons enabled.")
    }

    override fun onDisable() {
        registrations.forEach { it.unregister() }
        registrations.clear()
        logger.info("Astera-Weapons disabled.")
    }

    private fun buildRootCommandSpec(
        giveWeapon: GiveWeaponUseCase,
        openGui: OpenGuiUseCase,
        permService: IPermissionService,
        weaponContents: WeaponContents,
        weaponsContentDir: java.nio.file.Path,
    ): CommandSpec.Literal {
        val giveLiteral = CommandSpec.Literal(
            name = "give",
            description = MessageKey("astera.weapons.command.give"),
            subcommands = listOf(
                CommandSpec.Argument(
                    name = "target",
                    type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                ),
                CommandSpec.Argument(name = "weapon", type = ArgumentType.ItemIdArg),
            ),
            executor = { ctx ->
                val target = ctx.required<String>("target")
                val weapon = ctx.required<String>("weapon")
                val outcome = giveWeapon.execute(
                    GiveWeaponUseCase.Request(
                        invokerId = ctx.sender,
                        targetName = target,
                        weaponIdStr = weapon,
                    ),
                )
                if (outcome.isSuccess) {
                    CommandResult.Success
                } else {
                    CommandResult.Failure(MessageKey("astera.weapons.command.give.failed"))
                }
            },
        ).requirePermission(
            permissionService = permService,
            permission = Permission("astera.weapons.give"),
        )

        // Wave _infra-2a: /astera-weapons reload — hot-swap content/weapons/.
        // All-or-nothing: a single bad YAML keeps the live registry intact and
        // surfaces the failure count via the command result (CommandResult
        // currently only carries a MessageKey; the LoadReport is logged for
        // operator triage. Phase 4 may extend CommandResult with structured
        // data for richer feedback).
        val reloadLiteral = CommandSpec.Literal(
            name = "reload",
            description = MessageKey("astera.weapons.command.reload"),
            executor = { _ ->
                val report = weaponContents.reload(weaponsContentDir)
                logger.info(
                    "/astera-weapons reload: loaded=${report.loaded}, " +
                        "failed=${report.failed} (dir=$weaponsContentDir)",
                )
                if (report.failed == 0) {
                    CommandResult.Success
                } else {
                    CommandResult.Failure(MessageKey("astera.weapons.command.reload.failed"))
                }
            },
        ).requirePermission(
            permissionService = permService,
            permission = Permission("astera.weapons.reload"),
        )

        return CommandSpec.Literal(
            name = "astera-weapons",
            description = MessageKey("astera.weapons.command.root"),
            subcommands = listOf(giveLiteral, guiSubcommand(openGui, permService), reloadLiteral),
        )
    }
}
