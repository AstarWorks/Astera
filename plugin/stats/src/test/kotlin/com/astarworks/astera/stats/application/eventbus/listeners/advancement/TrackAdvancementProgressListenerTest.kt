package com.astarworks.astera.stats.application.eventbus.listeners.advancement

import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.contents.AdvancementContents
import com.astarworks.astera.core.application.port.inbound.IAdvancementService
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.advancement.AdvancementCriteria
import com.astarworks.astera.core.domain.advancement.AdvancementError
import com.astarworks.astera.core.domain.advancement.AdvancementId
import com.astarworks.astera.core.domain.advancement.AdvancementProgress
import com.astarworks.astera.core.domain.advancement.CustomAdvancement
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.event.CurrencyChangeReason
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.ItemAcquired
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.Score
import com.astarworks.astera.core.domain.match.Team
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.event.CurrencyChanged
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Unit tests for [TrackAdvancementProgressListener] (Wave 5p, ADR-0088).
 *
 * Asserts the fan-out routing: each event type maps to the right criterion
 * leaf and calls the recording stub service with the right arguments.
 */
class TrackAdvancementProgressListenerTest {

    private val killer = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val victim = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
    private val iconItem = ItemId("example-sword")
    private val killAdv = AdvancementId("kill-three")
    private val currAdv = AdvancementId("earn-gold")
    private val matchAdv = AdvancementId("win-one")
    private val itemAdv = AdvancementId("acquire-sword")

    private lateinit var registry: AdvancementContents
    private lateinit var recorded: MutableList<Triple<PlayerId, AdvancementId, Int>>
    private lateinit var service: RecordingAdvancementService
    private lateinit var matchRepo: StubMatchRepository
    private lateinit var listener: TrackAdvancementProgressListener

    @BeforeEach
    fun setUp() {
        registry = AdvancementContents().also {
            it.register(
                CustomAdvancement(
                    id = killAdv,
                    titleKey = MessageKey("astera.advancement.kill_three.title"),
                    descriptionKey = MessageKey("astera.advancement.kill_three.desc"),
                    iconItemId = iconItem,
                    criteria = AdvancementCriteria.KillCount(3),
                    rewards = emptyList(),
                ),
            )
            it.register(
                CustomAdvancement(
                    id = currAdv,
                    titleKey = MessageKey("astera.advancement.earn_gold.title"),
                    descriptionKey = MessageKey("astera.advancement.earn_gold.desc"),
                    iconItemId = iconItem,
                    criteria = AdvancementCriteria.CurrencyEarned(Currency.GOLD, 100),
                    rewards = emptyList(),
                ),
            )
            it.register(
                CustomAdvancement(
                    id = matchAdv,
                    titleKey = MessageKey("astera.advancement.win_one.title"),
                    descriptionKey = MessageKey("astera.advancement.win_one.desc"),
                    iconItemId = iconItem,
                    criteria = AdvancementCriteria.MatchesWon(1),
                    rewards = emptyList(),
                ),
            )
            it.register(
                CustomAdvancement(
                    id = itemAdv,
                    titleKey = MessageKey("astera.advancement.acquire_sword.title"),
                    descriptionKey = MessageKey("astera.advancement.acquire_sword.desc"),
                    iconItemId = iconItem,
                    criteria = AdvancementCriteria.ItemAcquired(ItemId("example-sword")),
                    rewards = emptyList(),
                ),
            )
        }
        recorded = mutableListOf()
        service = RecordingAdvancementService(recorded)
        matchRepo = StubMatchRepository()
        listener = TrackAdvancementProgressListener(service, registry, matchRepo)
    }

    @Test
    fun `PlayerKilled progresses every kill-count criterion for the killer`() {
        listener.handle(
            PlayerKilled(
                victim = victim,
                killer = killer,
                source = DamageSource.Environment,
                atTick = 100L,
            ),
        )
        assertThat(recorded).contains(Triple(killer, killAdv, 1))
    }

