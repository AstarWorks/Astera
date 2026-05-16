package com.astarworks.astera.platform.paper

import com.astarworks.astera.adapter.minecraftapi.binding.McPlayerGateway
import com.astarworks.astera.adapter.minecraftapi.event.IMcEvent
import com.astarworks.astera.adapter.minecraftapi.item.IMcItemTemplate
import com.astarworks.astera.adapter.paper.command.PaperCommandHandler
import com.astarworks.astera.adapter.paper.event.BukkitEventAdapter
import com.astarworks.astera.adapter.paper.event.PaperBroadcaster
import com.astarworks.astera.adapter.paper.scheduler.PaperScheduler
import com.astarworks.astera.adapter.paper.server.PaperServer
import com.astarworks.astera.application.command.ArgumentType
import com.astarworks.astera.application.command.CommandResult
import com.astarworks.astera.application.command.CommandSpec
import com.astarworks.astera.application.i18n.LanguageLoader
import com.astarworks.astera.application.i18n.SimpleMessageRenderer
import com.astarworks.astera.application.service.InMemoryCooldownTracker
import com.astarworks.astera.application.service.MutableItemRegistry
import com.astarworks.astera.application.service.MutableSkillRegistry
import com.astarworks.astera.application.service.MutableStatusRegistry
import com.astarworks.astera.application.service.MutableWeaponRegistry
import com.astarworks.astera.application.service.SkillLoaderService
import com.astarworks.astera.application.service.StatusEffectLoaderService
import com.astarworks.astera.application.service.WeaponLoaderService
import com.astarworks.astera.application.service.YamlElementMatrix
import com.astarworks.astera.application.usecase.FireWeaponUseCase
import com.astarworks.astera.application.usecase.GiveWeaponUseCase
import com.astarworks.astera.domain.model.i18n.MessageKey
import com.astarworks.astera.domain.model.item.ItemId
import com.astarworks.astera.domain.model.weapon.WeaponSpec
import org.bukkit.plugin.java.JavaPlugin
import java.nio.file.Path
import java.nio.file.Paths

/**
 * JavaPlugin entrypoint. Wires the dependency graph and bridges Bukkit lifecycle
 * to Astera's use cases.
 *
 * Phase 1 uses manual constructor wiring instead of Koin: with only a handful of
 * services the boilerplate is honest about what's happening. Koin moves in once
 * the module list grows past ~5 use cases (Phase 2).
 */
class AsteraPlugin : JavaPlugin() {

    private lateinit var weaponRegistry: MutableWeaponRegistry
    private lateinit var itemRegistry: MutableItemRegistry<IMcItemTemplate>
    private lateinit var giveWeapon: GiveWeaponUseCase
    private lateinit var fireWeapon: FireWeaponUseCase
    private lateinit var cooldowns: InMemoryCooldownTracker
    private lateinit var messages: SimpleMessageRenderer
    private lateinit var mcServer: PaperServer

