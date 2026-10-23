package com.astarworks.astera.match.domain.matchmaking

import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.domain.mode.GameModeId

/** What a queue lane is for: only [PVP] lanes insist on opponents. */
public enum class LaneKind {
    /** Teams fight each other: every team needs at least one player, so nobody starts a PvP match alone. */
    PVP,

    /**
     * Cooperative / against the environment: a solo start is fine, and everybody queued plays on the same
     * side (the lane holds `players_per_team` players, ADR-0249).
     */
    PVE,

    /** Practice lane: a solo start is fine. */
    TRAINING;

    public companion object {
        public fun parse(wire: String): LaneKind =
            entries.firstOrNull { it.name.equals(wire.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("unknown lane kind '$wire' (pvp | pve | training)")
    }
}

/**
 * One queue lane of `content/matchmaking/queue.yaml` (WP8): which stage a
 * lane plays on and how big the match is.
 *
 * [key] is `norma` (the legacy 2-team lane, `modeKey = null`) or a game-mode
 * id (`modeKey = key`, joined as `custom:<key>`).
 */
public data class QueueLane(
    val key: String,
    val stage: StageId,
    val teams: Int,
    val playersPerTeam: Int,
    val minPlayers: Int,
    val kind: LaneKind = LaneKind.PVP,
) {
    init {
        if (key != NORMA_KEY) GameModeId(key) // validates lower-kebab
        // Re-uses MatchSizeSpec's invariants (teams >= 2, 1 <= minPlayers <= cap).
        sizeSpec()
    }

    public val mode: MatchmakingMode
        get() = if (key == NORMA_KEY) MatchmakingMode.Norma else MatchmakingMode.Custom(GameModeId(key))

    /** `MatchStarted.modeKey` for matches formed from this lane (`null` = legacy Norma). */
    public val modeKey: String? get() = key.takeIf { it != NORMA_KEY }

    /** Roster cap: a full queue starts immediately. */
    public val capacity: Int get() = if (kind == LaneKind.PVE) playersPerTeam else teams * playersPerTeam

    /**
     * PvP lanes never form a match with an empty team ([MatchSizeSpec.requireAllTeams]); PvE / training lanes
     * may start with a single player.
     */
    public fun sizeSpec(): MatchSizeSpec =
        MatchSizeSpec(
            teams,
            playersPerTeam,
            minPlayers,
            requireAllTeams = kind == LaneKind.PVP,
            cooperative = kind == LaneKind.PVE,
        )

    public companion object {
        public const val NORMA_KEY: String = "norma"
    }
}

/**
 * Queue to match tuning (WP8), loaded from `content/matchmaking/queue.yaml`.
 *
 * @property checkIntervalSeconds how often the scheduler looks at the queues.
 * @property queueCountdownSeconds wait between "enough players" and forming the
 *   match; restarts when a queued player leaves.
 * @property matchCountdownSeconds length of the in-arena COUNTDOWN phase
 *   before the match turns ACTIVE.
 */
public data class QueueSettings(
    val checkIntervalSeconds: Int,
    val queueCountdownSeconds: Int,
    val matchCountdownSeconds: Int,
    val lanes: Map<String, QueueLane>,
) {
    init {
        require(checkIntervalSeconds >= 1) { "check_interval_seconds must be >= 1" }
        require(queueCountdownSeconds >= 0) { "queue_countdown_seconds must be >= 0" }
        require(matchCountdownSeconds >= 0) { "match_countdown_seconds must be >= 0" }
    }

    public fun laneFor(mode: MatchmakingMode): QueueLane? = when (mode) {
        MatchmakingMode.Norma -> lanes[QueueLane.NORMA_KEY]
        is MatchmakingMode.Custom -> lanes[mode.adminConfigured.value]
        is MatchmakingMode.Tournament -> null
    }

    /** Roster dimensions for [mode]; modes without a lane fall back to [MatchSizeSpec.DEFAULT]. */
    public fun sizeFor(mode: MatchmakingMode): MatchSizeSpec = laneFor(mode)?.sizeSpec() ?: MatchSizeSpec.DEFAULT

    /** Configured COUNTDOWN to ACTIVE floor for [modeKey]; `null` = no lane, use the mode policy. */
    public fun minPlayersForModeKey(modeKey: String?): Int? =
        lanes[modeKey ?: QueueLane.NORMA_KEY]?.minPlayers

    public companion object {
        /** Used when `content/matchmaking/queue.yaml` is absent. */
        public val DEFAULT: QueueSettings = QueueSettings(
            checkIntervalSeconds = 1,
            queueCountdownSeconds = 10,
            matchCountdownSeconds = 10,
            lanes = mapOf(
                QueueLane.NORMA_KEY to QueueLane(
                    key = QueueLane.NORMA_KEY,
                    stage = StageId("example-arena"),
                    teams = 2,
                    playersPerTeam = 4,
                    minPlayers = 2,
                    kind = LaneKind.PVP,
                ),
            ),
        )
    }
}
