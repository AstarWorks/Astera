package com.astarworks.astera.stats.scenario

import com.astarworks.astera.combat.application.contents.ArmorContents
import com.astarworks.astera.combat.application.contents.SkillContents
import com.astarworks.astera.combat.application.contents.StatusContents
import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.application.contents.ItemContents
import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.service.InMemoryCooldownTracker
import com.astarworks.astera.core.application.service.InMemoryPermissionService
import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerProfile
import com.astarworks.astera.core.domain.stage.Region
import com.astarworks.astera.core.domain.stage.Stage
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.contents.StageContents
import com.astarworks.astera.match.application.service.InMemoryMatchRepository
import com.astarworks.astera.match.application.usecase.match.EndMatchUseCase
import com.astarworks.astera.match.application.usecase.match.StartMatchUseCase
import com.astarworks.astera.match.application.usecase.match.SwitchPhaseUseCase
import com.astarworks.astera.combat.application.port.outbound.IKnockbackGateway
import com.astarworks.astera.combat.application.port.outbound.INearbyTargetQuery
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.stats.application.eventbus.listeners.MissionProgressOnKillListener
import com.astarworks.astera.stats.application.eventbus.listeners.RewardOnKillListener
import com.astarworks.astera.stats.application.service.InMemoryLevelService
import com.astarworks.astera.stats.application.service.InMemoryNormaProgressRepository
import com.astarworks.astera.stats.application.service.InMemoryNormaService
import com.astarworks.astera.stats.application.service.InMemoryPlayerStatsRepository
import com.astarworks.astera.stats.application.usecase.level.GrantXpUseCase
import com.astarworks.astera.stats.application.usecase.stats.RecordKillUseCase
import com.astarworks.astera.stats.scenario.fake.SeededDamageRandomizer
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.testfixtures.FakeBroadcaster
import com.astarworks.astera.testfixtures.FakeGuiAdapter
import com.astarworks.astera.testfixtures.FakeMatchTeamLookup
import com.astarworks.astera.testfixtures.FakeMcPlayer
import com.astarworks.astera.testfixtures.FakeMcServer
import com.astarworks.astera.testfixtures.FakeMessageRenderer
import com.astarworks.astera.testfixtures.FakePlayerGateway
import com.astarworks.astera.testfixtures.FakePlayerRepository
import com.astarworks.astera.testfixtures.FakeScheduler
import com.astarworks.astera.testfixtures.FakeScoreboardManager
import com.astarworks.astera.testfixtures.FakeTabList
import com.astarworks.astera.testfixtures.FakeWorldGateway
import java.util.UUID

/**
 * Pre-wired in-memory end-to-end harness for scenario tests (Wave _infra-1 /
 * ADR-0059; Wave _items-1 / ADR-0073 dropped weapons-plugin wiring).
 *
 * Bundles every fake + InMemory production repository + use case needed for a
 * kill → reward flow, mirroring what
 * [com.astarworks.astera.stats.smoke.KillRewardChainSmokeTest] and
 * [com.astarworks.astera.match.smoke.MatchLifecycleSmokeTest] assemble by
 * hand. Scenarios reach in via [ScenarioDsl] rather than touching fields
 * directly.
 *
 * Listener subscriptions wired at construction:
 *  - [RewardOnKillListener] on [PlayerKilled] → records kill + grants XP
 *  - [MissionProgressOnKillListener] on [PlayerKilled] → ticks `Kills` normas
 *
 * Damage application is intentionally left under-wired. The full
 * [com.astarworks.astera.combat.application.usecase.damage.ApplyDamageUseCase]
 * needs `IDamageGateway` / `IArmorEquipmentProvider` / `IStatusEffectContainer`
 * (none of which have a canonical in-memory fake yet); scenarios that need a
 * kill publish [PlayerKilled] directly via the DSL's `kills()` helper — the
 * same idiom the existing smoke tests use. See `// TODO(infra-2)` markers
 * for the gaps.
 */
