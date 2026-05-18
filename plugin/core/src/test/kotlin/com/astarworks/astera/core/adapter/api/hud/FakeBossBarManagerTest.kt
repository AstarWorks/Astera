package com.astarworks.astera.core.adapter.api.hud

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.FakeBossBarManager
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class FakeBossBarManagerTest {

    private val player1 = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val player2 = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000002"))

    private val phaseId = BossBarId("match.phase")
    private val hpId = BossBarId("combat.hp")
    private val xpId = BossBarId("stats.xp")

    private val phaseTitle = MessageKey("hud.test.bossbar.title")
    private val hpTitle = MessageKey("hud.test.bossbar.hp")
    private val xpTitle = MessageKey("hud.test.bossbar.xp")

    @Test
    fun `createBossBar returns a bar with the configured fields`() {
        val manager = FakeBossBarManager()
        val bar = manager.createBossBar(
            id = phaseId,
            title = phaseTitle,
            progress = 0.5,
            color = BossBarColor.RED,
            overlay = BossBarOverlay.NOTCHED_10,
            flags = setOf(BossBarFlag.DARKEN_SCREEN),
        )

        assertThat(bar.id).isEqualTo(phaseId)
        assertThat(bar.title).isEqualTo(phaseTitle)
        assertThat(bar.progress).isEqualTo(0.5)
        assertThat(bar.color).isEqualTo(BossBarColor.RED)
        assertThat(bar.overlay).isEqualTo(BossBarOverlay.NOTCHED_10)
        assertThat(bar.flags).containsExactly(BossBarFlag.DARKEN_SCREEN)
    }

    @Test
    fun `progress setter rejects values outside 0 to 1`() {
        val manager = FakeBossBarManager()
        val bar = manager.createBossBar(phaseId, phaseTitle)

        assertThatThrownBy { bar.progress = 1.5 }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { bar.progress = -0.1 }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `progress setter accepts boundary values 0 and 1`() {
        val manager = FakeBossBarManager()
        val bar = manager.createBossBar(phaseId, phaseTitle)

        bar.progress = 0.0
        assertThat(bar.progress).isEqualTo(0.0)

        bar.progress = 1.0
        assertThat(bar.progress).isEqualTo(1.0)
    }

    @Test
    fun `show makes the bar visible to the player`() {
        val manager = FakeBossBarManager()
        val bar = manager.createBossBar(phaseId, phaseTitle)

        manager.show(player1, bar)

        assertThat(manager.currentBars(player1)).containsExactly(bar)
        assertThat(manager.currentBars(player2)).isEmpty()
    }

    @Test
    fun `multiple bars can be visible to one player simultaneously`() {
        val manager = FakeBossBarManager()
        val phase = manager.createBossBar(phaseId, phaseTitle)
        val hp = manager.createBossBar(hpId, hpTitle)
        val xp = manager.createBossBar(xpId, xpTitle)

        manager.show(player1, phase)
        manager.show(player1, hp)
        manager.show(player1, xp)

        assertThat(manager.currentBars(player1)).containsExactlyInAnyOrder(phase, hp, xp)
    }

    @Test
    fun `hide removes only the targeted bar and other bars stay visible`() {
        val manager = FakeBossBarManager()
        val phase = manager.createBossBar(phaseId, phaseTitle)
        val hp = manager.createBossBar(hpId, hpTitle)

        manager.show(player1, phase)
        manager.show(player1, hp)
        manager.hide(player1, phase)

        assertThat(manager.currentBars(player1)).containsExactly(hp)
    }

    @Test
    fun `hideAll clears the player visibility set`() {
        val manager = FakeBossBarManager()
        val phase = manager.createBossBar(phaseId, phaseTitle)
        val hp = manager.createBossBar(hpId, hpTitle)

        manager.show(player1, phase)
        manager.show(player1, hp)
        manager.hideAll(player1)

        assertThat(manager.currentBars(player1)).isEmpty()
    }

    @Test
    fun `removeBossBar removes from the registry and from every viewer`() {
        val manager = FakeBossBarManager()
        val phase = manager.createBossBar(phaseId, phaseTitle)
        val hp = manager.createBossBar(hpId, hpTitle)

        manager.show(player1, phase)
        manager.show(player2, phase)
        manager.show(player1, hp)

        manager.removeBossBar(phaseId)

        assertThat(manager.bossBar(phaseId)).isNull()
        assertThat(manager.activeBars).doesNotContainKey(phaseId)
        assertThat(manager.currentBars(player1)).containsExactly(hp)
        assertThat(manager.currentBars(player2)).isEmpty()
    }

    @Test
    fun `progress can be updated after show and the manager reflects it via currentBars`() {
        val manager = FakeBossBarManager()
        val bar = manager.createBossBar(phaseId, phaseTitle, progress = 1.0)
        manager.show(player1, bar)

        bar.progress = 0.25

        val visible = manager.currentBars(player1)
        assertThat(visible).hasSize(1)
        assertThat(visible.single().progress).isEqualTo(0.25)
    }

    @Test
    fun `createBossBar with an existing id returns the same instance with new fields applied`() {
        val manager = FakeBossBarManager()
        val original = manager.createBossBar(
            id = phaseId,
            title = phaseTitle,
            progress = 1.0,
            color = BossBarColor.WHITE,
            overlay = BossBarOverlay.PROGRESS,
            flags = setOf(BossBarFlag.PLAY_BOSS_MUSIC),
        )

        val replayed = manager.createBossBar(
            id = phaseId,
            title = MessageKey("hud.test.bossbar.replayed"),
            progress = 0.4,
            color = BossBarColor.BLUE,
            overlay = BossBarOverlay.NOTCHED_20,
            flags = setOf(BossBarFlag.DARKEN_SCREEN, BossBarFlag.CREATE_WORLD_FOG),
        )

        assertThat(replayed).isSameAs(original)
        assertThat(replayed.title).isEqualTo(MessageKey("hud.test.bossbar.replayed"))
        assertThat(replayed.progress).isEqualTo(0.4)
        assertThat(replayed.color).isEqualTo(BossBarColor.BLUE)
        assertThat(replayed.overlay).isEqualTo(BossBarOverlay.NOTCHED_20)
        assertThat(replayed.flags).containsExactlyInAnyOrder(
            BossBarFlag.DARKEN_SCREEN,
            BossBarFlag.CREATE_WORLD_FOG,
        )
    }

    @Test
    fun `bossBar lookup returns null for an unknown id`() {
        val manager = FakeBossBarManager()
        assertThat(manager.bossBar(BossBarId("nonexistent"))).isNull()
    }

    @Test
    fun `addFlag and removeFlag mutate the flag set`() {
        val manager = FakeBossBarManager()
        val bar = manager.createBossBar(phaseId, phaseTitle)

        bar.addFlag(BossBarFlag.DARKEN_SCREEN)
        bar.addFlag(BossBarFlag.CREATE_WORLD_FOG)
        bar.addFlag(BossBarFlag.DARKEN_SCREEN) // idempotent

        assertThat(bar.flags).containsExactlyInAnyOrder(
            BossBarFlag.DARKEN_SCREEN,
            BossBarFlag.CREATE_WORLD_FOG,
        )

        bar.removeFlag(BossBarFlag.DARKEN_SCREEN)
        bar.removeFlag(BossBarFlag.PLAY_BOSS_MUSIC) // no-op

        assertThat(bar.flags).containsExactly(BossBarFlag.CREATE_WORLD_FOG)
    }
}
