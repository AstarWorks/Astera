package com.astarworks.astera.economy.application.eventbus.listeners

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.ILastMatchStore
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.event.CurrencyChangeReason
import com.astarworks.astera.core.domain.event.MatchRewardsGranted
import com.astarworks.astera.core.domain.event.MatchSummaryReady
import com.astarworks.astera.core.domain.event.XpSource
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.result.LastMatchRecord
import com.astarworks.astera.core.domain.match.result.MatchOutcome
import com.astarworks.astera.core.domain.match.result.MatchRewardResult
import com.astarworks.astera.core.domain.match.result.PlayerMatchLine
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.application.usecase.currency.DepositUseCase
import com.astarworks.astera.economy.domain.reward.MatchRewardCalculator
import com.astarworks.astera.economy.domain.reward.MatchRewardTable
import com.astarworks.astera.stats.application.port.outbound.ILevelService
import com.astarworks.astera.stats.application.usecase.level.GrantXpUseCase
import com.astarworks.astera.stats.domain.level.XpCurve
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

/**
 * Pays every participant of a finished match from the reward table
 * (`content/balance/match-rewards.yaml`, F3-4) exactly once, tells them what
 * they got, and records the result for the lobby.
 *
 * Per player: GOLD goes through [DepositUseCase] (reason `MatchReward`),
 * XP through [GrantXpUseCase] (source `MatchWin` for a win, `MatchParticipation`
 * otherwise; XP never converts to a currency on top), then a breakdown goes to chat, the [LastMatchRecord] is saved to the
 * shared player store, and finally one [MatchRewardsGranted] is published.
 *
 * "Once" is per match: a second [MatchSummaryReady] for the same match id is
 * ignored. A failing deposit for one player is logged and does not stop the
 * others.
 *
 * XP needs the stats plugin; without it ([levels] returns `null`) the XP rows
 * are skipped and the level lines are omitted.
 */
public class GrantMatchRewardsOnSummaryListener(
    private val table: () -> MatchRewardTable,
    private val deposit: DepositUseCase,
    private val levels: () -> ILevelService?,
    private val eventBus: IDomainEventBus,
    private val messages: IMessageRenderer,
    private val playerGateway: IPlayerGateway,
    private val lastMatches: () -> ILastMatchStore?,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val paid: MutableSet<MatchId> = ConcurrentHashMap.newKeySet()

    public suspend fun handle(event: MatchSummaryReady) {
        val summary = event.summary
        if (!paid.add(summary.matchId)) {
            log.debug("GrantMatchRewards: match {} already paid, ignoring", summary.matchId)
            return
        }
        val currentTable = table()
        val results = LinkedHashMap<PlayerId, MatchRewardResult>()
        for (line in summary.players) {
            val outcome = summary.outcomeOf(line.playerId)
            val planned = MatchRewardCalculator.calculate(currentTable, summary.modeKey, outcome, line, line.playerId == summary.mvp)
            val result = pay(line.playerId, planned, summary.matchId)
            results[line.playerId] = result
            announce(line.playerId, result)
            lastMatches()?.save(line.playerId, record(summary, line, result))
        }
        eventBus.publish(MatchRewardsGranted(summary.matchId, results))
    }

    private suspend fun pay(playerId: PlayerId, planned: MatchRewardResult, matchId: MatchId): MatchRewardResult {
        credit(playerId, Currency.GOLD, planned.gold, matchId)
        val levelService = levels() ?: return planned
        val before = levelService.currentLevel(playerId).value
        var after = before
        if (planned.xp > 0L) {
            val source = if (planned.outcome == MatchOutcome.WIN) XpSource.MatchWin else XpSource.MatchParticipation
            GrantXpUseCase(levelService, eventBus).execute(playerId, planned.xp, source)
                .onFailure { log.debug("GrantMatchRewards: xp grant failed for {} match {}: {}", playerId, matchId, it) }
            after = levelService.currentLevel(playerId).value
        }
        val total = levelService.totalXp(playerId).value
        val base = XpCurve.thresholdToReach(after)
        return planned.copy(
            levelBefore = before,
            levelAfter = after,
            xpIntoLevel = total - base,
            xpForNextLevel = XpCurve.thresholdToReach(after + 1) - base,
        )
    }

    private suspend fun credit(playerId: PlayerId, currency: Currency, amount: Long, matchId: MatchId) {
        if (amount <= 0L) return
        deposit.execute(playerId, currency, amount, CurrencyChangeReason.MatchReward)
            .onFailure { log.warn("GrantMatchRewards: {} {} for {} (match {}) failed: {}", amount, currency, playerId, matchId, it) }
    }

    private fun announce(playerId: PlayerId, result: MatchRewardResult) {
        fun send(key: String, vararg params: Pair<String, String>) =
            playerGateway.sendMessage(playerId, messages.render(playerId, MessageKey(key), params.toMap()))

        send("astera.match.reward.header")
        if (result.lines.isEmpty()) {
            send("astera.match.reward.none")
            return
        }
        for (row in result.lines) {
            val amounts = buildList {
                if (row.gold > 0) add(messages.render(playerId, MessageKey("astera.match.reward.amount.gold"), mapOf("amount" to row.gold.toString())))
                if (row.xp > 0) add(messages.render(playerId, MessageKey("astera.match.reward.amount.xp"), mapOf("amount" to row.xp.toString())))
            }.joinToString(" ")
            send(
                "astera.match.reward.line",
                "label" to messages.render(
                    playerId,
                    MessageKey("astera.match.reward.kind.${row.kind.name.lowercase()}"),
                    mapOf("count" to row.count.toString()),
                ),
                "amounts" to amounts,
            )
        }
        send(
            "astera.match.reward.total",
            "gold" to result.gold.toString(),
            "xp" to result.xp.toString(),
        )
        if (result.levelAfter > 0) {
            if (result.leveledUp) {
                send("astera.match.reward.levelup", "from" to result.levelBefore.toString(), "to" to result.levelAfter.toString())
            }
            send(
                "astera.match.reward.level",
                "level" to result.levelAfter.toString(),
                "into" to result.xpIntoLevel.toString(),
                "next" to result.xpForNextLevel.toString(),
            )
        }
    }

    private fun record(
        summary: com.astarworks.astera.core.domain.match.result.MatchSummary,
        line: PlayerMatchLine,
        result: MatchRewardResult,
    ): LastMatchRecord = LastMatchRecord(
        matchId = summary.matchId.value,
        endedAtEpochSec = clock.instant().epochSecond,
        modeKey = summary.modeKey,
        outcome = result.outcome,
        teams = summary.teams.map { it.team.value to it.score },
        kills = line.kills,
        deaths = line.deaths,
        assists = line.assists,
        objective = line.objective,
        mvp = line.playerId == summary.mvp,
        gold = result.gold,
        xp = result.xp,
        level = result.levelAfter,
        xpIntoLevel = result.xpIntoLevel,
        xpForNextLevel = result.xpForNextLevel,
    )
}
