package com.astarworks.astera.match.application.service.hud

import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.i18n.LocalizedLore
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.Score
import com.astarworks.astera.core.domain.match.Team
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.nexus.NexusPillarTier
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.service.InMemoryMatchRepository
import com.astarworks.astera.match.application.service.matchmaking.InMemoryMatchmaker
import com.astarworks.astera.match.domain.gamemode.GameModeContext
import com.astarworks.astera.match.domain.gamemode.HudLine
import com.astarworks.astera.match.domain.gamemode.IGameMode
import com.astarworks.astera.match.domain.gamemode.IGameModePhase
import com.astarworks.astera.match.domain.gamemode.MatchHudStatus
import com.astarworks.astera.match.domain.gamemode.annihilation.AnnihilationConfig
import com.astarworks.astera.match.domain.gamemode.annihilation.AnnihilationHudStatus
import com.astarworks.astera.match.domain.gamemode.annihilation.AnnihilationMatchState
import com.astarworks.astera.match.domain.matchmaking.MatchmakingMode
import com.astarworks.astera.match.domain.mode.GameModeId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeHudGateway
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** WP14 / ADR-0177: sidebar model per mode shape, per-viewer layout, and the two sidebar services. */
class MatchSidebarTest {

    private val p1 = PlayerId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
    private val p2 = PlayerId(UUID.fromString("22222222-2222-2222-2222-222222222222"))
    private val red = TeamId("red")
    private val blue = TeamId("blue")

    private fun team(id: TeamId, members: Set<PlayerId>) = Team(
        id = id,
        displayNameKey = MessageKey("astera.match.team.${id.value}"),
        colorKey = MessageKey("astera.match.team.${id.value}.color"),
        members = members,
    )

    private fun match(
        phase: MatchPhase = MatchPhase.ACTIVE,
        redScore: Int = 3,
        blueScore: Int = 1,
        startedAt: Long = 0,
    ) = Match(
        id = MatchId("m1"),
        phase = phase,
        teams = listOf(team(red, setOf(p1)), team(blue, setOf(p2))),
        score = Score(mapOf(red to redScore, blue to blueScore)),
        stageId = StageId("arena"),
        startedAtTick = startedAt,
    )

    /** Resolves team names / colours like the language files do; everything else echoes the key. */
    private val renderer = object : IMessageRenderer {
        override fun render(playerId: PlayerId?, key: MessageKey, placeholders: Map<String, String>): String = when (key.value) {
            "astera.match.team.red" -> "Red"
            "astera.match.team.red.color" -> "red"
            "astera.match.team.blue" -> "Blue"
            else -> key.value
        }

        override fun renderLore(playerId: PlayerId?, key: MessageKey, placeholders: Map<String, String>) = listOf(key.value)
        override fun renderText(playerId: PlayerId?, text: LocalizedText, placeholders: Map<String, String>) = ""
        override fun renderLocalizedLore(playerId: PlayerId?, lore: LocalizedLore, placeholders: Map<String, String>) = emptyList<String>()
    }

    /** Mode double: reports whatever [status] holds; lifecycle hooks are irrelevant here. */
    private class StubMode(var status: MatchHudStatus? = null) : IGameMode {
        override fun id() = GameModeId("stub")
        override fun titleKey() = MessageKey("astera.gamemode.stub.title")
        override fun defaultPhases(): List<IGameModePhase> = listOf(IGameModePhase.Active)
        override fun onMatchStarted(context: GameModeContext) = Unit
        override fun onPhaseChanged(context: GameModeContext, oldPhase: IGameModePhase, newPhase: IGameModePhase) = Unit
        override fun onTick(context: GameModeContext, tick: Long) = Unit
        override fun onMatchEnded(context: GameModeContext) = Unit
        override fun hudStatus(match: Match, nowTick: Long): MatchHudStatus = status ?: super.hudStatus(match, nowTick)
    }

    private fun HudLayout.Sidebar.keys(): List<String> = lines.map { (it as HudComponent.Text).messageKey.value }

    private fun HudLayout.Sidebar.text(key: String): HudComponent.Text =
        lines.map { it as HudComponent.Text }.first { it.messageKey.value == key }

    // ---- model ------------------------------------------------------------

