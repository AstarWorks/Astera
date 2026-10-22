package com.astarworks.astera.match.scenario

import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.Region
import com.astarworks.astera.core.domain.stage.Stage
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.contents.StageContents
import com.astarworks.astera.match.application.eventbus.listeners.death.ApplyPvpDeathPolicyListener
import com.astarworks.astera.match.application.eventbus.listeners.match.MatchCountdownListener
import com.astarworks.astera.match.application.eventbus.listeners.match.MatchEndSummaryListener
import com.astarworks.astera.match.application.gamemode.annihilation.AnnihilationMatchTracker
import com.astarworks.astera.match.application.port.outbound.IMatchModeRegistry
import com.astarworks.astera.match.application.port.outbound.IPvpDeathPolicyResolver
import com.astarworks.astera.match.application.service.InMemoryMatchModeRegistry
import com.astarworks.astera.match.application.service.InMemoryMatchRepository
import com.astarworks.astera.match.application.service.death.DefaultPvpDeathPolicyResolver
import com.astarworks.astera.match.application.usecase.match.EndMatchUseCase
import com.astarworks.astera.match.application.usecase.match.EntryGameUseCase
import com.astarworks.astera.match.application.usecase.match.StartMatchUseCase
import com.astarworks.astera.match.application.usecase.match.SwitchPhaseError
import com.astarworks.astera.match.application.usecase.match.SwitchPhaseUseCase
import com.astarworks.astera.match.application.usecase.match.VictoryConditionEvaluator
import com.astarworks.astera.match.domain.match.MatchStateMachine
import com.astarworks.astera.core.application.service.inventory.InMemoryInventoryMutator
import com.astarworks.astera.testfixtures.FakeHudGateway
import com.astarworks.astera.testfixtures.FakeMessageRenderer
import com.astarworks.astera.testfixtures.FakePlayerGateway
import com.astarworks.astera.testfixtures.FakeScheduler
import java.util.UUID

/**
 * Base test harness for Astera gameplay scenario tests (Wave
 * 5game-complete, ADR-0121 §G).
 *
 * # What's wired
 *
 *  - In-memory [IDomainEventBus] (production
 *    [InMemoryDomainEventBus]) — listeners attach here.
 *  - [InMemoryMatchRepository] + [StageContents] + lifecycle use cases
 *    (Start / Entry / SwitchPhase / End).
 *  - [InMemoryMatchModeRegistry] mirrored from `MatchStarted.modeKey`
 *    so SwitchPhase's min-player guard sees the right floor.
 *  - All ADR-0121 §C/§D/§E listeners (Countdown / EndSummary / PvP
 *    death policy) so scenarios can assert the chat / HUD / inventory
 *    side-effects.
 *  - Event sniffer subscribed to `DomainEvent::class`; every published
 *    event is appended to [publishedEvents] for typed assertions.
 *
 * # What's intentionally NOT wired
 *
 *  - Combat damage application — scenarios fake kills via
 *    `bus.publish(PlayerKilled(...))`, mirroring the Wave _infra-1
 *    `kills()` DSL idiom (no `ApplyDamageUseCase` setup means no
 *    armor-modifier accuracy concerns either).
 *  - Stats reward / XP — that's the Wave _infra-1
 *    `AsteraTestHarness.newDefault()` job in the stats plugin. Match
 *    scenarios don't need it.
 *  - Paper / Bukkit — every fake here is JVM-only.
 *
 * # Why a separate harness from `stats/scenario/AsteraTestHarness`?
 *
 * That harness lives in `plugin/stats` and pulls in stats-only fakes
 * (norma / XP / leveling). Match-scenario tests would otherwise need
 * `testImplementation(project(":plugin:stats"))`, a circular dep into
 * a sibling that depends on this very plugin. Splitting the harness
 * keeps the dep direction clean.
 */
