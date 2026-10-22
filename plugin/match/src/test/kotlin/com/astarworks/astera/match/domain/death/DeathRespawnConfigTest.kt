package com.astarworks.astera.match.domain.death

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DeathRespawnConfigTest {

    @Test
    fun `empty config yields the 5s delay, 2s death cam and 3s protection defaults`() {
        val parsed = DeathRespawnConfig.parse(emptyMap())
        assertThat(parsed.config).isEqualTo(DeathRespawnConfig(5, 2, 3))
        assertThat(parsed.warnings).isEmpty()
    }

    @Test
    fun `yaml values override the defaults`() {
        val parsed = DeathRespawnConfig.parse(
            mapOf("respawn_delay_seconds" to "8", "death_cam_seconds" to "0", "spawn_protection_seconds" to "1"),
        )
        assertThat(parsed.config).isEqualTo(DeathRespawnConfig(8, 0, 1))
    }

    @Test
    fun `bad or out of range values keep the default and warn`() {
        val parsed = DeathRespawnConfig.parse(
            mapOf("respawn_delay_seconds" to "soon", "death_cam_seconds" to "-1", "spawn_protection_seconds" to "99"),
        )
        assertThat(parsed.config).isEqualTo(DeathRespawnConfig.DEFAULT)
        assertThat(parsed.warnings).hasSize(3)
    }

    @Test
    fun `death cam never outlasts the respawn delay`() {
        assertThat(DeathRespawnConfig(respawnDelaySeconds = 1, deathCamSeconds = 4).effectiveDeathCamSeconds).isEqualTo(1)
    }
}
