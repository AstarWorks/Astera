package com.astarworks.astera.core.platform.wiring

import com.astarworks.astera.core.application.port.outbound.IMatchConfigService
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.match.MatchConfig
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.world.WeatherState
import com.astarworks.astera.core.domain.world.WorldDifficulty
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.testfixtures.FakeBroadcaster
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeGuiAdapter
import com.astarworks.astera.testfixtures.FakeMcServer
import com.astarworks.astera.testfixtures.FakeScheduler
import com.astarworks.astera.testfixtures.FakeWorldRulesGateway
import com.astarworks.astera.testfixtures.FakeWorldTimeGateway
import com.astarworks.astera.testfixtures.FakeWorldWeatherGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.logging.Logger

class MatchConfigWiringTest {
    @TempDir
    lateinit var content: Path

    @Test
    fun `match configuration writes through host world gateways`() {
        val services = ServiceRegistry()
        val rules = FakeWorldRulesGateway()
        val time = FakeWorldTimeGateway()
        val weather = FakeWorldWeatherGateway()
        val ctx = WiringContext(services, content, Logger.getAnonymousLogger())
        val state = CoreWiringState(ctx, CorePlatformPorts(
            mcServer = FakeMcServer(), scheduler = FakeScheduler(), broadcaster = FakeBroadcaster(),
            guiAdapter = GuiAdapterFactory { _, _, _ -> FakeGuiAdapter() },
            worldRulesGateway = rules, worldTimeGateway = time, worldWeatherGateway = weather,
        ))
        state.eventBus = FakeDomainEventBus()
        state.wireHudAndWorld()
        val service = services.getOrThrow<IMatchConfigService>()
        val world = WorldId("arena")
        val match = MatchId("test-match")
        val config = MatchConfig(pvpEnabled = false, keepInventoryOnDeath = true,
            difficulty = WorldDifficulty.HARD, timeMode = 18000, weatherMode = WeatherState.Clear)

        assertThat(service.apply(match, world, config).isSuccess).isTrue()
        assertThat(rules.isPvP(world)).isFalse()
        assertThat(rules.isKeepInventoryOnDeath(world)).isTrue()
        assertThat(rules.getDifficulty(world)).isEqualTo(WorldDifficulty.HARD)
        assertThat(time.getCurrentTime(world)).isEqualTo(18000L)
        assertThat(time.isTimeFrozen(world)).isTrue()
        assertThat(weather.getCurrentWeather(world)).isEqualTo(WeatherState.Clear)
        assertThat(weather.isWeatherFrozen(world)).isTrue()
        service.resetToDefault(match)
        assertThat(rules.isPvP(world)).isTrue()
        assertThat(time.isTimeFrozen(world)).isFalse()
        assertThat(weather.isWeatherFrozen(world)).isFalse()
    }
}
