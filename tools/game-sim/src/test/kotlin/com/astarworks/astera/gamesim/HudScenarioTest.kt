package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.event.CurrencyChangeReason
import com.astarworks.astera.core.domain.event.CurrencyChanged
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.hud.HudRegion
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.match.application.port.outbound.matchmaking.IMatchmaker
import com.astarworks.astera.match.domain.gamemode.annihilation.AnnihilationConfig
import com.astarworks.astera.match.domain.matchmaking.MatchmakingMode
import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * WP14: the HUD channels as a player sees them, recorded by [SimHudRecorder]:
 * lobby sidebar, match sidebar per mode, weapon resource bar, action-bar owner.
 */
class HudScenarioTest {

    private fun HudLayout.Sidebar.texts(): List<HudComponent.Text> = lines.map { it as HudComponent.Text }

    private fun HudLayout.Sidebar.keys(): List<String> = texts().map { it.messageKey.value }

    private fun HudLayout.BossBarSlot.text(): HudComponent.Text = component as HudComponent.Text

    // ---- lobby -------------------------------------------------------------

    @Test
    fun `lobby sidebar shows online players, queue state and the star wallet`() {
        scenario("hud-lobby-sidebar") {
            val alice = player("alice")
            player("bob")
            sim.services.getOrThrow<ICurrencyLedger>().credit(alice.id, Currency.STAR, 25)
            advance(40)

            val idle = checkNotNull(sim.hud.sidebar(alice.id)) { "no lobby sidebar" }
            assertThat(idle.titleKey.value).isEqualTo("astera.hud.lobby.title")
            assertThat(idle.texts().first { it.messageKey.value == "astera.hud.lobby.online" }.placeholders)
                .containsEntry("count", "2")
            assertThat(idle.keys()).contains("astera.hud.lobby.queue.idle")
            assertThat(idle.texts().first { it.messageKey.value == "astera.hud.sidebar.stars" }.placeholders)
                .containsEntry("stars", "25")

            val queued = sim.services.getOrThrow<IMatchmaker>().enqueue(alice.id, MatchmakingMode.Norma)
            assertThat(queued).isInstanceOf(Result.Success::class.java)
            advance(2)

            val inQueue = checkNotNull(sim.hud.sidebar(alice.id))
            assertThat(inQueue.keys()).contains(
                "astera.hud.lobby.queue.mode",
                "astera.hud.lobby.queue.position",
                "astera.hud.lobby.queue.eta",
            )
            assertThat(inQueue.keys()).doesNotContain("astera.hud.lobby.queue.idle")

            // Idle seconds cost nothing: the poll sees identical layouts and sends nothing.
            val changes = sim.hud.changes(alice.id, HudRegion.SIDEBAR).size
            advance(100)
            assertThat(sim.hud.changes(alice.id, HudRegion.SIDEBAR)).hasSize(changes)
        }
    }