public class MatchScenarioHarness private constructor(
    public val eventBus: IDomainEventBus,
    public val scheduler: FakeScheduler,
    public val matches: InMemoryMatchRepository,
    public val stages: StageContents,
    public val modeRegistry: IMatchModeRegistry,
    public val pvpResolver: IPvpDeathPolicyResolver,
    public val hudGateway: FakeHudGateway,
    public val messages: FakeMessageRenderer,
    public val playerGateway: FakePlayerGateway,
    public val inventoryMutator: InMemoryInventoryMutator,
    public val annihilationTracker: AnnihilationMatchTracker,
    public val publishedEvents: MutableList<DomainEvent>,
) {

    private val start = StartMatchUseCase(stages, matches, eventBus, scheduler)
    private val end = EndMatchUseCase(matches, eventBus, scheduler, VictoryConditionEvaluator())
    private val entry = EntryGameUseCase(matches, eventBus)
    private val switch = SwitchPhaseUseCase(matches, eventBus, scheduler, modeRegistry)

    /** Spawn a fake player id (no Paper presence, no profile). */
    public fun spawnPlayer(name: String): PlayerId {
        return PlayerId(UUID.randomUUID()).also {
            playerGateway.register(name, it)
        }
    }

    /** Register [stageId] in [stages] if missing. */
    public fun ensureStage(stageId: StageId) {
        if (stages.find(stageId) == null) {
            stages.register(
                Stage(
                    id = stageId,
                    displayNameKey = MessageKey("astera.stage.${stageId.value}"),
                    regions = emptyList<Region>(),
                ),
            )
        }
    }

    /** Run StartMatchUseCase + mirror modeKey into registry the way AsteraMatchPlugin does. */
    public fun startMatch(stageId: StageId, modeKey: String? = null): MatchId {
        ensureStage(stageId)
        return when (val r = start.execute(stageId, modeKey = modeKey)) {
            is Result.Success -> r.value.also { modeRegistry.register(it, modeKey) }
            is Result.Failure -> error("startMatch failed: ${r.error}")
        }
    }

    /** Enroll [playerId] in [matchId] on [teamId]. */
    public fun entry(matchId: MatchId, playerId: PlayerId, teamId: TeamId) {
        when (val r = entry.execute(matchId, playerId, teamId)) {
            is Result.Success -> Unit
            is Result.Failure -> error("entry failed: ${r.error}")
        }
    }

    /** SwitchPhase with the given trigger. Returns the (Result-wrapped) outcome. */
    public fun switchPhase(
        matchId: MatchId,
        trigger: MatchStateMachine.TransitionTrigger,
    ): Result<MatchPhase, SwitchPhaseError> = switch.execute(matchId, trigger)

    /** End [matchId] with [reason]. */
    public fun endMatch(matchId: MatchId, reason: EndReason): Result<Unit, *> =
        end.execute(matchId, reason)

    /** Publish a synthetic [PlayerKilled] mirroring the stats-harness DSL. */
    public fun publishKill(victim: PlayerId, killer: PlayerId?, weapon: ItemId) {
        eventBus.publish(
            PlayerKilled(
                victim = victim,
                killer = killer,
                source = DamageSource.Item(itemId = weapon, attackerId = killer ?: victim),
                atTick = scheduler.currentTick(),
            ),
        )
    }

    /** Advance the deterministic scheduler by [ticks] ticks. */
    public fun advanceTicks(ticks: Long) {
        scheduler.advanceTicks(ticks)
    }

    /** Typed filter on the sniffer. */
    public inline fun <reified T : DomainEvent> published(): List<T> =
        publishedEvents.filterIsInstance<T>()

    public companion object {
        public fun newDefault(): MatchScenarioHarness {
            val bus = InMemoryDomainEventBus()
            val scheduler = FakeScheduler(startTick = 0L)
            val matchRepo = InMemoryMatchRepository()
            val stages = StageContents()
            val modeRegistry: IMatchModeRegistry = InMemoryMatchModeRegistry()
            val hud = FakeHudGateway()
            val messages = FakeMessageRenderer()
            val playerGateway = FakePlayerGateway()
            val inventoryMutator = InMemoryInventoryMutator()
            val annihilationTracker = AnnihilationMatchTracker(bus)
            val pvpResolver = DefaultPvpDeathPolicyResolver(
                matchRepo = matchRepo,
                modeRegistry = modeRegistry,
            )

            // Wire production listeners — same as AsteraMatchPlugin would.
            val countdown = MatchCountdownListener(matchRepo, hud, scheduler)
            bus.subscribe(MatchStarted::class) { countdown.handle(it) }

            val endSummary = MatchEndSummaryListener(
                matchRepo, hud, messages, playerGateway,
                com.astarworks.astera.match.application.service.result.MatchTallyService(matchRepo), bus, { null },
            )
            bus.subscribe(MatchEnded::class) { endSummary.handle(it) }

            val applyPvp = ApplyPvpDeathPolicyListener(matchRepo, pvpResolver, inventoryMutator)
            bus.subscribe(PlayerKilled::class) { applyPvp.handle(it) }

            // Mirror the AsteraMatchPlugin subscribers that maintain the registry.
            bus.subscribe(MatchStarted::class) { modeRegistry.register(it.matchId, it.modeKey) }
            bus.subscribe(MatchEnded::class) { modeRegistry.unregister(it.matchId) }

            // Event sniffer.
            val published: MutableList<DomainEvent> = mutableListOf()
            bus.subscribe(DomainEvent::class) { published += it }

            return MatchScenarioHarness(
                eventBus = bus,
                scheduler = scheduler,
                matches = matchRepo,
                stages = stages,
                modeRegistry = modeRegistry,
                pvpResolver = pvpResolver,
                hudGateway = hud,
                messages = messages,
                playerGateway = playerGateway,
                inventoryMutator = inventoryMutator,
                annihilationTracker = annihilationTracker,
                publishedEvents = published,
            )
        }
    }
}