public class AsteraTestHarness private constructor(
    /** Production [IDomainEventBus] (in-memory). Listeners subscribe here. */
    public val eventBus: IDomainEventBus,
    public val scheduler: FakeScheduler,
    public val server: FakeMcServer,
    public val broadcaster: FakeBroadcaster,
    public val messageRenderer: FakeMessageRenderer,
    public val worldGateway: FakeWorldGateway,
    public val playerGateway: FakePlayerGateway,
    public val tabList: FakeTabList,
    public val scoreboards: FakeScoreboardManager,
    public val playerRepo: FakePlayerRepository,
    public val matchTeamLookup: FakeMatchTeamLookup,
    public val guiAdapter: FakeGuiAdapter,
    public val itemRegistry: ItemContents,
    public val skillRegistry: SkillContents,
    public val armorRegistry: ArmorContents,
    public val statusRegistry: StatusContents,
    public val stageRepo: StageContents,
    public val matchRepo: InMemoryMatchRepository,
    public val playerStatsRepo: InMemoryPlayerStatsRepository,
    public val normaProgressRepo: InMemoryNormaProgressRepository,
    public val levelService: InMemoryLevelService,
    /** Wave _infra-2b: norma definitions live here; the service reads through to this. */
    public val normaRegistry: com.astarworks.astera.stats.application.contents.NormaContents,
    public val normaService: InMemoryNormaService,
    public val permissionService: InMemoryPermissionService,
    public val cooldownTracker: InMemoryCooldownTracker,
    public val knockbackGateway: NoOpKnockbackGateway,
    public val nearbyTargetQuery: EmptyNearbyTargetQuery,
    public val damageRandomizer: SeededDamageRandomizer,
    public val recordKill: RecordKillUseCase,
    public val grantXp: GrantXpUseCase,
    /**
     * Sniffer attached to [eventBus] at construction. Records every
     * [DomainEvent] published, regardless of subscriber. Tests assert on
     * event history via `publishedEvents.filterIsInstance<T>()`.
     */
    public val publishedEvents: MutableList<DomainEvent>,
) {

    /**
     * Spawn a fake player with [name] and (optionally) place them on [team].
     * Registers a [PlayerProfile] in [playerRepo] and a [FakeMcPlayer] in
     * [server] so `services.findPlayer(id)` round-trips. Returns the
     * [IMcPlayer] handle scenarios use as the DSL receiver.
     */
    public fun spawnPlayer(name: String, team: TeamId? = null): IMcPlayer {
        val id = PlayerId(UUID.randomUUID())
        val player = FakeMcPlayer(id = id, name = name)
        server.register(player)
        playerGateway.register(name, id)
        playerRepo.save(
            PlayerProfile(
                id = id,
                displayName = name,
                locale = "en",
                joinedAtEpochMs = System.currentTimeMillis(),
            ),
        )
        if (team != null) {
            matchTeamLookup.place(id, team)
        }
        return player
    }

    /** Advance the deterministic scheduler by [n] ticks. */
    public fun advanceTicks(n: Int) {
        scheduler.advanceTicks(n.toLong())
    }

    /**
     * Create a stage in [stageRepo], start a match via [StartMatchUseCase],
     * and place each player onto the implied red/blue teams (round-robin so
     * `players[0,2,4...] = red`, `players[1,3,5...] = blue`).
     *
     * Returns the [MatchId] in the now-COUNTDOWN match. Phase advancement
     * (COUNTDOWN→ACTIVE) is left to the scenario; most kill-flow scenarios
     * only need the match to exist + team affiliation set, not a live phase.
     *
     * TODO(infra-2): wire `EntryGameUseCase` so the players are formally
     * enrolled in the match teams (right now we just set [matchTeamLookup]).
     */
    public fun startMatch(stageId: StageId, players: List<IMcPlayer>): MatchId {
        if (stageRepo.find(stageId) == null) {
            stageRepo.register(
                Stage(
                    id = stageId,
                    displayNameKey = MessageKey("astera.stage.${stageId.value}"),
                    regions = emptyList<Region>(),
                ),
            )
        }

        val start = StartMatchUseCase(stageRepo, matchRepo, eventBus, scheduler)
        val result = start.execute(stageId)
        val matchId = when (result) {
            is Result.Success -> result.value
            is Result.Failure -> error("startMatch failed: ${result.error}")
        }

        val red = TeamId("red")
        val blue = TeamId("blue")
        players.forEachIndexed { idx, p ->
            matchTeamLookup.place(p.id, if (idx % 2 == 0) red else blue)
        }
        return matchId
    }

    /**
     * Helper for scenarios that want to drive a match all the way to ENDED.
     * Runs the same COUNTDOWN→ACTIVE→ENDED path as the lifecycle smoke test.
     */
    public fun endMatch(matchId: MatchId): Result<Unit, *> {
        val switch = SwitchPhaseUseCase(matchRepo, eventBus, scheduler)
        // COUNTDOWN → ACTIVE (best-effort; ignore failures so scenarios can
        // also call endMatch on an already-active match).
        switch.execute(matchId, com.astarworks.astera.match.domain.match.MatchStateMachine.TransitionTrigger.CountdownComplete)
        val end = EndMatchUseCase(matchRepo, eventBus, scheduler)
        return end.execute(matchId, com.astarworks.astera.core.domain.event.EndReason.AdminEnded)
    }

    /** Convenience: was a [MatchStarted] event published? */
    public fun matchPhaseOf(matchId: MatchId): MatchPhase? =
        matchRepo.findById(matchId)?.phase

    public companion object {

        /** Empty registries, no players. Most scenarios use this. */
        public fun newDefault(): AsteraTestHarness = build()

        private fun build(): AsteraTestHarness {
            val eventBus = InMemoryDomainEventBus()
            val scheduler = FakeScheduler(startTick = 0L)
            val server = FakeMcServer()
            val broadcaster = FakeBroadcaster()
            val messages = FakeMessageRenderer()
            val world = FakeWorldGateway()
            val players = FakePlayerGateway()
            val tab = FakeTabList()
            val scoreboards = FakeScoreboardManager()
            val playerRepo = FakePlayerRepository()
            val teams = FakeMatchTeamLookup()
            val gui = FakeGuiAdapter()
            val items = ItemContents()
            val skillReg = SkillContents()
            val armorReg = ArmorContents()
            val statusReg = StatusContents()
            val stages = StageContents()
            val matches = InMemoryMatchRepository()
            val statsRepo = InMemoryPlayerStatsRepository()
            val normaProgress = InMemoryNormaProgressRepository()
            val levelService = InMemoryLevelService()
            val normaReg = com.astarworks.astera.stats.application.contents.NormaContents()
            val normaService = InMemoryNormaService(normaProgress, normaReg)
            val permissions = InMemoryPermissionService()
            val cooldowns = InMemoryCooldownTracker(scheduler)
            val knockback = NoOpKnockbackGateway()
            val nearby = EmptyNearbyTargetQuery()
            val rng = SeededDamageRandomizer(seed = 0L)

            val recordKill = RecordKillUseCase(statsRepo, eventBus)
            val grantXp = GrantXpUseCase(levelService, eventBus)
            val rewardOnKill = RewardOnKillListener(recordKill, grantXp)
            val missionProgressOnKill = MissionProgressOnKillListener(normaService, eventBus)

            eventBus.subscribe(PlayerKilled::class) { rewardOnKill.handle(it) }
            eventBus.subscribe(PlayerKilled::class) { missionProgressOnKill.handle(it) }

            // Event-history sniffer. Subscribe to DomainEvent base so every
            // publish is captured; production InMemoryDomainEventBus does not
            // retain history, so the sniffer is the only way scenarios get a
            // queryable event log.
            val publishedEvents: MutableList<DomainEvent> = mutableListOf()
            eventBus.subscribe(DomainEvent::class) { publishedEvents += it }

            return AsteraTestHarness(
                eventBus = eventBus,
                scheduler = scheduler,
                server = server,
                broadcaster = broadcaster,
                messageRenderer = messages,
                worldGateway = world,
                playerGateway = players,
                tabList = tab,
                scoreboards = scoreboards,
                playerRepo = playerRepo,
                matchTeamLookup = teams,
                guiAdapter = gui,
                itemRegistry = items,
                skillRegistry = skillReg,
                armorRegistry = armorReg,
                statusRegistry = statusReg,
                stageRepo = stages,
                matchRepo = matches,
                playerStatsRepo = statsRepo,
                normaProgressRepo = normaProgress,
                levelService = levelService,
                normaRegistry = normaReg,
                normaService = normaService,
                permissionService = permissions,
                cooldownTracker = cooldowns,
                knockbackGateway = knockback,
                nearbyTargetQuery = nearby,
                damageRandomizer = rng,
                recordKill = recordKill,
                grantXp = grantXp,
                publishedEvents = publishedEvents,
            )
        }
    }
}

/**
 * Trivial item-template stand-in retained for legacy scenario imports.
 *
 * Wave _items-1 (ADR-0073): `IItemRegistry` / [com.astarworks.astera.core.application.contents.ItemContents]
 * are no longer generic — they return [com.astarworks.astera.core.domain.item.ItemSpec]
 * straight out. Scenarios that previously held a `TestItemTemplate` to satisfy
 * the registry's generic should switch to `ItemSpec` builders (see
 * `plugin/items/src/test/.../testfixtures/ItemSpecs.kt` for the canonical
 * builder). This class remains as a marker only.
 */
public data class TestItemTemplate(val id: ItemId)

/** No-op [IKnockbackGateway]; matches the Wave 5a default binding. */
public class NoOpKnockbackGateway : IKnockbackGateway {
    public val applied: MutableList<Pair<PlayerId, Vec3>> = mutableListOf()
    override fun applyImpulse(target: PlayerId, impulse: Vec3) {
        applied += target to impulse
    }
}

/** Empty [INearbyTargetQuery]; matches the Wave 5a default binding. */
public class EmptyNearbyTargetQuery : INearbyTargetQuery {
    override fun findInRadius(center: Vec3, radius: Double, world: WorldId?): List<PlayerId> = emptyList()
}