    @Test
    fun `PlayerKilled with no killer is ignored`() {
        listener.handle(
            PlayerKilled(
                victim = victim,
                killer = null,
                source = DamageSource.Environment,
                atTick = 100L,
            ),
        )
        assertThat(recorded).isEmpty()
    }

    @Test
    fun `CurrencyChanged with positive delta progresses earned criteria`() {
        listener.handle(
            CurrencyChanged(
                playerId = killer,
                currency = Currency.GOLD,
                delta = 50L,
                newBalance = 50L,
                reason = CurrencyChangeReason.MatchReward,
            ),
        )
        assertThat(recorded).contains(Triple(killer, currAdv, 50))
    }

    @Test
    fun `CurrencyChanged with non-positive delta is ignored`() {
        listener.handle(
            CurrencyChanged(
                playerId = killer,
                currency = Currency.GOLD,
                delta = -10L,
                newBalance = 90L,
                reason = CurrencyChangeReason.Withdraw,
            ),
        )
        assertThat(recorded).isEmpty()
    }

    @Test
    fun `MatchEnded progresses winning-team players`() {
        val matchId = MatchId("test-match-1")
        val winningTeamId = TeamId("red")
        matchRepo.put(
            Match(
                id = matchId,
                phase = MatchPhase.ENDED,
                teams = listOf(
                    Team(
                        id = winningTeamId,
                        displayNameKey = MessageKey("astera.team.red.name"),
                        colorKey = MessageKey("astera.team.red.color"),
                        members = setOf(killer),
                    ),
                    Team(
                        id = TeamId("blue"),
                        displayNameKey = MessageKey("astera.team.blue.name"),
                        colorKey = MessageKey("astera.team.blue.color"),
                        members = setOf(victim),
                    ),
                ),
                score = Score(emptyMap()),
                stageId = StageId("demo"),
                startedAtTick = 0L,
            ),
        )
        listener.handle(
            MatchEnded(
                matchId = matchId,
                winningTeam = winningTeamId,
                atTick = 100L,
                reason = EndReason.ObjectiveCompleted,
            ),
        )
        assertThat(recorded).contains(Triple(killer, matchAdv, 1))
        assertThat(recorded.none { it.first == victim }).isTrue
    }

    @Test
    fun `ItemAcquired progresses matching item criterion`() {
        listener.handle(
            ItemAcquired(
                playerId = killer,
                itemId = ItemId("example-sword"),
                count = 1,
                atTick = 100L,
            ),
        )
        assertThat(recorded).contains(Triple(killer, itemAdv, 1))
    }

    private class RecordingAdvancementService(
        private val recorded: MutableList<Triple<PlayerId, AdvancementId, Int>>,
    ) : IAdvancementService {
        override fun progress(
            playerId: PlayerId,
            advancementId: AdvancementId,
            increment: Int,
        ): Result<AdvancementProgress, AdvancementError> {
            recorded.add(Triple(playerId, advancementId, increment))
            return Result.success(AdvancementProgress(current = increment, target = increment, completedAt = null))
        }
        override fun completed(playerId: PlayerId): Set<AdvancementId> = emptySet()
        override fun progressOf(playerId: PlayerId, advancementId: AdvancementId): AdvancementProgress? = null
    }

    private class StubMatchRepository : IMatchRepository {
        private val byId = mutableMapOf<MatchId, Match>()
        fun put(match: Match) { byId[match.id] = match }
        override fun findById(id: MatchId): Match? = byId[id]
        override fun all(): Collection<Match> = byId.values
        override fun save(entity: Match) { byId[entity.id] = entity }
        override fun delete(id: MatchId) { byId.remove(id) }
        override fun findActiveMatches(): Collection<Match> = emptyList()
        override fun findMatchOf(playerId: PlayerId): Match? = null
        override fun findInPhase(phase: MatchPhase): Collection<Match> = emptyList()
    }
}
