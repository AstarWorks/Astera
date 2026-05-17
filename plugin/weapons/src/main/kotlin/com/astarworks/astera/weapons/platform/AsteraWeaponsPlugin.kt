package com.astarworks.astera.weapons.platform

import com.astarworks.astera.core.adapter.api.item.IMcItemTemplate
import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.adapter.paper.event.BukkitEventAdapter
import com.astarworks.astera.core.application.command.ArgumentType
import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.command.requirePermission
import com.astarworks.astera.core.application.port.inbound.ICommandHandler
import com.astarworks.astera.core.application.port.outbound.IBroadcaster
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.platform.AsteraCorePlugin
import com.astarworks.astera.weapons.adapter.PaperWeaponDelivery
import com.astarworks.astera.weapons.adapter.WeaponClickListener
import com.astarworks.astera.core.application.port.outbound.ICooldownTracker
import com.astarworks.astera.core.application.service.InMemoryCooldownTracker
import com.astarworks.astera.weapons.application.port.outbound.IWeaponDelivery
import com.astarworks.astera.weapons.application.port.outbound.IWeaponRegistry
import com.astarworks.astera.weapons.application.service.MutableWeaponRegistry
import com.astarworks.astera.weapons.application.service.WeaponLoaderService
import com.astarworks.astera.weapons.application.usecase.FireWeaponUseCase
import com.astarworks.astera.weapons.application.usecase.GiveWeaponUseCase
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

        // 3. Build Weapons-owned impls -----------------------------------------
        val weaponRegistry = MutableWeaponRegistry()
        val weaponReport = WeaponLoaderService(weaponRegistry).loadFrom(core.contentDir.resolve("weapons"))
        logger.info("Weapons loaded: ${weaponReport.loaded} ok, ${weaponReport.failed} failed")

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

        // 7. /astera-weapons give command --------------------------------------
        commandHandler.register(buildGiveCommandSpec(giveWeapon, permService))

        logger.info("Astera-Weapons enabled.")
    }

    override fun onDisable() {
        registrations.forEach { it.unregister() }
        registrations.clear()
        logger.info("Astera-Weapons disabled.")
    }

    private fun buildGiveCommandSpec(
        giveWeapon: GiveWeaponUseCase,
        permService: IPermissionService,
    ): CommandSpec.Literal = CommandSpec.Literal(
        name = "astera-weapons",
        description = MessageKey("astera.weapons.command.root"),
        subcommands = listOf(
            CommandSpec.Literal(
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
            ),
        ),
    )
}
