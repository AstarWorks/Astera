package com.astarworks.astera.stats.command

import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.command.forEveryone
import com.astarworks.astera.core.application.port.inbound.IQuestService
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IQuestChainRegistry
import com.astarworks.astera.core.application.port.outbound.loginbonus.ILoginBonusService
import com.astarworks.astera.core.domain.advancement.AdvancementCriteria
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.norma.PeriodKind
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.application.port.outbound.IAchievementRepository
import com.astarworks.astera.stats.application.port.outbound.INormaService
import java.time.Clock
import java.time.LocalDate

/**
 * The progression screens (ADR-0210), each a top-level `/astera` area for every player:
 *
 * ```
 * /astera daily          collect today's login bonus, or show the streak and tomorrow's reward
 * /astera missions       daily / weekly missions with progress in the current period
 * /astera quests         quest chains: current stage and progress
 * /astera achievements   the achievement catalog with what you have unlocked
 * ```
 */
internal class ProgressionCommands(
    private val loginBonus: ILoginBonusService,
    /** Collects and announces today's bonus; `false` when there was nothing to collect. */
    private val claimDaily: (PlayerId) -> Boolean,
    private val normas: INormaService,
    private val quests: IQuestService,
    private val questChains: IQuestChainRegistry,
    private val achievements: IAchievementRepository,
    private val messages: IMessageRenderer,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    fun build(): List<CommandSpec.Literal> = listOf(daily(), missions(), quests(), achievementsList())

    private fun daily(): CommandSpec.Literal = CommandSpec.Literal(
        name = "daily",
        executor = lambda@{ ctx ->
            val me = ctx.sender ?: return@lambda CommandResult.Failure(MessageKey("astera.command.player-only"))
            // Collecting announces itself (chat + toast); an already-collected day falls through to the status.
            if (claimDaily(me)) return@lambda CommandResult.Success
            val status = loginBonus.status(me, LocalDate.now(clock))
            ctx.reply(MessageKey("astera.progress.daily.header"))
            ctx.reply(
                MessageKey("astera.progress.daily.status"),
                mapOf("streak" to status.streakDays.toString(), "day" to status.day.toString()),
            )
            ctx.reply(MessageKey(if (status.claimedToday) "astera.progress.daily.claimed" else "astera.progress.daily.unavailable"))
            status.tomorrow?.let {
                ctx.reply(
                    MessageKey("astera.progress.daily.tomorrow"),
                    mapOf("amount" to it.amount.toString(), "currency" to it.currency.name),
                )
            }
            CommandResult.Success
        },
    ).forEveryone(Permission("astera.progress.daily"))

    private fun missions(): CommandSpec.Literal = CommandSpec.Literal(
        name = "missions",
        executor = lambda@{ ctx ->
            val me = ctx.sender ?: return@lambda CommandResult.Failure(MessageKey("astera.command.player-only"))
            val views = normas.views(me).sortedWith(compareBy({ it.norma.period.ordinal }, { it.norma.id.value }))
            if (views.isEmpty()) {
                ctx.reply(MessageKey("astera.progress.missions.none"))
                return@lambda CommandResult.Success
            }
            ctx.reply(MessageKey("astera.progress.missions.header"))
            for (view in views) {
                ctx.reply(
                    MessageKey(if (view.completed) "astera.progress.missions.line-done" else "astera.progress.missions.line"),
                    mapOf(
                        "period" to messages.render(me, MessageKey("astera.progress.period.${periodToken(view.norma.period)}"), emptyMap()),
                        "name" to messages.render(me, view.norma.displayKey, emptyMap()),
                        "current" to view.current.coerceAtMost(view.required).toString(),
                        "required" to view.required.toString(),
                    ),
                )
            }
            CommandResult.Success
        },
    ).forEveryone(Permission("astera.progress.missions"))

    private fun quests(): CommandSpec.Literal = CommandSpec.Literal(
        name = "quests",
        executor = lambda@{ ctx ->
            val me = ctx.sender ?: return@lambda CommandResult.Failure(MessageKey("astera.command.player-only"))
            val rows = quests.allChains(me).sortedBy { it.chainId.value }
            if (rows.isEmpty()) {
                ctx.reply(MessageKey("astera.progress.quests.none"))
                return@lambda CommandResult.Success
            }
            ctx.reply(MessageKey("astera.progress.quests.header"))
            for (row in rows) {
                val chain = questChains.find(row.chainId) ?: continue
                val quest = messages.render(me, chain.titleKey, emptyMap())
                if (row.isComplete) {
                    ctx.reply(MessageKey("astera.progress.quests.line-done"), mapOf("quest" to quest))
                    continue
                }
                val stage = chain.stage(row.currentStageId) ?: continue
                ctx.reply(
                    MessageKey("astera.progress.quests.line"),
                    mapOf(
                        "quest" to quest,
                        "stage" to messages.render(me, stage.titleKey, emptyMap()),
                        "current" to row.stageProgress.toString(),
                        "required" to targetOf(stage.criteria).toString(),
                    ),
                )
            }
            CommandResult.Success
        },
    ).forEveryone(Permission("astera.progress.quests"))

    private fun achievementsList(): CommandSpec.Literal = CommandSpec.Literal(
        name = "achievements",
        executor = lambda@{ ctx ->
            val me = ctx.sender ?: return@lambda CommandResult.Failure(MessageKey("astera.command.player-only"))
            val catalog = achievements.list().sortedBy { it.id.value }
            val unlocked = achievements.unlocked(me)
            ctx.reply(
                MessageKey("astera.progress.achievements.header"),
                mapOf("unlocked" to catalog.count { it.id in unlocked }.toString(), "total" to catalog.size.toString()),
            )
            for (achievement in catalog) {
                ctx.reply(
                    MessageKey(if (achievement.id in unlocked) "astera.progress.achievements.line-unlocked" else "astera.progress.achievements.line-locked"),
                    mapOf(
                        "name" to messages.render(me, achievement.nameKey, emptyMap()),
                        "description" to messages.render(me, achievement.descriptionKey, emptyMap()),
                    ),
                )
            }
            CommandResult.Success
        },
    ).forEveryone(Permission("astera.progress.achievements"))

    private fun periodToken(period: PeriodKind): String = period.name.lowercase()

    /** The scalar a stage counts towards; mirrors the quest service (least-progressed leaf of AllOf, most-progressed of AnyOf). */
    private fun targetOf(criteria: AdvancementCriteria): Int = when (criteria) {
        is AdvancementCriteria.KillCount -> criteria.target
        is AdvancementCriteria.CurrencyEarned -> criteria.amount.toInt().coerceAtLeast(1)
        is AdvancementCriteria.MatchesWon -> criteria.count
        is AdvancementCriteria.ItemAcquired -> 1
        is AdvancementCriteria.AllOf -> criteria.children.maxOf { targetOf(it) }
        is AdvancementCriteria.AnyOf -> criteria.children.minOf { targetOf(it) }
    }
}