    override fun onEnable() {
        logger.info("Astera enabling...")

        val contentDir = resolveContentDir()
        logger.info("Content directory: $contentDir")

        // Vendor-neutral abstraction over Paper server / scheduler.
        mcServer = PaperServer(this.server)
        val scheduler = PaperScheduler(this)

        // i18n.
        val languages = LanguageLoader.loadFrom(contentDir.resolve("languages"))
        messages = SimpleMessageRenderer(
            languages = languages,
            localeResolver = { playerId -> playerId?.let { mcServer.findPlayer(it)?.locale } },
        )

        // Item registry — Stage 1 D primary key for every item reference.
        itemRegistry = MutableItemRegistry(
            mapper = { cfg ->
                val externalKey = cfg.externalKey
                if (externalKey != null) {
                    IMcItemTemplate.External(ItemId(cfg.id), externalKey)
                } else {
                    IMcItemTemplate.Vanilla(
                        id = ItemId(cfg.id),
                        material = cfg.material!!,
                        customModelData = cfg.customModelData,
                        displayName = cfg.displayNameKey?.let(::MessageKey),
                        lore = cfg.loreKeys.map(::MessageKey),
                        pdcTags = cfg.pdcTags,
                    )
                }
            },
        )
        val itemReport = itemRegistry.loadFrom(contentDir.resolve("items"))
        logger.info("Items loaded: ${itemReport.loaded} ok, ${itemReport.failed} failed")

        // Outbound port bindings.
        val playerGateway = McPlayerGateway(mcServer, messages, itemRegistry)
        val broadcaster = PaperBroadcaster()
        cooldowns = InMemoryCooldownTracker(scheduler)

        // Weapon registry (populated from content/weapons/*.yaml).
        weaponRegistry = MutableWeaponRegistry()
        val report = WeaponLoaderService(weaponRegistry).loadFrom(contentDir.resolve("weapons"))
        logger.info("Weapons loaded: ${report.loaded} ok, ${report.failed} failed")

        // Stage 1 J — Skill / status registries. Loaded at startup; Phase 2 mid
        // Batch 3 wires them into use cases.
        val skillRegistry = MutableSkillRegistry()
        val skillReport = SkillLoaderService(skillRegistry).loadFrom(contentDir.resolve("skills"))
        logger.info("Skills loaded: ${skillReport.loaded} ok, ${skillReport.failed} failed")

        val statusRegistry = MutableStatusRegistry()
        val statusReport = StatusEffectLoaderService(statusRegistry).loadFrom(contentDir.resolve("status"))
        logger.info("Status effects loaded: ${statusReport.loaded} ok, ${statusReport.failed} failed")

        // Stage 1 F — Element matrix. Feeds DamageRule.resolve when use cases
        // need to apply attribute-vs-attribute multipliers. Phase 2 mid Batch 3
        // adds a use case that uses this; Phase 1 just keeps it loaded.
        val elementMatrix = YamlElementMatrix.loadFromFile(
            contentDir.resolve("balance").resolve("element-matrix.yaml"),
        )
        logger.info("Element matrix loaded: ${elementMatrix::class.simpleName}")

        // Use cases.
        giveWeapon = GiveWeaponUseCase(weaponRegistry, playerGateway, messages)
        fireWeapon = FireWeaponUseCase(broadcaster, weaponRegistry)

        // Bridge Bukkit events into the application layer.
        // Stage 1 interim: detect the player's held material and look up the
        // weapon whose registered item template uses the same vanilla material.
        // Stage 2 H will replace this with a PersistentDataContainer tag written
        // at give-time, so multiple weapons can share the same vanilla material.
        val listener = BukkitEventAdapter { event ->
            when (event) {
                is IMcEvent.PlayerLeftClickAir, is IMcEvent.PlayerLeftClickBlock -> handleFire(event)
            }
        }
        server.pluginManager.registerEvents(listener, this)

        // Stage 1 E — declarative command tree via PaperCommandHandler.
        val commandHandler = PaperCommandHandler(this, scheduler)
        commandHandler.register(buildAsteraCommandSpec())

        logger.info("Astera enabled.")
    }

    override fun onDisable() {
        logger.info("Astera disabling.")
    }

    private fun buildAsteraCommandSpec(): CommandSpec.Literal = CommandSpec.Literal(
        name = "astera",
        description = MessageKey("astera.command.root"),
        subcommands = listOf(
            CommandSpec.Literal(
                name = "give",
                description = MessageKey("astera.command.give"),
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
                        CommandResult.Failure(MessageKey("astera.command.give.failed"))
                    }
                },
            ),
        ),
    )

    private fun handleFire(event: IMcEvent) {
        val player = event.player
        val held = player.heldItemMaterialKey() ?: return
        val weapon: WeaponSpec = weaponRegistry.all().firstOrNull { spec ->
            val template = itemRegistry.resolveOrNull(spec.itemId)
            template is IMcItemTemplate.Vanilla && template.material == held
        } ?: return

        if (cooldowns.isOnCooldown(player.id, weapon.id)) {
            val remainingTicks = cooldowns.remainingTicks(player.id, weapon.id)
            val remainingSeconds = "%.1fs".format(remainingTicks / 20.0)
            player.sendMessage(
                messages.render(
                    player.id,
                    MessageKey("astera.weapon.on_cooldown"),
                    mapOf("weapon" to weapon.id.value, "remaining" to remainingSeconds),
                ),
            )
            return
        }

        cooldowns.start(player.id, weapon.id, weapon.cooldownTicks)
        fireWeapon.execute(playerId = player.id, weaponId = weapon.id, at = player.location)
    }

    private fun resolveContentDir(): Path {
        val workspaceCandidate = Paths.get(dataFolder.parentFile.parentFile.absolutePath, "content")
        if (java.nio.file.Files.isDirectory(workspaceCandidate)) return workspaceCandidate

        val serverCandidate = Paths.get(dataFolder.parentFile.parentFile.absolutePath).resolve("content")
        if (java.nio.file.Files.isDirectory(serverCandidate)) return serverCandidate

        return dataFolder.toPath().resolve("content")
    }
}