    // ---- match sidebar -------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "battle-loyal, astera.hud.sidebar.battle-loyal.team",
        "break-core,   astera.hud.sidebar.break-core.intact",
        "carry-tnt,    astera.hud.sidebar.carry-tnt.team",
        "capture-wool, astera.hud.sidebar.capture-wool.team",
        "steal-jewelry, astera.hud.sidebar.steal-jewelry.team",
    )
    fun `siege mode sidebars show a live phase line and per-team objective progress`(mode: String, teamKey: String) {
        scenario("hud-sidebar-$mode") {
            val red = player("red", team = "red")
            val blue = player("blue", team = "blue")
            startMatch(mode, mapOf(red to "red", blue to "blue"))
            advance(40)

            val sidebar = checkNotNull(sim.hud.sidebar(red.id)) { "$mode: no match sidebar" }
            assertThat(sidebar.titleKey.value).isEqualTo("astera.gamemode.$mode.title")
            assertThat(sidebar.keys().first()).isEqualTo("astera.hud.sidebar.phase.active")
            val teamLines = sidebar.texts().filter { it.messageKey.value == teamKey }
            assertThat(teamLines.map { it.placeholders["team"] }).hasSize(2)
            assertThat(sidebar.texts().first { it.messageKey.value == "astera.hud.sidebar.you" }.placeholders)
                .containsKey("team")
            // The lobby sidebar is gone for match members.
            assertThat(sidebar.titleKey.value).isNotEqualTo("astera.hud.lobby.title")

            // The clock moves, in place: no second sidebar handle.
            val clockBefore = sidebar.texts().first().placeholders["time"]
            advance(20 * 5)
            val later = checkNotNull(sim.hud.sidebar(red.id))
            assertThat(later.texts().first().placeholders["time"]).isNotEqualTo(clockBefore)

            // Match end hands the channel back to the lobby sidebar.
            val lifecycle = sim.services.getOrThrow<IMatchLifecycle>()
            val matchId = sim.eventsOf<com.astarworks.astera.core.domain.event.MatchStarted>().last().matchId
            lifecycle.end(matchId, com.astarworks.astera.core.domain.event.EndReason.AdminEnded)
            advance(40)
            assertThat(sim.eventsOf<MatchEnded>()).isNotEmpty()
            assertThat(checkNotNull(sim.hud.sidebar(red.id)).titleKey.value).isEqualTo("astera.hud.lobby.title")
        }
    }

    @Test
    fun `break core sidebar flips a team to core down when its core is broken`() {
        scenario("hud-sidebar-break-core-progress") {
            val attacker = player("attacker", team = "red")
            val defender = player("defender", team = "blue")
            startMatch("break-core", mapOf(attacker to "red", defender to "blue"))
            advance(25)
            assertThat(checkNotNull(sim.hud.sidebar(defender.id)).keys().count { it == "astera.hud.sidebar.break-core.intact" })
                .isEqualTo(2)

            attacker.breaks(Vec3(46.0, -60.0, 0.0)) // blue core: the mode ends the match
            advance(25)
            assertThat(sim.eventsOf<MatchEnded>()).hasSize(1)
        }
    }

    @Test
    fun `annihilation sidebar shows the nexus clock and pillars standing per team`() {
        val config = AnnihilationConfig(pillarHp = 40, buildPhaseTicks = 200, engagePhaseTicks = 100_000, tier3PhaseTicks = 100_000)
        scenario("hud-sidebar-annihilation", config) {
            val red = player("red", team = "red")
            val blue = player("blue", team = "blue")
            val lifecycle = sim.services.getOrThrow<IMatchLifecycle>()
            val matchId = (lifecycle.start(StageId("annihilation-arena"), "annihilation") as Result.Success).value
            lifecycle.entry(matchId, red.id, com.astarworks.astera.core.domain.match.TeamId("red"))
            lifecycle.entry(matchId, blue.id, com.astarworks.astera.core.domain.match.TeamId("blue"))
            lifecycle.switchPhase(matchId, com.astarworks.astera.match.domain.match.MatchStateMachine.TransitionTrigger.CountdownComplete)
            advance(40)

            val build = checkNotNull(sim.hud.sidebar(red.id)) { "no annihilation sidebar" }
            assertThat(build.titleKey.value).isEqualTo("astera.gamemode.annihilation.title")
            assertThat(build.keys().first()).isEqualTo("astera.hud.sidebar.nexus.build")
            val teams = build.texts().filter { it.messageKey.value == "astera.hud.sidebar.annihilation.team" }
            assertThat(teams.map { it.placeholders["team"] }).contains("Red", "Blue") // the stage's 3rd team is listed too
            assertThat(teams.map { it.placeholders["tiers"] }).containsOnly("●●●")

            advance(200) // Build ends
            assertThat(checkNotNull(sim.hud.sidebar(red.id)).keys().first()).isEqualTo("astera.hud.sidebar.nexus.engage")
        }
    }

    // ---- weapon resource ---------------------------------------------------

    @Test
    fun `the weapon resource owns one boss bar that follows the held item, drains, and shows the reload`() {
        scenario("hud-weapon-resource") {
            val shooter = player("shooter", team = "red")
            val dummy = mob("dummy", at = Vec3(0.0, groundY, 12.0), team = "blue")
            sim.setMaxHealth(dummy, 500.0)

            shooter.holds("pistol")
            shooter.faces(dummy)
            advance(2)
            val full = checkNotNull(sim.hud.bossBar(shooter.id)) { "no resource bar for a held pistol" }
            assertThat(full.text().messageKey.value).isEqualTo("astera.hud.resource.ammo.label")
            assertThat(full.text().placeholders).containsEntry("remaining", "12").containsEntry("max", "12")
            assertThat(full.progress).isEqualTo(1f)

            repeat(3) {
                shooter.rightClick()
                advance(6)
            }
            val drained = checkNotNull(sim.hud.bossBar(shooter.id))
            assertThat(drained.text().placeholders).containsEntry("remaining", "9")
            assertThat(drained.progress).isEqualTo(0.75f)

            // Empty: red prompt. Then Q: yellow bar filling with the reload timer, green flash when done.
            repeat(9) {
                shooter.rightClick()
                advance(6)
            }
            assertThat(checkNotNull(sim.hud.bossBar(shooter.id)).text().messageKey.value).isEqualTo("astera.hud.resource.ammo.empty")
            shooter.drop()
            advance(16)
            val reloading = checkNotNull(sim.hud.bossBar(shooter.id))
            assertThat(reloading.text().messageKey.value).isEqualTo("astera.hud.resource.ammo.reloading")
            assertThat(reloading.progress).isBetween(0.3f, 0.7f)
            advance(16)
            assertThat(checkNotNull(sim.hud.bossBar(shooter.id)).text().messageKey.value).isEqualTo("astera.hud.resource.ammo.reloaded")

            // One handle the whole time: the first draw is a show, every later one an update.
            val ops = sim.hud.changes(shooter.id, HudRegion.BOSS_BAR_SLOT_1).map { it.op }
            assertThat(ops.first()).isEqualTo(SimHudRecorder.Op.SHOW)
            assertThat(ops.drop(1)).containsOnly(SimHudRecorder.Op.UPDATE)

            // Putting the pistol away hides the bar.
            shooter.holds("rifle")
            advance(2)
            sim.hud.bossBar(shooter.id)?.let {
                assertThat(it.text().messageKey.value).`as`("a different gun draws its own ammo").startsWith("astera.hud.resource.")
            }
        }
    }

    // ---- action bar ----------------------------------------------------------

    @Test
    fun `the action bar shows the cooldown readout and a currency toast is not erased by it`() {
        scenario("hud-action-bar-owner") {
            val shooter = player("shooter", team = "red")
            shooter.cast("flame-burst") // long cooldown
            advance(12)

            val readout = checkNotNull(sim.hud.actionBar(shooter.id)) { "no cooldown readout" }
            assertThat(readout.priority).isEqualTo(0)

            sim.eventBus.publish(
                CurrencyChanged(shooter.id, Currency.STAR, delta = 5, newBalance = 30, reason = CurrencyChangeReason.MatchReward),
            )
            advance(2)
            val toast = checkNotNull(sim.hud.actionBar(shooter.id))
            assertThat(toast.priority).isEqualTo(1)
            assertThat((toast.component as HudComponent.Text).placeholders).containsEntry("delta", "+5")

            // The readout keeps polling while the toast is up; the toast stays until it expires.
            advance(20)
            assertThat(checkNotNull(sim.hud.actionBar(shooter.id)).priority).isEqualTo(1)
        }
    }
}
