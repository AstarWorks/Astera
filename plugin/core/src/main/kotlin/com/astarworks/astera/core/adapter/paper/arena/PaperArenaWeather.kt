package com.astarworks.astera.core.adapter.paper.arena

import com.astarworks.astera.core.adapter.paper.world.PaperWorldWeatherGateway
import com.astarworks.astera.core.application.config.parser.YamlContentParser
import com.astarworks.astera.core.domain.world.WeatherState
import com.astarworks.astera.core.domain.world.WorldId
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.weather.ThunderChangeEvent
import org.bukkit.event.weather.WeatherChangeEvent
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.plugin.java.JavaPlugin
import java.nio.file.Path
import kotlin.io.path.readText

/** Physical weather is shared by every 2048-pitch arena slot; stage skies remain per-player views. */
internal class PaperArenaWeather(private val plugin: JavaPlugin, contentDir: Path) : Listener {
    private val world = WorldId("world")
    private val weather = PaperWorldWeatherGateway(plugin.server)
    // A subdirectory keeps the lobby's non-recursive worlds loader from reading arena rules as a lobby.
    private val fixed = contentDir.resolve("worlds/arena/rules.yaml").let { file ->
        val rules = YamlContentParser().parse(file.readText(), MapSerializer(String.serializer(), Boolean.serializer()))
        require(rules.keys == setOf("weather_clear_fixed")) {
            "$file: expected only weather_clear_fixed; found ${rules.keys}"
        }
        rules.getValue("weather_clear_fixed")
    }

    /** Like the lobby lock, always apply on Global, at boot and after world load. */
    fun settleWeather() {
        if (!fixed) return
        plugin.server.globalRegionScheduler.execute(plugin, Runnable {
            weather.freezeWeather(world, true)
            weather.setWeather(world, WeatherState.Clear)
        })
    }

    @EventHandler
    fun onWorldLoad(event: WorldLoadEvent) {
        if (event.world.name == world.value) settleWeather()
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onWeather(event: WeatherChangeEvent) {
        if (fixed && event.world.name == world.value && event.toWeatherState()) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onThunder(event: ThunderChangeEvent) {
        if (fixed && event.world.name == world.value && event.toThunderState()) event.isCancelled = true
    }
}
