package com.astarworks.astera.core.adapter.api.entity

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.FakeMcPlayer
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit tests for the HUD methods added to [IMcPlayer] in Wave 5b:
 *   - [IMcPlayer.sendActionBar]
 *   - [IMcPlayer.sendTitle]
 *
 * The interface itself only declares the methods — actual delivery is the
 * Paper-impl responsibility (deferred to Wave 6 per ADR-0031). These tests
 * exercise the [FakeMcPlayer] recording surface so use cases that emit HUD
 * traffic can be asserted on without a Paper harness.
 */
class IMcPlayerActionBarTitleTest {

    private val playerId = PlayerId(UUID.fromString("00000000-0000-0000-0000-00000000d00b"))

    private fun newPlayer(): FakeMcPlayer = FakeMcPlayer(id = playerId)

    @Test
    fun `sendActionBar records the messageKey and placeholders`() {
        val player = newPlayer()
        val key = MessageKey("hud.actionbar.test_objective")
        val placeholders = mapOf("target" to "Slime", "remaining" to "3")

        player.sendActionBar(key, placeholders)

        assertThat(player.actionBars).hasSize(1)
        val call = player.actionBars.single()
        assertThat(call.key).isEqualTo(key)
        assertThat(call.placeholders).isEqualTo(placeholders)
    }

    @Test
    fun `sendActionBar with empty placeholders defaults to empty map`() {
        val player = newPlayer()
        val key = MessageKey("hud.actionbar.test_objective")

        player.sendActionBar(key)

        assertThat(player.actionBars).hasSize(1)
        val call = player.actionBars.single()
        assertThat(call.key).isEqualTo(key)
        assertThat(call.placeholders).isEmpty()
    }

    @Test
    fun `sendTitle with all defaults records 500_3500_1000 ms durations`() {
        val player = newPlayer()
        val titleKey = MessageKey("hud.title.test_start")

        player.sendTitle(titleKey)

        assertThat(player.titles).hasSize(1)
        val call = player.titles.single()
        assertThat(call.titleKey).isEqualTo(titleKey)
        assertThat(call.subtitleKey).isNull()
        assertThat(call.fadeIn).isEqualTo(500.milliseconds)
        assertThat(call.stay).isEqualTo(3500.milliseconds)
        assertThat(call.fadeOut).isEqualTo(1000.milliseconds)
    }

    @Test
    fun `sendTitle with explicit subtitle and custom durations records all values intact`() {
        val player = newPlayer()
        val titleKey = MessageKey("hud.title.test_start")
        val subtitleKey = MessageKey("hud.title.test_subtitle")

        player.sendTitle(
            titleKey = titleKey,
            subtitleKey = subtitleKey,
            fadeIn = 250.milliseconds,
            stay = 5000.milliseconds,
            fadeOut = 750.milliseconds,
        )

        assertThat(player.titles).hasSize(1)
        val call = player.titles.single()
        assertThat(call.titleKey).isEqualTo(titleKey)
        assertThat(call.subtitleKey).isEqualTo(subtitleKey)
        assertThat(call.fadeIn).isEqualTo(250.milliseconds)
        assertThat(call.stay).isEqualTo(5000.milliseconds)
        assertThat(call.fadeOut).isEqualTo(750.milliseconds)
    }

    @Test
    fun `sendTitle without subtitle records null subtitleKey`() {
        val player = newPlayer()
        val titleKey = MessageKey("hud.title.test_start")

        player.sendTitle(
            titleKey = titleKey,
            subtitleKey = null,
            fadeIn = 100.milliseconds,
            stay = 200.milliseconds,
            fadeOut = 300.milliseconds,
        )

        assertThat(player.titles).hasSize(1)
        val call = player.titles.single()
        assertThat(call.titleKey).isEqualTo(titleKey)
        assertThat(call.subtitleKey).isNull()
    }

    @Test
    fun `multiple sendActionBar calls append to actionBars list in order`() {
        val player = newPlayer()
        val first = MessageKey("hud.actionbar.first")
        val second = MessageKey("hud.actionbar.second")
        val third = MessageKey("hud.actionbar.third")

        player.sendActionBar(first, mapOf("n" to "1"))
        player.sendActionBar(second, mapOf("n" to "2"))
        player.sendActionBar(third, mapOf("n" to "3"))

        assertThat(player.actionBars).hasSize(3)
        assertThat(player.actionBars[0].key).isEqualTo(first)
        assertThat(player.actionBars[0].placeholders).isEqualTo(mapOf("n" to "1"))
        assertThat(player.actionBars[1].key).isEqualTo(second)
        assertThat(player.actionBars[1].placeholders).isEqualTo(mapOf("n" to "2"))
        assertThat(player.actionBars[2].key).isEqualTo(third)
        assertThat(player.actionBars[2].placeholders).isEqualTo(mapOf("n" to "3"))
    }

    @Test
    fun `multiple sendTitle calls append to titles list in order`() {
        val player = newPlayer()
        val first = MessageKey("hud.title.first")
        val second = MessageKey("hud.title.second")

        player.sendTitle(first)
        player.sendTitle(
            titleKey = second,
            subtitleKey = MessageKey("hud.title.second_sub"),
            fadeIn = 111.milliseconds,
            stay = 222.milliseconds,
            fadeOut = 333.milliseconds,
        )

        assertThat(player.titles).hasSize(2)
        assertThat(player.titles[0].titleKey).isEqualTo(first)
        assertThat(player.titles[0].subtitleKey).isNull()
        assertThat(player.titles[0].fadeIn).isEqualTo(500.milliseconds)
        assertThat(player.titles[0].stay).isEqualTo(3500.milliseconds)
        assertThat(player.titles[0].fadeOut).isEqualTo(1000.milliseconds)
        assertThat(player.titles[1].titleKey).isEqualTo(second)
        assertThat(player.titles[1].subtitleKey).isEqualTo(MessageKey("hud.title.second_sub"))
        assertThat(player.titles[1].fadeIn).isEqualTo(111.milliseconds)
        assertThat(player.titles[1].stay).isEqualTo(222.milliseconds)
        assertThat(player.titles[1].fadeOut).isEqualTo(333.milliseconds)
    }
}
