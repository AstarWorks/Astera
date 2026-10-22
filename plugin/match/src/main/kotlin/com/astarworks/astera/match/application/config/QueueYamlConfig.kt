package com.astarworks.astera.match.application.config

import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.domain.matchmaking.LaneKind
import com.astarworks.astera.match.domain.matchmaking.QueueLane
import com.astarworks.astera.match.domain.matchmaking.QueueSettings
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * YAML DTO for `content/matchmaking/queue.yaml` (WP8).
 *
 * ```yaml
 * check_interval_seconds: 1
 * queue_countdown_seconds: 10
 * match_countdown_seconds: 10
 * lanes:
 *   - id: norma            # "norma" or a game-mode id
 *     stage: example-arena
 *     teams: 2
 *     players_per_team: 4
 *     min_players: 2       # pvp lanes are floored at one player per team
 *     kind: pvp            # pvp | pve | training (only pve / training may start solo)
 * ```
 */
@Serializable
public data class QueueYamlConfig(
    @SerialName("check_interval_seconds") public val checkIntervalSeconds: Int = 1,
    @SerialName("queue_countdown_seconds") public val queueCountdownSeconds: Int = 10,
    @SerialName("match_countdown_seconds") public val matchCountdownSeconds: Int = 10,
    public val lanes: List<LaneYamlConfig>,
) {
    @Serializable
    public data class LaneYamlConfig(
        public val id: String,
        public val stage: String,
        public val teams: Int = 2,
        @SerialName("players_per_team") public val playersPerTeam: Int = 4,
        @SerialName("min_players") public val minPlayers: Int = 2,
        /** `pvp` (default) | `pve` | `training`. Only non-PvP lanes may start with a single player. */
        public val kind: String = "pvp",
    )

    public fun toSettings(): QueueSettings {
        val built = lanes.map {
            val kind = LaneKind.parse(it.kind)
            // A PvP lane needs an opponent: never below one player per team, whatever the YAML says.
            val floor = if (kind == LaneKind.PVP) maxOf(it.minPlayers, it.teams) else it.minPlayers
            QueueLane(
                key = it.id,
                stage = StageId(it.stage),
                teams = it.teams,
                playersPerTeam = it.playersPerTeam,
                minPlayers = floor.coerceAtMost(it.teams * it.playersPerTeam),
                kind = kind,
            )
        }
        require(built.map { it.key }.toSet().size == built.size) { "duplicate lane id in queue.yaml" }
        return QueueSettings(
            checkIntervalSeconds = checkIntervalSeconds,
            queueCountdownSeconds = queueCountdownSeconds,
            matchCountdownSeconds = matchCountdownSeconds,
            lanes = built.associateBy { it.key },
        )
    }
}
