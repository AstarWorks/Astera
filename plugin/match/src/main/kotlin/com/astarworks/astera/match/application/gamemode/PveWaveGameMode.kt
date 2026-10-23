package com.astarworks.astera.match.application.gamemode

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.mob.IEncounterZoneRegistry
import com.astarworks.astera.core.application.port.outbound.mob.IMobSpawner
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.MobKilled
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.mob.encounter.EncounterZone
import com.astarworks.astera.core.domain.mob.encounter.EncounterZoneId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.Stage
import com.astarworks.astera.match.application.gamemode.siege.SiegeEnv
import com.astarworks.astera.match.application.gamemode.siege.SiegeGameMode
import com.astarworks.astera.match.application.gamemode.siege.SiegeRun
import com.astarworks.astera.match.domain.gamemode.HudLine
import com.astarworks.astera.match.domain.mode.GameModeId
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * What [PveWaveGameMode] needs on top of [SiegeEnv] (ADR-0249): the mob spawner and the encounter registry
 * of Core. A mode built without it (the reflective `GameModeContents` instance) is inert.
 */
public class PveEnv(
    public val spawner: IMobSpawner,
    public val encounters: IEncounterZoneRegistry,
)

/**
 * PvE Waves (ADR-0249): a solo / co-op run against the scripted waves of an encounter
 * (`content/pve/encounters/<id>.yaml` -> `waves`).
 *
 *  - Once the match is ACTIVE the first wave appears after `first_wave_delay_seconds`; every following one
 *    `intermission_seconds` after the previous wave was cleared. Mobs are spawned through `IMobSpawner`, so they
 *    are real entities with Astera HP / damage / names and hunt the players.
 *  - A kill is counted from `MobKilled` of a mob this run spawned (credit goes to the killing participant). A mob
 *    that vanishes without a kill (fell out of the world, removed by the host) is dropped from the wave, so a
 *    wave can never block forever.
 *  - Clearing the last wave ends the match with [EndReason.EncounterCleared] (VICTORY for the party). The party
 *    shares `lives` deaths; the last one - or `time_limit_seconds` - ends it with [EndReason.EncounterFailed]
 *    (DEFEAT). If a wave cannot spawn a single mob the match is cancelled instead of being "won".
 *  - Sidebar: `Wave 2/3 - 5 enemies left`, lives, defeated count. Rewards: `match-rewards.yaml` `modes.pve-waves`.
 *
 * It borrows the siege skeleton (per-match run state, kill / tick hooks, HUD lines) but needs no objective
 * regions; the stage only names `mob-spawn-*` regions where waves appear.
 */