    @Test
    fun `score modes show phase with elapsed time then one score line per team`() {
        val model = MatchSidebarModel.build(StubMode(), match(), nowTick = 1300)

        assertThat(model.titleKey.value).isEqualTo("astera.gamemode.stub.title")
        val phase = model.lines[0]
        assertThat(phase.key.value).isEqualTo("astera.hud.sidebar.phase.active")
        assertThat(phase.placeholders).containsEntry("time", "1:05")
        assertThat(model.lines[1].key.value).isEqualTo("astera.hud.sidebar.spacer")
        val scores = model.lines.drop(2)
        assertThat(scores.map { it.team }).containsExactly(red, blue)
        assertThat(scores.map { it.placeholders["score"] }).containsExactly("3", "1")
    }

    @Test
    fun `the clock only runs while the match is live`() {
        val model = MatchSidebarModel.build(StubMode(), match(phase = MatchPhase.COUNTDOWN), nowTick = 50)

        assertThat(model.lines[0].key.value).isEqualTo("astera.hud.sidebar.phase.countdown")
        assertThat(model.lines[0].placeholders).isEmpty()
    }

    @Test
    fun `a mode can replace the phase line and contribute objective lines`() {
        val mode = StubMode(
            MatchHudStatus(
                phaseLine = HudLine(MessageKey("astera.hud.sidebar.nexus.engage"), mapOf("time" to "4:12")),
                lines = listOf(HudLine(MessageKey("astera.hud.sidebar.carry-tnt.team"), mapOf("score" to "2", "goal" to "3"), red)),
            ),
        )

        val model = MatchSidebarModel.build(mode, match(), nowTick = 0)

        assertThat(model.lines.map { it.key.value }).containsExactly(
            "astera.hud.sidebar.nexus.engage",
            "astera.hud.sidebar.spacer",
            "astera.hud.sidebar.carry-tnt.team",
        )
    }

    @Test
    fun `annihilation reports the nexus phase clock and which pillars still stand per team`() {
        val matchId = MatchId("m1")
        val config = AnnihilationConfig()
        var state = AnnihilationMatchState.seed(matchId, config, listOf(red, blue)).openTierForCombat(NexusPillarTier.TIER_1)
        val redTier1 = state.pillarsOf(red).first { it.tier == NexusPillarTier.TIER_1 }
        state = state.applyDamage(redTier1.id, config.pillarHp).first

        val status = AnnihilationHudStatus.of(state, match(), nowTick = 1200)

        assertThat(status.phaseLine!!.key.value).isEqualTo("astera.hud.sidebar.nexus.build")
        assertThat(status.phaseLine!!.placeholders).containsEntry("time", "4:00") // 6000 - 1200 ticks
        val (redLine, blueLine) = status.lines
        assertThat(redLine.team).isEqualTo(red)
        assertThat(redLine.placeholders).containsEntry("tiers", "○●●").containsEntry("alive", "2")
            .containsEntry("hp", (config.pillarHp * 2).toString())
        assertThat(blueLine.placeholders).containsEntry("tiers", "●●●").containsEntry("alive", "3")
            .containsEntry("hp", (config.pillarHp * 3).toString())
    }

    @Test
    fun `an eliminated team is shown as out`() {
        val config = AnnihilationConfig()
        var state = AnnihilationMatchState.seed(MatchId("m1"), config, listOf(red, blue)).openTierForCombat(NexusPillarTier.TIER_3)
        state = state.applyDamage(state.pillarsOf(red).first { it.tier == NexusPillarTier.TIER_3 }.id, config.pillarHp).first

        val status = AnnihilationHudStatus.of(state, match(), nowTick = 0)

        assertThat(status.lines[0].key.value).isEqualTo("astera.hud.sidebar.annihilation.eliminated")
        assertThat(status.lines[1].key.value).isEqualTo("astera.hud.sidebar.annihilation.team")
    }

    @Test
    fun `clock rounds up and never goes negative`() {
        assertThat(AnnihilationHudStatus.clock(1)).isEqualTo("0:01")
        assertThat(AnnihilationHudStatus.clock(1200)).isEqualTo("1:00")
        assertThat(AnnihilationHudStatus.clock(-5)).isEqualTo("0:00")
    }

    // ---- per-viewer layout ---------------------------------------------------

