package com.astarworks.astera.match.application.service.hud

import com.astarworks.astera.core.adapter.api.hud.ITabList
import com.astarworks.astera.core.adapter.api.hud.TabListEntry
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.service.InMemoryPlayerLabelGateway
import com.astarworks.astera.core.domain.hud.PlayerLabel
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.Score
import com.astarworks.astera.core.domain.match.Team
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.service.InMemoryMatchRepository
import com.astarworks.astera.testfixtures.FakeMessageRenderer
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** ADR-0200: team-coloured nameplates with HP, and the tab header / footer. */
class NameplateAndTabTest {

    private val a = PlayerId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
    private val b = PlayerId(UUID.fromString("22222222-2222-2222-2222-222222222222"))
    private val lobbyGuy = PlayerId(UUID.fromString("33333333-3333-3333-3333-333333333333"))

    private class ColorRenderer(private val delegate: FakeMessageRenderer = FakeMessageRenderer()) :
        IMessageRenderer by delegate {
        override fun render(playerId: PlayerId?, key: MessageKey, placeholders: Map<String, String>): String =
            when (key.value) {
                "color.red" -> "red"
                "color.blue" -> "blue"
                else -> delegate.render(playerId, key, placeholders)
            }
    }

    /** Keeps key + placeholders (the shared fake drops the placeholders). */
    private class RecordingTab : ITabList {
        val headers = mutableMapOf<PlayerId, String>()
        val footers = mutableMapOf<PlayerId, String>()
        override fun setHeader(playerId: PlayerId, header: MessageKey, placeholders: Map<String, String>) {
            headers[playerId] = header.value + placeholders.toSortedMap()
        }
        override fun setFooter(playerId: PlayerId, footer: MessageKey, placeholders: Map<String, String>) {
            footers[playerId] = footer.value + placeholders.toSortedMap()
        }
        override fun clearHeader(playerId: PlayerId) { headers.remove(playerId) }
        override fun clearFooter(playerId: PlayerId) { footers.remove(playerId) }
        override fun setPlayerEntries(playerId: PlayerId, entries: List<TabListEntry>) = Unit
        override fun currentHeader(playerId: PlayerId): String? = headers[playerId]
        override fun currentFooter(playerId: PlayerId): String? = footers[playerId]
        override fun currentEntries(playerId: PlayerId): List<TabListEntry> = emptyList()
    }

    private fun team(id: String, color: String, member: PlayerId) =
        Team(TeamId(id), MessageKey("name.$id"), MessageKey("color.$color"), setOf(member))

    private fun match(phase: MatchPhase = MatchPhase.ACTIVE) = Match(
        id = MatchId("m1"),
        phase = phase,
        teams = listOf(team("red", "red", a), team("blue", "blue", b)),
        score = Score.EMPTY,
        stageId = StageId("s"),
        startedAtTick = 0L,
    )

    @Test
    fun `every participant sees every participant in team colour with whole HP`() {
        val matches = InMemoryMatchRepository().apply { save(match()) }
        val labels = InMemoryPlayerLabelGateway()
        val hp = mutableMapOf(a to 150.4, b to 0.2)
        val service = MatchNameplateService(matches, labels, ColorRenderer(), { hp[it] }, FakeScheduler())

        service.refresh(MatchId("m1"))

        for (viewer in listOf(a, b)) {
            assertThat(labels.labelsOf(viewer)).containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    a to PlayerLabel("red", "red", hp = 151),
                    b to PlayerLabel("blue", "blue", hp = 1),
                ),
            )
        }
        assertThat(labels.labelsOf(lobbyGuy)).isEmpty()
    }

    @Test
    fun `an HP change restyles, match end and leaving clear`() {
        val matches = InMemoryMatchRepository().apply { save(match()) }
        val labels = InMemoryPlayerLabelGateway()
        val hp = mutableMapOf(a to 100.0, b to 100.0)
        val service = MatchNameplateService(matches, labels, ColorRenderer(), { hp[it] }, FakeScheduler())
        service.refresh(MatchId("m1"))

        hp[b] = 40.0
        service.refreshMatchOf(b)
        assertThat(labels.labelsOf(a).getValue(b).hp).isEqualTo(40)

        service.release(MatchId("m1"))
        assertThat(labels.labelsOf(a)).isEmpty()
        assertThat(labels.labelsOf(b)).isEmpty()
    }

    @Test
    fun `the poll keeps labels applied without any event`() {
        val matches = InMemoryMatchRepository().apply { save(match()) }
        val labels = InMemoryPlayerLabelGateway()
        val scheduler = FakeScheduler()
        MatchNameplateService(matches, labels, ColorRenderer(), { 20.0 }, scheduler)

        scheduler.advanceTicks(20)

        assertThat(labels.labelsOf(a)).hasSize(2)
    }

    @Test
    fun `tab header and footer differ between lobby and match and update once`() {
        val matches = InMemoryMatchRepository().apply { save(match()) }
        val tab = RecordingTab()
        val service = TabHeaderFooterService(matches, tab, ColorRenderer(), { null }, FakeScheduler())

        service.onJoined(a)
        service.onJoined(lobbyGuy)

        assertThat(tab.currentHeader(lobbyGuy)).startsWith("astera.hud.tab.lobby.header")
        assertThat(tab.currentFooter(lobbyGuy)).contains("count=2")
        assertThat(tab.currentHeader(a)).startsWith("astera.hud.tab.match.header")
        assertThat(tab.currentFooter(a)).contains("color=red", "team=name.red", "count=2")
    }

    @Test
    fun `a quitting player stops being tracked`() {
        val tab = RecordingTab()
        val service = TabHeaderFooterService(InMemoryMatchRepository(), tab, ColorRenderer(), { null }, FakeScheduler())
        service.onJoined(a)
        service.onJoined(b)

        service.onQuit(a)

        assertThat(tab.currentFooter(b)).contains("count=1")
    }
}
