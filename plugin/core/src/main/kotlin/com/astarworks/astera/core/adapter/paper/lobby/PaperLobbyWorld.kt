package com.astarworks.astera.core.adapter.paper.lobby

import com.astarworks.astera.core.adapter.paper.arena.ArenaPaster
import com.astarworks.astera.core.application.config.LobbyWorldYamlConfig
import com.astarworks.astera.core.application.port.outbound.*
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.lobby.LobbyBuildPlan
import com.astarworks.astera.core.domain.stage.ArenaBlockPos
import com.astarworks.astera.core.domain.stage.ArenaBuildPlan
import com.astarworks.astera.core.domain.world.WorldId
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.World
import org.bukkit.block.Sign
import org.bukkit.block.sign.Side
import org.bukkit.plugin.java.JavaPlugin
import org.slf4j.LoggerFactory
import kotlin.time.Duration

/** The single lobby occupies reserved slot zero of the boot-loaded world. */
internal class PaperLobbyWorld(
    private val plugin: JavaPlugin,
    private val messages: IMessageRenderer,
    private val scheduler: IScheduler,
    private val slots: com.astarworks.astera.core.application.service.instance.SlotAllocator,
) {
    @Volatile var ready: Boolean = false
        private set
    private val log = LoggerFactory.getLogger(javaClass)
    private val paster = ArenaPaster(plugin, scheduler,
        (System.getenv("ASTERA_PASTE_BLOCKS_PER_TICK")?.toIntOrNull() ?: 4096).also { require(it > 0) })

    fun install(config: LobbyWorldYamlConfig, plan: LobbyBuildPlan, force: Boolean = false,
                onReady: () -> Unit = {}): World? {
        val world = plugin.server.getWorld(config.id) ?: return null
        if (ready && !force) return world
        ready = false
        slots.state(-1, com.astarworks.astera.core.application.service.instance.SlotAllocator.State.Building)
        val blocks = plan.blocks.mapKeys { (pos, _) -> ArenaBlockPos(pos.x, pos.y, pos.z) }
        val arena = ArenaBuildPlan(blocks, emptySet(), config.surfaceY, emptyList())
        paster.paste(world, arena).thenAccept { result ->
            if (result is Result.Failure) {
                log.error("Lobby slot paste failed: {}", result.error)
                return@thenAccept
            }
            val signs = plan.signs.map { sign ->
                val done = java.util.concurrent.CompletableFuture<Void>()
                scheduler.run(ExecutionScope.Region(WorldId(world.name), sign.pos.x shr 4, sign.pos.z shr 4), Duration.ZERO) {
                    runCatching {
                        val state = world.getBlockAt(sign.pos.x, sign.pos.y, sign.pos.z).state as? Sign
                        if (state != null) {
                            val side = state.getSide(Side.FRONT)
                            sign.lines.forEachIndexed { i, key -> side.line(i, MiniMessage.miniMessage().deserialize(
                                if (key == null) "" else messages.render(null, key, sign.placeholders))) }
                            state.isWaxed = true
                            state.update(true, false)
                        }
                        done.complete(null)
                    }.onFailure { done.completeExceptionally(it) }
                }.onRetired { done.completeExceptionally(IllegalStateException("Lobby sign region retired")) }
                done
            }
            java.util.concurrent.CompletableFuture.allOf(*signs.toTypedArray()).whenComplete { _, failure ->
                scheduler.run(ExecutionScope.Global, Duration.ZERO) {
                    if (failure == null) {
                        slots.state(-1, com.astarworks.astera.core.application.service.instance.SlotAllocator.State.Ready)
                        ready = true
                        log.info("Lobby slot ready: {} blocks, {} signs", blocks.size, signs.size)
                        onReady()
                    } else log.error("Lobby sign paste failed", failure)
                }
            }
        }
        return world
    }
}