    @Test
    fun `layout localises team names and adds the viewer's own team and star balance`() {
        val model = MatchSidebarModel.build(StubMode(), match(), nowTick = 0)

        val layout = MatchSidebarModel.layoutFor(model, match(), p1, renderer, stars = 40)

        val redScore = layout.lines.map { it as HudComponent.Text }
            .first { it.messageKey.value == "astera.hud.sidebar.score" && it.placeholders["team"] == "Red" }
        assertThat(redScore.placeholders).containsEntry("color", "red").containsEntry("score", "3")
        val blueScore = layout.lines.map { it as HudComponent.Text }
            .first { it.messageKey.value == "astera.hud.sidebar.score" && it.placeholders["team"] == "Blue" }
        assertThat(blueScore.placeholders).containsEntry("color", "white") // unknown colour key falls back
        assertThat(layout.text("astera.hud.sidebar.you").placeholders).containsEntry("team", "Red")
        assertThat(layout.text("astera.hud.sidebar.stars").placeholders).containsEntry("stars", "40")
        assertThat(layout.keys().last()).isEqualTo("astera.hud.sidebar.stars")
    }

    @Test
    fun `no wallet means no star line and the sidebar never exceeds fifteen lines`() {
        val many = StubMode(MatchHudStatus(lines = (1..40).map { HudLine(MessageKey("astera.hud.sidebar.score"), team = red) }))
        val model = MatchSidebarModel.build(many, match(), nowTick = 0)

        val layout = MatchSidebarModel.layoutFor(model, match(), p1, renderer, stars = null)

        assertThat(layout.lines.size).isLessThanOrEqualTo(15)
        assertThat(layout.keys()).doesNotContain("astera.hud.sidebar.stars")
    }

    // ---- match service ---------------------------------------------------------

    private class MatchFixture(val mode: StubMode = StubMode()) {
        val scheduler = FakeScheduler()
        val hud = FakeHudGateway(scheduler = scheduler)
        val matches = InMemoryMatchRepository()
    }

    private fun MatchFixture.service(renderer: IMessageRenderer, stars: Long? = 7) = MatchSidebarService(
        matches = matches,
        modeOf = { mode },
        hud = hud,
        messages = renderer,
        stars = { stars },
        scheduler = scheduler,
    )

    @Test
    fun `refresh pushes a sidebar to every member and unchanged content is not redrawn`() {
        val f = MatchFixture()
        f.matches.save(match())
        val service = f.service(renderer)

        service.refresh(MatchId("m1"))
        service.refresh(MatchId("m1"))

        assertThat(f.hud.operations.filterIsInstance<FakeHudGateway.HudOp.Shown>()).hasSize(2) // p1 + p2, once
        assertThat(f.hud.operations).hasSize(2)
    }

    @Test
    fun `a score change redraws in place and the poll advances the clock`() {
        val f = MatchFixture()
        f.matches.save(match())
        val service = f.service(renderer)
        service.refresh(MatchId("m1"))

        f.matches.save(match(redScore = 4))
        service.refresh(MatchId("m1"))
        assertThat(f.hud.operations.filterIsInstance<FakeHudGateway.HudOp.Updated>()).hasSize(2)
        assertThat(f.hud.activeFor(p1)).hasSize(1) // replaced, not stacked

        f.scheduler.advanceTicks(20) // poll: elapsed time moved on
        assertThat(f.hud.operations.filterIsInstance<FakeHudGateway.HudOp.Updated>()).hasSize(4)
    }

    @Test
    fun `release forgets what was sent so the next match redraws, and an ended match is never drawn`() {
        val f = MatchFixture()
        f.matches.save(match())
        val service = f.service(renderer)
        service.refresh(MatchId("m1"))
        service.refresh(MatchId("m1"))
        assertThat(f.hud.operations).hasSize(2) // deduplicated

        service.release(MatchId("m1"))
        service.refresh(MatchId("m1")) // identical content, but the cache was dropped: drawn again
        assertThat(f.hud.operations).hasSize(4)
        assertThat(f.hud.operations.filterIsInstance<FakeHudGateway.HudOp.Cleared>()).isEmpty()

        f.matches.save(match(phase = MatchPhase.ENDED))
        val before = f.hud.operations.size
        service.refresh(MatchId("m1"))
        assertThat(f.hud.operations.size).isEqualTo(before)
    }

