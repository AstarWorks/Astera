package com.astarworks.astera.platform.paper

import com.astarworks.astera.adapter.minecraftapi.binding.McPlayerGateway
import com.astarworks.astera.adapter.minecraftapi.event.IMcEvent
import com.astarworks.astera.adapter.paper.event.BukkitEventAdapter
import com.astarworks.astera.adapter.paper.event.PaperBroadcaster
import com.astarworks.astera.adapter.paper.server.PaperServer
import com.astarworks.astera.application.i18n.LanguageLoader
import com.astarworks.astera.application.i18n.SimpleMessageRenderer
import com.astarworks.astera.application.service.MutableWeaponRegistry
import com.astarworks.astera.application.service.WeaponLoaderService
import com.astarworks.astera.application.usecase.FireWeaponUseCase
import com.astarworks.astera.application.usecase.GiveWeaponUseCase
import com.astarworks.astera.domain.model.player.PlayerId
import com.astarworks.astera.domain.model.weapon.WeaponId
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
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
    private lateinit var giveWeapon: GiveWeaponUseCase
    private lateinit var fireWeapon: FireWeaponUseCase

    override fun onEnable() {
        logger.info("Astera enabling...")

        val contentDir = resolveContentDir()
        logger.info("Content directory: $contentDir")

        // Vendor-neutral abstraction over Paper server.
        val mcServer = PaperServer(this.server)

        // i18n.
        val languages = LanguageLoader.loadFrom(contentDir.resolve("languages"))
        val messages = SimpleMessageRenderer(
            languages = languages,
            localeResolver = { playerId -> playerId?.let { mcServer.findPlayer(it)?.locale } },
        )

        // Outbound port bindings.
        val playerGateway = McPlayerGateway(mcServer, messages)
        val broadcaster = PaperBroadcaster()

        // Weapon registry (populated from content/weapons/*.yaml).
        weaponRegistry = MutableWeaponRegistry()
        val report = WeaponLoaderService(weaponRegistry).loadFrom(contentDir.resolve("weapons"))
        logger.info("Weapons loaded: ${report.loaded} ok, ${report.failed} failed")

        // Use cases.
        giveWeapon = GiveWeaponUseCase(weaponRegistry, playerGateway, messages)
        fireWeapon = FireWeaponUseCase(broadcaster, weaponRegistry)

        // Bridge Bukkit events into the application layer.
        val listener = BukkitEventAdapter { event ->
            val player = event.player
            // Phase 1: every left-click "fires" example-sword. Phase 2 will inspect the held item.
            fireWeapon.execute(
                playerId = player.id,
                weaponId = WeaponId("example-sword"),
                at = player.location,
            )
            when (event) {
                is IMcEvent.PlayerLeftClickAir, is IMcEvent.PlayerLeftClickBlock -> Unit
            }
        }
        server.pluginManager.registerEvents(listener, this)

        // /astera command.
        getCommand("astera")?.setExecutor(AsteraCommand())

        logger.info("Astera enabled.")
    }

    override fun onDisable() {
        logger.info("Astera disabling.")
    }

    private fun resolveContentDir(): Path {
        // Prefer the repo-root content/ when running from a workspace mount
        // (docker-compose mounts ../content into /server/content). Otherwise
        // fall back to the plugin's data folder.
        val workspaceCandidate = Paths.get(dataFolder.parentFile.parentFile.absolutePath, "content")
        if (java.nio.file.Files.isDirectory(workspaceCandidate)) return workspaceCandidate

        val serverCandidate = Paths.get(dataFolder.parentFile.parentFile.absolutePath).resolve("content")
        if (java.nio.file.Files.isDirectory(serverCandidate)) return serverCandidate

        return dataFolder.toPath().resolve("content")
    }

    private inner class AsteraCommand : CommandExecutor {
        override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
            if (args.isEmpty() || args[0] != "give") {
                sender.sendMessage("Usage: /astera give <player> <weapon-id>")
                return true
            }
            if (args.size < 3) {
                sender.sendMessage("Usage: /astera give <player> <weapon-id>")
                return true
            }
            val invokerId = (sender as? Player)?.let { PlayerId(it.uniqueId) }
            val outcome = giveWeapon.execute(
                GiveWeaponUseCase.Request(
                    invokerId = invokerId,
                    targetName = args[1],
                    weaponIdStr = args[2],
                )
            )
            if (invokerId == null) {
                sender.sendMessage("[Astera] give outcome: $outcome")
            }
            return true
        }
    }
}