public class PveWaveGameMode @JvmOverloads constructor(
    env: SiegeEnv? = null,
    private val pve: PveEnv? = null,
) : SiegeGameMode<PveWaveGameMode.Run>(ID, TITLE_KEY, env) {

    private val log = LoggerFactory.getLogger(javaClass)

    public class Run(
        matchId: MatchId,
        stage: Stage,
        public val encounter: EncounterZone,
        public val spawnPoints: List<Vec3>,
        public val maxLives: Int,
    ) : SiegeRun(matchId, stage) {
        public var livesLeft: Int = maxLives
        public var waveIndex: Int = 0
        public var waveSpawned: Boolean = false
        public var activeSinceTick: Long = -1L
        public var nextWaveTick: Long = 0L
        public var kills: Int = 0
        public var spawnCursor: Int = 0
        public val alive: MutableMap<UUID, EntityHandle> = LinkedHashMap()
    }

    /** Bus subscriptions of this mode: the siege ones plus the mob kills. */
    public fun subscribeAll(): List<IDomainEventBus.Subscription> {
        val e = env ?: return emptyList()
        val matches = e.matches
        return subscribe() + e.eventBus.subscribe(MobKilled::class) { ev ->
            val killer = ev.killerId ?: return@subscribe
            val match = matches.findMatchOf(killer) ?: return@subscribe
            dispatch(match.id) { run, _ -> mobKilled(run, ev.handle.id) }
        }
    }

    override fun createRun(match: Match, stage: Stage): Run? {
        val e = env ?: return null
        val p = pve ?: return null
        val encounterId = e.string("encounter", DEFAULT_ENCOUNTER)
        val encounter = p.encounters.get(EncounterZoneId(encounterId))
        if (encounter == null || encounter.waves.isEmpty()) {
            log.warn("{}: encounter '{}' is unknown or has no waves; match {} runs without an objective.", ID, encounterId, match.id)
            return null
        }
        val spawns = stage.regionsWithPrefix(SPAWN_PREFIX).map { r ->
            Vec3((r.min.x + r.max.x) / 2, r.min.y, (r.min.z + r.max.z) / 2)
        }
        if (spawns.isEmpty()) {
            log.warn("{}: stage '{}' has no '{}*' regions; match {} runs without an objective.", ID, stage.id, SPAWN_PREFIX, match.id)
            return null
        }
        return Run(match.id, stage, encounter, spawns, e.int("lives", DEFAULT_LIVES).coerceAtLeast(1))
    }

    override fun tickRun(run: Run, match: Match, tick: Long) {
        val e = env ?: return
        if (run.ended) return
        if (run.activeSinceTick < 0) {
            run.activeSinceTick = tick
            run.nextWaveTick = tick + e.int("first_wave_delay_seconds", DEFAULT_FIRST_DELAY) * TICKS_PER_SECOND
        }
        val limit = e.int("time_limit_seconds", DEFAULT_TIME_LIMIT)
        if (limit > 0 && tick - run.activeSinceTick > limit * TICKS_PER_SECOND) {
            fail(run, match, timeUp = true)
            return
        }
        dropVanished(run)
        if (!run.waveSpawned) {
            if (tick >= run.nextWaveTick) spawnWave(run, match)
        } else if (run.alive.isEmpty()) {
            waveCleared(run, match, tick)
        }
    }

    private fun spawnWave(run: Run, match: Match) {
        val p = pve ?: return
        val wave = run.encounter.waves[run.waveIndex]
        var spawned = 0
        for (entry in wave.mobs) {
            repeat(entry.count) {
                val at = nextSpawnPoint(run, spawned)
                when (val result = p.spawner.spawn(run.stage.world, at, entry.mobTypeId)) {
                    is Result.Success -> {
                        run.alive[result.value.id] = result.value
                        spawned++
                    }
                    is Result.Failure -> log.warn("{}: could not spawn {} for match {}: {}", ID, entry.mobTypeId, run.matchId, result.error)
                }
            }
        }
        if (spawned == 0) {
            log.error("{}: wave {} of match {} spawned nothing; cancelling the match instead of awarding a win.", ID, run.waveIndex + 1, run.matchId)
            endWith(run, EndReason.Cancelled("pve-spawn-failed"))
            return
        }
        run.waveSpawned = true
        tellMatch(match, KEY_WAVE_START, mapOf("wave" to (run.waveIndex + 1).toString(), "waves" to run.encounter.waves.size.toString(), "count" to spawned.toString()))
    }

    private fun nextSpawnPoint(run: Run, n: Int): Vec3 {
        val base = run.spawnPoints[run.spawnCursor % run.spawnPoints.size]
        run.spawnCursor++
        // Spread mobs sharing a spot so they do not stack into one body.
        val ring = n / run.spawnPoints.size
        return if (ring == 0) base else Vec3(base.x + (ring % 3 - 1) * 1.5, base.y, base.z + (ring / 3 + 1) * 1.5)
    }

    private fun waveCleared(run: Run, match: Match, tick: Long) {
        val e = env ?: return
        val waves = run.encounter.waves.size
        val wave = run.waveIndex + 1
        if (wave >= waves) {
            tellMatch(match, KEY_CLEARED, mapOf("waves" to waves.toString()))
            endWith(run, EndReason.EncounterCleared(partyTeam(match), waves))
            return
        }
        tellMatch(match, KEY_WAVE_CLEARED, mapOf("wave" to wave.toString(), "waves" to waves.toString()))
        run.waveIndex++
        run.waveSpawned = false
        run.nextWaveTick = tick + e.int("intermission_seconds", DEFAULT_INTERMISSION) * TICKS_PER_SECOND
    }

    private fun mobKilled(run: Run, entityId: UUID) {
        val handle = run.alive.remove(entityId) ?: return
        run.kills++
        pve?.spawner?.despawn(handle) // body is dead; this only forgets the adapter's bookkeeping
    }

    private fun dropVanished(run: Run) {
        val p = pve ?: return
        val gone = run.alive.filterValues { !p.spawner.isAlive(it) }
        for ((id, handle) in gone) {
            run.alive.remove(id)
            p.spawner.despawn(handle)
        }
    }

    override fun killed(run: Run, match: Match, victim: PlayerId, victimTeam: TeamId?, killer: PlayerId?, killerTeam: TeamId?) {
        if (run.ended || victimTeam == null) return
        run.livesLeft = (run.livesLeft - 1).coerceAtLeast(0)
        tellMatch(match, KEY_LIFE_LOST, mapOf("lives" to run.livesLeft.toString()))
        if (run.livesLeft <= 0) fail(run, match, timeUp = false)
    }

    private fun fail(run: Run, match: Match, timeUp: Boolean) {
        endWith(run, EndReason.EncounterFailed(opponentTeam(match), run.waveIndex + 1, run.encounter.waves.size, timeUp))
    }

    /** The side the players fight on: the first team that has members. */
    private fun partyTeam(match: Match): TeamId =
        (match.teams.firstOrNull { it.members.isNotEmpty() } ?: match.teams.first()).id

    /** The empty side the party "loses to" in the result screen. */
    private fun opponentTeam(match: Match): TeamId {
        val party = partyTeam(match)
        return (match.teams.firstOrNull { it.id != party } ?: match.teams.last()).id
    }

    override fun hudLines(run: Run, match: Match): List<HudLine> {
        val waves = run.encounter.waves.size
        val now = env?.now?.invoke() ?: 0L
        val goal = when {
            run.activeSinceTick < 0 -> HudLine(KEY_HUD_READY, mapOf("waves" to waves.toString()))
            run.waveSpawned -> HudLine(
                KEY_HUD_WAVE,
                mapOf("wave" to (run.waveIndex + 1).toString(), "waves" to waves.toString(), "left" to run.alive.size.toString()),
            )
            else -> HudLine(
                KEY_HUD_NEXT,
                mapOf(
                    "wave" to (run.waveIndex + 1).toString(),
                    "waves" to waves.toString(),
                    "seconds" to ((run.nextWaveTick - now + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND).coerceAtLeast(0).toString(),
                ),
            )
        }
        return listOf(
            goal,
            HudLine(KEY_HUD_LIVES, mapOf("lives" to run.livesLeft.toString(), "max" to run.maxLives.toString())),
            HudLine(KEY_HUD_KILLS, mapOf("kills" to run.kills.toString())),
        )
    }

    override fun finish(run: Run) {
        val p = pve ?: return
        for (handle in run.alive.values) p.spawner.despawn(handle)
        run.alive.clear()
    }

    public companion object {
        public val ID: GameModeId = GameModeId("pve-waves")
        public val TITLE_KEY: MessageKey = MessageKey("astera.gamemode.pve-waves.title")
        private val KEY_WAVE_START: MessageKey = MessageKey("astera.gamemode.pve-waves.wave-start")
        private val KEY_WAVE_CLEARED: MessageKey = MessageKey("astera.gamemode.pve-waves.wave-cleared")
        private val KEY_CLEARED: MessageKey = MessageKey("astera.gamemode.pve-waves.cleared")
        private val KEY_LIFE_LOST: MessageKey = MessageKey("astera.gamemode.pve-waves.life-lost")
        private val KEY_HUD_READY: MessageKey = MessageKey("astera.hud.sidebar.pve-waves.ready")
        private val KEY_HUD_WAVE: MessageKey = MessageKey("astera.hud.sidebar.pve-waves.wave")
        private val KEY_HUD_NEXT: MessageKey = MessageKey("astera.hud.sidebar.pve-waves.next")
        private val KEY_HUD_LIVES: MessageKey = MessageKey("astera.hud.sidebar.pve-waves.lives")
        private val KEY_HUD_KILLS: MessageKey = MessageKey("astera.hud.sidebar.pve-waves.kills")
        private const val SPAWN_PREFIX: String = "mob-spawn-"
        private const val DEFAULT_ENCOUNTER: String = "astera:proving-grounds"
        private const val DEFAULT_LIVES: Int = 2
        private const val DEFAULT_FIRST_DELAY: Int = 3
        private const val DEFAULT_INTERMISSION: Int = 5
        private const val DEFAULT_TIME_LIMIT: Int = 600
        private const val TICKS_PER_SECOND: Long = 20L
    }
}
