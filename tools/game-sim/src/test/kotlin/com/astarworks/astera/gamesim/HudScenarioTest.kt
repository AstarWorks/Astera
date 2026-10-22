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

            // The daily login bonus lands ~5 s after joining and moves the wallet once; let it settle first.
            advance(100)
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

    // ---- weapon resource (F2-2, ADR-0194) -----------------------------------

    @Test
    fun `ammo is on the status line next to HP, reload is a cooldown sweep, energy is the food bar, and no boss bar ever exists`() {
        scenario("hud-weapon-resource") {
            val shooter = player("shooter", team = "red")
            val dummy = mob("dummy", at = Vec3(0.0, groundY, 12.0), team = "blue")
            sim.setMaxHealth(dummy, 500.0)

            // No gun in hand: the status line carries HP only.
            advance(4)
            assertThat(sim.hud.statusAmmo(shooter.id)).isNull()

            shooter.holds("pistol")
            shooter.faces(dummy)
            advance(4)
            assertThat(sim.hud.statusAmmo(shooter.id)).isEqualTo("12/12")
            assertThat(sim.hud.statusLineText(shooter.id)).contains("astera.hud.status.hp-ammo")

            repeat(3) {
                shooter.rightClick()
                advance(6)
            }
            assertThat(sim.hud.statusAmmo(shooter.id)).isEqualTo("9/12")

            // Empty, then Q: the held item gets the native cooldown sweep for the reload time; ammo refills after it.
            repeat(9) {
                shooter.rightClick()
                advance(6)
            }
            assertThat(sim.hud.statusAmmo(shooter.id)).isEqualTo("0/12")
            assertThat(cooldownSweeps().map { it.ticks }).doesNotContain(30) // shots sweep their own short cooldown (ADR-0197), not the reload
            shooter.drop()
            advance(2)
            assertThat(cooldownSweeps().map { it.ticks }).contains(30)
            advance(32)
            assertThat(sim.hud.statusAmmo(shooter.id)).isEqualTo("12/12")

            // Putting the gun away drops the ammo part; the energy bar (food) keeps showing.
            shooter.holds("knight-sword")
            advance(4)
            assertThat(sim.hud.statusAmmo(shooter.id)).isNull()
            assertThat(foodLevel(shooter)).isEqualTo(20)

            // The status line is one handle the whole time, and no boss-bar region was ever drawn.
            val ops = sim.hud.changes(shooter.id, HudRegion.STATUS_LINE).map { it.op }
            assertThat(ops.first()).isEqualTo(SimHudRecorder.Op.SHOW)
            assertThat(ops.drop(1)).containsOnly(SimHudRecorder.Op.UPDATE)
            assertThat(sim.hud.bossBarLayoutsRecorded).isZero()
            assertThat(HudRegion.entries.map { it.name }).noneMatch { it.startsWith("BOSS_BAR") }
        }
    }

    // ---- action bar ----------------------------------------------------------

    @Test
    fun `a cooldown no longer writes to the action bar and a currency toast stays up`() {
        scenario("hud-action-bar-owner") {
            val shooter = player("shooter", team = "red")
            shooter.cast("flame-burst") // long cooldown
            advance(12)

            // ADR-0197: cooldowns live on the status line; the action bar only carries status effects and toasts.
            assertThat(sim.hud.actionBar(shooter.id)).isNull()

            sim.eventBus.publish(
                CurrencyChanged(shooter.id, Currency.STAR, delta = 5, newBalance = 30, reason = CurrencyChangeReason.MatchReward),
            )
            advance(2)
            val toast = checkNotNull(sim.hud.actionBar(shooter.id))
            assertThat(toast.priority).isEqualTo(1)
            assertThat((toast.component as HudComponent.Text).placeholders).containsEntry("delta", "+5")

            // The effects readout keeps polling while the toast is up; the toast stays until it expires.
            advance(20)
            assertThat(checkNotNull(sim.hud.actionBar(shooter.id)).priority).isEqualTo(1)
        }
    }
}
