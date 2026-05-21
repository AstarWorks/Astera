package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.domain.player.StarterGrant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

class StarterGrantLoaderTest {

    @Test
    fun `load returns EMPTY when the file is missing`(@TempDir tmp: Path) {
        val result = StarterGrantLoader().load(tmp.resolve("does-not-exist.yaml"))

        assertThat(result).isEqualTo(StarterGrant.EMPTY)
    }

    @Test
    fun `load returns EMPTY when YAML is malformed`(@TempDir tmp: Path) {
        val path = tmp.resolve("starter-grant.yaml")
        path.writeText("default_loadout: [unclosed")

        val result = StarterGrantLoader().load(path)

        assertThat(result).isEqualTo(StarterGrant.EMPTY)
    }

    @Test
    fun `load returns EMPTY when validation fails`(@TempDir tmp: Path) {
        val path = tmp.resolve("starter-grant.yaml")
        path.writeText(
            """
            default_loadout:
              - UpperCaseBad
            """.trimIndent(),
        )

        val result = StarterGrantLoader().load(path)

        assertThat(result).isEqualTo(StarterGrant.EMPTY)
    }

    @Test
    fun `load parses a happy-path YAML`(@TempDir tmp: Path) {
        val path = tmp.resolve("starter-grant.yaml")
        path.writeText(
            """
            default_loadout:
              - example-sword
            wallet_grants:
              GOLD: 100
            tutorial:
              auto_enroll: true
              script_id: getting-started
            welcome_dialog_id: astera-welcome
            """.trimIndent(),
        )

        val result = StarterGrantLoader().load(path)

        assertThat(result.defaultLoadout).hasSize(1)
        assertThat(result.walletGrants).hasSize(1)
        assertThat(result.tutorialAutoEnroll).isTrue
        assertThat(result.welcomeDialogId).isEqualTo("astera-welcome")
    }
}
