package com.astarworks.astera.core.adapter.paper.lobby

import com.astarworks.astera.core.application.config.LobbyWorldYamlConfig
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IWorldRulesGateway
import com.astarworks.astera.core.application.port.outbound.IWorldTimeGateway
import com.astarworks.astera.core.application.port.outbound.IWorldWeatherGateway
import com.astarworks.astera.core.domain.lobby.LobbyBuildPlan
import com.astarworks.astera.core.domain.world.WeatherState
import com.astarworks.astera.core.domain.world.WorldDifficulty
import com.astarworks.astera.core.domain.world.WorldId
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.block.Biome
import org.bukkit.block.Sign
import org.bukkit.block.data.BlockData
import org.bukkit.block.sign.Side
import org.bukkit.generator.BiomeProvider
import org.bukkit.generator.ChunkGenerator
import org.bukkit.generator.WorldInfo
import org.bukkit.plugin.java.JavaPlugin
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random

/**
 * Creates the void `lobby` world and pastes the procedural sky-island build
 * into it (ADR-0180). Idempotent: a marker file in the plugin data folder
 * records `(version, seed, centre, surfaceY)` of the last paste; when it
 * matches, the world already holds the build and nothing is placed. A changed
 * marker clears the old build's bounds before pasting the new one.
 *
 * Main thread only (called from plugin enable).
 */
internal class PaperLobbyWorld(
    private val plugin: JavaPlugin,
    private val messages: IMessageRenderer,
    private val rules: IWorldRulesGateway,
    private val time: IWorldTimeGateway,
    private val weather: IWorldWeatherGateway,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val server get() = plugin.server

    /**
     * Creates / loads the world, pastes the build when needed and applies the world rules.
     * [force] clears and pastes again even when the marker matches (admin panel repaste, ADR-0233).
     */
    fun install(config: LobbyWorldYamlConfig, plan: LobbyBuildPlan, force: Boolean = false): World? {
        val marker = markerPath()
        val expected = markerText(config, plan)
        val previous = if (Files.isRegularFile(marker)) Files.readString(marker) else null

        val world = server.getWorld(config.id) ?: server.createWorld(creator(config)) ?: run {
            log.error("Lobby world '{}' could not be created", config.id)
            return null
        }
        if (previous != expected || force) {
            if (previous != null || force) clearBounds(world, plan)
            paste(world, plan)
            Files.createDirectories(marker.parent)
            Files.writeString(marker, expected)
            log.info("Lobby world '{}' built: {} blocks, {} signs", config.id, plan.blocks.size, plan.signs.size)
        } else {
            log.info("Lobby world '{}' already built (marker {})", config.id, config.markerVersion)
        }
        world.setSpawnLocation(
            Location(world, config.spawn.x, config.spawn.y, config.spawn.z, config.spawn.yaw, config.spawn.pitch),
        )
        applyRules(config, WorldId(config.id))
        return world
    }

    private fun creator(config: LobbyWorldYamlConfig): WorldCreator =
        WorldCreator.name(config.id)
            .environment(World.Environment.NORMAL)
            .generator(VoidGenerator(config))
            .biomeProvider(PlainsOnly)
            .generateStructures(false)
            .seed(config.seed)

    private fun applyRules(config: LobbyWorldYamlConfig, id: WorldId) {
        val r = config.rules
        rules.setDifficulty(id, WorldDifficulty.PEACEFUL)
        rules.setDoMobSpawning(id, r.mobSpawning)
        rules.setDoFireTick(id, false)
        rules.setPvP(id, r.pvpEnabled)
        rules.setKeepInventoryOnDeath(id, true)
        time.setTime(id, r.timeOfDay)
        time.freezeTime(id, r.timeFrozen)
        if (r.weatherClearFixed) {
            weather.setWeather(id, WeatherState.Clear)
            weather.freezeWeather(id, true)
        }
    }

    private fun paste(world: World, plan: LobbyBuildPlan) {
        val cache = HashMap<String, BlockData?>()
        fun data(state: String): BlockData? = cache.getOrPut(state) {
            runCatching { server.createBlockData(state) }
                .onFailure { log.warn("Lobby build: invalid block data '{}': {}", state, it.message) }
                .getOrNull()
        }
        // Water last, with physics on, so fountains and falls start flowing once everything around exists.
        val (water, solid) = plan.blocks.entries.partition { it.value.startsWith("minecraft:water") }
        for ((pos, state) in solid) {
            data(state)?.let { world.getBlockAt(pos.x, pos.y, pos.z).setBlockData(it, false) }
        }
        for ((pos, state) in water) {
            data(state)?.let { world.getBlockAt(pos.x, pos.y, pos.z).setBlockData(it, true) }
        }
        val mm = MiniMessage.miniMessage()
        for (sign in plan.signs) {
            val block = world.getBlockAt(sign.pos.x, sign.pos.y, sign.pos.z)
            val state = block.state as? Sign ?: continue
            val side = state.getSide(Side.FRONT)
            sign.lines.forEachIndexed { i, key ->
                side.line(i, if (key == null) mm.deserialize("") else mm.deserialize(messages.render(null, key, sign.placeholders)))
            }
            state.isWaxed = true
            state.update(true, false)
        }
    }

    /** Wipes the previous build's bounding box (marker changed, layout may differ). */
    private fun clearBounds(world: World, plan: LobbyBuildPlan) {
        val air = server.createBlockData("minecraft:air")
        for (x in plan.minX - 8..plan.maxX + 8) {
            for (z in plan.minZ - 8..plan.maxZ + 8) {
                for (y in plan.minY - 40..plan.maxY + 12) {
                    val block = world.getBlockAt(x, y, z)
                    if (!block.type.isAir) block.setBlockData(air, false)
                }
            }
        }
    }

    private fun markerPath(): Path = plugin.dataFolder.toPath().toAbsolutePath().resolve("lobby-world.marker")

    /** The lane list is part of the marker: adding / removing a queue lane repastes the queue island. */
    private fun markerText(c: LobbyWorldYamlConfig, plan: LobbyBuildPlan): String =
        "version=${c.markerVersion} seed=${c.seed} center=${c.center.x},${c.center.z} surfaceY=${c.surfaceY} " +
            "lanes=${plan.lanePads.keys.joinToString(",")}"

    /** Empty chunks; the build is pasted afterwards. */
    private class VoidGenerator(private val config: LobbyWorldYamlConfig) : ChunkGenerator() {
        override fun getFixedSpawnLocation(world: World, random: Random): Location =
            Location(world, config.spawn.x, config.spawn.y, config.spawn.z, config.spawn.yaw, config.spawn.pitch)
    }

    private object PlainsOnly : BiomeProvider() {
        override fun getBiome(worldInfo: WorldInfo, x: Int, y: Int, z: Int): Biome = Biome.PLAINS
        override fun getBiomes(worldInfo: WorldInfo): List<Biome> = listOf(Biome.PLAINS)
    }
}