    @Test
    fun `a wallet change redraws just that player`() {
        val f = MatchFixture()
        f.matches.save(match())
        var balance = 7L
        val service = MatchSidebarService(f.matches, { f.mode }, f.hud, renderer, { balance }, f.scheduler)
        service.refresh(MatchId("m1"))
        val before = f.hud.operations.size

        balance = 12
        service.refreshPlayer(p1)

        assertThat(f.hud.operations.size).isEqualTo(before + 1)
        val updated = (f.hud.operations.last() as FakeHudGateway.HudOp.Updated).layout as HudLayout.Sidebar
        assertThat(updated.text("astera.hud.sidebar.stars").placeholders).containsEntry("stars", "12")
    }

    // ---- lobby service ---------------------------------------------------------

    private class LobbyFixture {
        val scheduler = FakeScheduler()
        val hud = FakeHudGateway(scheduler = scheduler)
        val matches = InMemoryMatchRepository()
        val matchmaker = InMemoryMatchmaker(eventBus = FakeDomainEventBus())
    }

    private fun LobbyFixture.service() = LobbySidebarService(
        matches = matches,
        matchmaker = matchmaker,
        hud = hud,
        messages = renderer,
        stars = { 15L },
        scheduler = scheduler,
        gold = { 0L },
    )

    private fun LobbyFixture.sidebarOf(player: PlayerId): HudLayout.Sidebar =
        hud.currentLayout(hud.activeFor(player).single()) as HudLayout.Sidebar

    @Test
    fun `the lobby sidebar shows a GOLD line when the player holds gold`() {
        val f = LobbyFixture()
        val service = LobbySidebarService(
            matches = f.matches,
            matchmaker = f.matchmaker,
            hud = f.hud,
            messages = renderer,
            stars = { 15L },
            scheduler = f.scheduler,
            gold = { 9L },
        )
        service.onJoined(p1)
        service.refreshAll()

        val layout = f.sidebarOf(p1)
        assertThat(layout.text("astera.hud.sidebar.gold").placeholders).containsEntry("gold", "9")
        assertThat(layout.keys().indexOf("astera.hud.sidebar.gold")).isGreaterThan(layout.keys().indexOf("astera.hud.sidebar.stars"))
    }

    @Test
    fun `the lobby sidebar shows players online, queue state and stars`() {
        val f = LobbyFixture()
        val service = f.service()
        service.onJoined(p1)
        service.onJoined(p2)

        service.refreshAll()
        val idle = f.sidebarOf(p1)
        assertThat(idle.titleKey.value).isEqualTo("astera.hud.lobby.title")
        assertThat(idle.text("astera.hud.lobby.online").placeholders).containsEntry("count", "2")
        assertThat(idle.keys()).contains("astera.hud.lobby.queue.idle")
        assertThat(idle.text("astera.hud.sidebar.stars").placeholders).containsEntry("stars", "15")
        assertThat(idle.keys()).`as`("no GOLD line while the player has none").doesNotContain("astera.hud.sidebar.gold")

        f.matchmaker.enqueue(p1, MatchmakingMode.Norma)
        service.refresh(p1)
        val queued = f.sidebarOf(p1)
        assertThat(queued.keys()).contains("astera.hud.lobby.queue.mode", "astera.hud.lobby.queue.position", "astera.hud.lobby.queue.eta")
        assertThat(queued.keys()).doesNotContain("astera.hud.lobby.queue.idle")
        assertThat(f.hud.activeFor(p1)).hasSize(1)
    }

    @Test
    fun `players in a live match are left to the match sidebar and get the lobby back afterwards`() {
        val f = LobbyFixture()
        f.matches.save(match())
        val service = f.service()
        service.onJoined(p1)

        service.refreshAll()
        assertThat(f.hud.operations).isEmpty()

        f.matches.save(match(phase = MatchPhase.ENDED))
        service.refreshAll()
        assertThat(f.hud.activeFor(p1)).hasSize(1)
        assertThat(f.sidebarOf(p1).titleKey.value).isEqualTo("astera.hud.lobby.title")
    }

    @Test
    fun `an unchanged lobby is not redrawn by the poll`() {
        val f = LobbyFixture()
        val service = f.service()
        service.onJoined(p1)
        val before = f.hud.operations.size

        f.scheduler.advanceTicks(60)

        assertThat(f.hud.operations.size).isEqualTo(before)
        service.stop()
    }
}
