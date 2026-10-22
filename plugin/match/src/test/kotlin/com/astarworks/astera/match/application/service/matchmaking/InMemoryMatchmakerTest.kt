package com.astarworks.astera.match.application.service.matchmaking

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.MatchFormed
import com.astarworks.astera.core.domain.event.MatchQueueJoined
import com.astarworks.astera.core.domain.event.MatchQueueLeft
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.PartyId
import com.astarworks.astera.match.domain.matchmaking.MatchSizeSpec
import com.astarworks.astera.match.domain.matchmaking.MatchmakingMode
import com.astarworks.astera.match.domain.matchmaking.QueueError
import com.astarworks.astera.match.domain.rating.Rating
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class InMemoryMatchmakerTest {

    private val baseClock = Instant.parse("2026-05-20T00:00:00Z")
    private fun makeMatchmaker(
        teamCount: Int = 2,
        minPlayersPerTeam: Int = 1,
    ) = Pair(
        FakeDomainEventBus(),
        run {
            val bus = FakeDomainEventBus()
            InMemoryMatchmaker(
                eventBus = bus,
                clock = { baseClock },
                teamCount = teamCount,
                minPlayersPerTeam = minPlayersPerTeam,
            ) to bus
        }.second,
    ).second

    private fun build(teamCount: Int = 2, minPlayersPerTeam: Int = 1): Triple<FakeDomainEventBus, InMemoryMatchmaker, MutableList<Instant>> {
        val bus = FakeDomainEventBus()
        val timeline = mutableListOf(baseClock)
        val mm = InMemoryMatchmaker(
            eventBus = bus,
            clock = { timeline.last() },
            teamCount = teamCount,
            minPlayersPerTeam = minPlayersPerTeam,
        )
        return Triple(bus, mm, timeline)
    }

    private fun pid() = PlayerId(UUID.randomUUID())

    @Test
    fun `enqueue records entry and publishes MatchQueueJoined`() {
        val (bus, mm, _) = build()
        val p = pid()
        val res = mm.enqueue(p, MatchmakingMode.Norma)
        assertThat(res.isSuccess).isTrue
        val entry = res.getOrNull()!!
        assertThat(entry.playerId).isEqualTo(p)
        assertThat(entry.mode).isEqualTo(MatchmakingMode.Norma)
        assertThat(bus.published.filterIsInstance<MatchQueueJoined>()).hasSize(1)
        assertThat(bus.published.filterIsInstance<MatchQueueJoined>().first().modeKey).isEqualTo("Norma")
    }

    @Test
    fun `enqueue twice for same player returns AlreadyEnqueued`() {
        val (_, mm, _) = build()
        val p = pid()
        mm.enqueue(p, MatchmakingMode.Norma)
        val again = mm.enqueue(p, MatchmakingMode.Norma)
        assertThat(again).isInstanceOf(Result.Failure::class.java)
        assertThat((again as Result.Failure).error).isInstanceOf(QueueError.AlreadyEnqueued::class.java)
    }

    @Test
    fun `dequeue removes entry and publishes MatchQueueLeft`() {
        val (bus, mm, _) = build()
        val p = pid()
        mm.enqueue(p, MatchmakingMode.Norma)
        val res = mm.dequeue(p)
        assertThat(res.isSuccess).isTrue
        assertThat(mm.queueStatus(p)).isNull()
        val leftEvents = bus.published.filterIsInstance<MatchQueueLeft>()
        assertThat(leftEvents).hasSize(1)
        assertThat(leftEvents.first().reasonTag).isEqualTo("player-dequeued")
    }

    @Test
    fun `dequeue for unenqueued player returns NotEnqueued`() {
        val (_, mm, _) = build()
        val res = mm.dequeue(pid())
        assertThat(res).isInstanceOf(Result.Failure::class.java)
        assertThat((res as Result.Failure).error).isInstanceOf(QueueError.NotEnqueued::class.java)
    }

    @Test
    fun `tryFormMatch returns null when below the target floor`() {
        val (_, mm, _) = build(teamCount = 2, minPlayersPerTeam = 1)
        mm.enqueue(pid(), MatchmakingMode.Norma)
        val res = mm.tryFormMatch(MatchmakingMode.Norma)
        assertThat(res.isSuccess).isTrue
        assertThat(res.getOrNull()).isNull()
    }

    @Test
    fun `tryFormMatch consumes entries and publishes MatchFormed`() {
        val (bus, mm, _) = build(teamCount = 2, minPlayersPerTeam = 1)
        val a = pid(); val b = pid()
        mm.enqueue(a, MatchmakingMode.Norma)
        mm.enqueue(b, MatchmakingMode.Norma)
        val res = mm.tryFormMatch(MatchmakingMode.Norma)
        val formation = res.getOrNull()!!
        assertThat(formation.players).containsExactlyInAnyOrder(a, b)
        assertThat(formation.teams).hasSize(2)
        assertThat(mm.pendingByMode(MatchmakingMode.Norma)).isEmpty()
        assertThat(bus.published.filterIsInstance<MatchFormed>()).hasSize(1)
    }

    @Test
    fun `tryFormMatch is idempotent - second call returns null`() {
        val (_, mm, _) = build()
        val a = pid(); val b = pid()
        mm.enqueue(a, MatchmakingMode.Norma)
        mm.enqueue(b, MatchmakingMode.Norma)
        val first = mm.tryFormMatch(MatchmakingMode.Norma)
        val second = mm.tryFormMatch(MatchmakingMode.Norma)
        assertThat(first.getOrNull()).isNotNull
        assertThat(second.getOrNull()).isNull()
    }

    @Test
    fun `party members land on the same team`() {
        // Match floor = 2 teams × 2 players = 4. Party of 2 takes one team
        // entirely; the remaining 2 solos take the other.
        val (_, mm, _) = build(teamCount = 2, minPlayersPerTeam = 2)
        val party = PartyId.random()
        val pa = pid(); val pb = pid()
        val s1 = pid(); val s2 = pid()
        mm.enqueue(pa, MatchmakingMode.Norma, partyId = party)
        mm.enqueue(pb, MatchmakingMode.Norma, partyId = party)
        mm.enqueue(s1, MatchmakingMode.Norma)
        mm.enqueue(s2, MatchmakingMode.Norma)

        val formation = mm.tryFormMatch(MatchmakingMode.Norma).getOrNull()!!
        val partyTeam = formation.teams.first { pa in it }
        assertThat(partyTeam).contains(pa, pb)
        val otherTeam = formation.teams.first { pa !in it }
        assertThat(otherTeam).containsExactlyInAnyOrder(s1, s2)
    }

    @Test
    fun `closeMode then enqueue returns ModeClosed`() {
        val (_, mm, _) = build()
        mm.closeMode(MatchmakingMode.Norma)
        val res = mm.enqueue(pid(), MatchmakingMode.Norma)
        assertThat(res).isInstanceOf(Result.Failure::class.java)
        assertThat((res as Result.Failure).error).isInstanceOf(QueueError.ModeClosed::class.java)
    }

    @Test
    fun `queueStatus returns null for unenqueued player`() {
        val (_, mm, _) = build()
        assertThat(mm.queueStatus(pid())).isNull()
    }

    @Test
    fun `pendingByMode returns entries in FIFO order`() {
        val (_, mm, timeline) = build()
        val a = pid(); val b = pid(); val c = pid()
        mm.enqueue(a, MatchmakingMode.Norma); timeline += baseClock.plusSeconds(1)
        mm.enqueue(b, MatchmakingMode.Norma); timeline += baseClock.plusSeconds(2)
        mm.enqueue(c, MatchmakingMode.Norma)
        val pending = mm.pendingByMode(MatchmakingMode.Norma).toList()
        assertThat(pending.map { it.playerId }).containsExactly(a, b, c)
    }

    @Test
    fun `Custom mode keyed separately from Norma`() {
        val (_, mm, _) = build()
        val gm = com.astarworks.astera.match.domain.mode.GameModeId("break-core")
        mm.enqueue(pid(), MatchmakingMode.Norma)
        mm.enqueue(pid(), MatchmakingMode.Custom(gm))
        assertThat(mm.pendingByMode(MatchmakingMode.Norma)).hasSize(1)
        assertThat(mm.pendingByMode(MatchmakingMode.Custom(gm))).hasSize(1)
    }

    @Test
    fun `Tournament mode keyed by tournamentMatchId`() {
        val (_, mm, _) = build()
        mm.enqueue(pid(), MatchmakingMode.Tournament("cup-2026-spring"))
        mm.enqueue(pid(), MatchmakingMode.Tournament("cup-2026-fall"))
        assertThat(mm.pendingByMode(MatchmakingMode.Tournament("cup-2026-spring"))).hasSize(1)
        assertThat(mm.pendingByMode(MatchmakingMode.Tournament("cup-2026-fall"))).hasSize(1)
    }

    @Test
    fun `per-mode sizing forms a 2v2 only once four players queue`() {
        val bus = FakeDomainEventBus()
        val mm = InMemoryMatchmaker(
            eventBus = bus,
            clock = { baseClock },
            sizeByMode = { MatchSizeSpec.TWO_V_TWO },
        )
        repeat(3) { mm.enqueue(pid(), MatchmakingMode.Norma) }
        assertThat(mm.tryFormMatch(MatchmakingMode.Norma).getOrNull()).isNull()

        mm.enqueue(pid(), MatchmakingMode.Norma)
        val formation = mm.tryFormMatch(MatchmakingMode.Norma).getOrNull()!!
        assertThat(formation.players).hasSize(4)
        assertThat(formation.teams).hasSize(2)
        assertThat(formation.teams).allSatisfy { assertThat(it).hasSize(2) }
    }

    @Test
    fun `solos are balanced across teams by rating`() {
        val ratings = mutableMapOf<PlayerId, Rating>()
        val mm = InMemoryMatchmaker(
            eventBus = FakeDomainEventBus(),
            clock = { baseClock },
            ratingLookup = { ratings[it] ?: Rating.DEFAULT },
            sizeByMode = { MatchSizeSpec.TWO_V_TWO },
        )
        val strong1 = pid(); val strong2 = pid(); val weak1 = pid(); val weak2 = pid()
        ratings[strong1] = Rating(1600)
        ratings[strong2] = Rating(1500)
        ratings[weak1] = Rating(1000)
        ratings[weak2] = Rating(900)
        listOf(strong1, strong2, weak1, weak2).forEach { mm.enqueue(it, MatchmakingMode.Norma) }

        val formation = mm.tryFormMatch(MatchmakingMode.Norma).getOrNull()!!
        fun teamAverage(team: Set<PlayerId>) = team.map { ratings.getValue(it).value }.average()
        val averages = formation.teams.map { teamAverage(it) }
        // Snake-draft splits the strong pair across the two teams.
        assertThat(kotlin.math.abs(averages[0] - averages[1])).isLessThanOrEqualTo(150.0)
        // Sanity: the two top-rated players are not stacked on one team.
        val strongTeam = formation.teams.first { strong1 in it }
        assertThat(strongTeam).doesNotContain(strong2)
    }

    @Test
    fun `oversized party returns PartyTooLargeForMode`() {
        // 2 teams × 1 player floor — a party of 2 exceeds the per-team cap.
        val (_, mm, _) = build(teamCount = 2, minPlayersPerTeam = 1)
        val party = PartyId.random()
        val pa = pid(); val pb = pid(); val solo1 = pid(); val solo2 = pid()
        mm.enqueue(pa, MatchmakingMode.Norma, partyId = party)
        mm.enqueue(pb, MatchmakingMode.Norma, partyId = party)
        mm.enqueue(solo1, MatchmakingMode.Norma)
        mm.enqueue(solo2, MatchmakingMode.Norma)
        val res = mm.tryFormMatch(MatchmakingMode.Norma)
        // The matchmaker rejects the oversized party for this mode.
        assertThat(res).isInstanceOf(Result.Failure::class.java)
        assertThat((res as Result.Failure).error).isInstanceOf(QueueError.PartyTooLargeForMode::class.java)
    }

    @Test
    fun `min players below the roster cap forms a partly filled balanced match`() {
        val mm = InMemoryMatchmaker(
            eventBus = FakeDomainEventBus(),
            clock = { baseClock },
            sizeByMode = { MatchSizeSpec(teamCount = 2, playersPerTeam = 4, minPlayers = 1) },
        )
        mm.enqueue(pid(), MatchmakingMode.Norma)
        val solo = mm.tryFormMatch(MatchmakingMode.Norma).getOrNull()!!
        assertThat(solo.players).hasSize(1)
        assertThat(solo.teams.map { it.size }).containsExactlyInAnyOrder(1, 0)

        repeat(5) { mm.enqueue(pid(), MatchmakingMode.Norma) }
        val five = mm.tryFormMatch(MatchmakingMode.Norma).getOrNull()!!
        assertThat(five.players).hasSize(5)
        assertThat(five.teams.map { it.size }).containsExactlyInAnyOrder(3, 2)
        assertThat(mm.pendingByMode(MatchmakingMode.Norma)).isEmpty()
    }

    @Test
    fun `a full queue takes at most the roster cap and leaves the rest queued`() {
        val mm = InMemoryMatchmaker(
            eventBus = FakeDomainEventBus(),
            clock = { baseClock },
            sizeByMode = { MatchSizeSpec(teamCount = 2, playersPerTeam = 2, minPlayers = 2) },
        )
        repeat(6) { mm.enqueue(pid(), MatchmakingMode.Norma) }
        val formation = mm.tryFormMatch(MatchmakingMode.Norma).getOrNull()!!
        assertThat(formation.players).hasSize(4)
        assertThat(mm.pendingByMode(MatchmakingMode.Norma)).hasSize(2)
    }

    @Test
    fun `below min players nothing is formed or consumed`() {
        val mm = InMemoryMatchmaker(
            eventBus = FakeDomainEventBus(),
            clock = { baseClock },
            sizeByMode = { MatchSizeSpec(teamCount = 2, playersPerTeam = 4, minPlayers = 3) },
        )
        repeat(2) { mm.enqueue(pid(), MatchmakingMode.Norma) }
        assertThat(mm.tryFormMatch(MatchmakingMode.Norma).getOrNull()).isNull()
        assertThat(mm.pendingByMode(MatchmakingMode.Norma)).hasSize(2)
    }
}
