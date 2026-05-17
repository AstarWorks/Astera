package com.astarworks.astera.persistence.jsonb

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Score
import com.astarworks.astera.core.domain.match.Team
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Wraps `List<Team>` for the matches.teams JSONB column. Domain types
 * (`Team`, `TeamId`, `MessageKey`, `PlayerId`) stay free of
 * `@Serializable`; this DTO mirrors their shape and converts.
 */
@Serializable
public data class MatchTeamsJson(val teams: List<TeamJson>) {

    public fun toDomain(): List<Team> = teams.map { it.toDomain() }

    public companion object {
        public fun fromDomain(teams: List<Team>): MatchTeamsJson =
            MatchTeamsJson(teams.map { TeamJson.fromDomain(it) })
    }
}

@Serializable
public data class TeamJson(
    val id: String,
    val displayNameKey: String,
    val colorKey: String,
    val members: List<String>,
) {
    public fun toDomain(): Team = Team(
        id = TeamId(id),
        displayNameKey = MessageKey(displayNameKey),
        colorKey = MessageKey(colorKey),
        members = members.map { PlayerId(UUID.fromString(it)) }.toSet(),
    )

    public companion object {
        public fun fromDomain(team: Team): TeamJson = TeamJson(
            id = team.id.value,
            displayNameKey = team.displayNameKey.value,
            colorKey = team.colorKey.value,
            members = team.members.map { it.uuid.toString() },
        )
    }
}

/**
 * Wraps `Score` (`Map<TeamId, Int>`) for JSONB. Keys are serialized as
 * the `TeamId.value` string.
 */
@Serializable
public data class MatchScoreJson(val points: Map<String, Int>) {

    public fun toDomain(): Score = Score(perTeam = points.mapKeys { (k, _) -> TeamId(k) })

    public companion object {
        public fun fromDomain(score: Score): MatchScoreJson = MatchScoreJson(
            points = score.perTeam.mapKeys { (tid, _) -> tid.value },
        )
    }
}
