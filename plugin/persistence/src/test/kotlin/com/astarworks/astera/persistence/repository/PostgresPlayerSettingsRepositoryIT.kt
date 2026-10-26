package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.player.ControlScheme
import com.astarworks.astera.core.domain.player.ParticleVisibility
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerSettings
import com.astarworks.astera.persistence.harness.PostgresIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Round-trip IT for [PostgresPlayerSettingsRepository] (Wave 5e / RTM issue
 * #15) against real Postgres via Testcontainers. Exercises every sealed
 * variant of [ControlScheme] / [ParticleVisibility] to confirm the
 * JSONB sealed-by-key codec round-trips both ways.
 */
public class PostgresPlayerSettingsRepositoryIT : PostgresIntegrationTestBase() {

    @Test
    public fun `round-trips all four typed fields with non-default variants`() {
        val repo = PostgresPlayerSettingsRepository(db)
        val id = PlayerId(UUID.randomUUID())
        val settings = PlayerSettings(
            playerId = id,
            controlScheme = ControlScheme.Button,
            particleVisibility = ParticleVisibility.OwnTeamOnly,
            doubleJumpEnabled = false,
            quickCastSlots = mapOf(1 to "astera:fireball", 4 to "astera:dash", 9 to "astera:heal"),
        )

        repo.upsert(settings)
        val found = repo.find(id)

        assertThat(found).isNotNull
        assertThat(found!!.controlScheme).isEqualTo(ControlScheme.Button)
        assertThat(found.particleVisibility).isEqualTo(ParticleVisibility.OwnTeamOnly)
        assertThat(found.doubleJumpEnabled).isFalse
        assertThat(found.quickCastSlots).containsEntry(1, "astera:fireball")
        assertThat(found.quickCastSlots).containsEntry(4, "astera:dash")
        assertThat(found.quickCastSlots).containsEntry(9, "astera:heal")
    }

    @Test
    public fun `findOrDefault returns the domain default when no row exists`() {
        val repo = PostgresPlayerSettingsRepository(db)
        val id = PlayerId(UUID.randomUUID())

        val result = repo.findOrDefault(id)

        assertThat(result).isEqualTo(PlayerSettings.default(id))
    }

    @Test
    public fun `ParticleVisibility OwnOnly round-trips`() {
        val repo = PostgresPlayerSettingsRepository(db)
        val id = PlayerId(UUID.randomUUID())
        repo.upsert(PlayerSettings(id, particleVisibility = ParticleVisibility.OwnOnly))

        assertThat(repo.find(id)!!.particleVisibility).isEqualTo(ParticleVisibility.OwnOnly)
    }

    @Test
    public fun `ControlScheme RightClick (default) round-trips`() {
        val repo = PostgresPlayerSettingsRepository(db)
        val id = PlayerId(UUID.randomUUID())
        repo.upsert(PlayerSettings(id, controlScheme = ControlScheme.RightClick))

        assertThat(repo.find(id)!!.controlScheme).isEqualTo(ControlScheme.RightClick)
    }

    @Test
    public fun `upsert overwrites the prior settings row`() {
        val repo = PostgresPlayerSettingsRepository(db)
        val id = PlayerId(UUID.randomUUID())
        repo.upsert(PlayerSettings(id, doubleJumpEnabled = true))
        repo.upsert(PlayerSettings(id, doubleJumpEnabled = false))

        assertThat(repo.find(id)!!.doubleJumpEnabled).isFalse
    }

    // ── Wave G 5player-prefs-social (ADR-0124) — locale + per-channel volume ──

    @Test
    public fun `locale and per-channel volume round-trip`() {
        val repo = PostgresPlayerSettingsRepository(db)
        val id = PlayerId(UUID.randomUUID())
        val settings = PlayerSettings(
            playerId = id,
            locale = LocaleCode.JA,
            musicVolume = 25,
            soundVolume = 50,
            voiceVolume = 0,
        )

        repo.upsert(settings)
        val found = repo.find(id)

        assertThat(found).isNotNull
        assertThat(found!!.locale).isEqualTo(LocaleCode.JA)
        assertThat(found.musicVolume).isEqualTo(25)
        assertThat(found.soundVolume).isEqualTo(50)
        assertThat(found.voiceVolume).isEqualTo(0)
    }

    @Test
    public fun `default locale is null (server default) and default volumes are 100`() {
        val repo = PostgresPlayerSettingsRepository(db)
        val id = PlayerId(UUID.randomUUID())
        repo.upsert(PlayerSettings(id))

        val found = repo.find(id)!!
        assertThat(found.locale).isNull()
        assertThat(found.musicVolume).isEqualTo(100)
        assertThat(found.soundVolume).isEqualTo(100)
        assertThat(found.voiceVolume).isEqualTo(100)
    }
}
