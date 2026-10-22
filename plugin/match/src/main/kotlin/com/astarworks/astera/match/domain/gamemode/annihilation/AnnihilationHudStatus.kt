package com.astarworks.astera.match.domain.gamemode.annihilation

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.nexus.NexusPhase
import com.astarworks.astera.core.domain.nexus.NexusState
import com.astarworks.astera.match.domain.gamemode.HudLine
import com.astarworks.astera.match.domain.gamemode.MatchHudStatus

/**
 * Annihilation's sidebar content (WP14, ADR-0177): the Nexus phase with the
 * time left until the next one, then one line per team showing which of its
 * three pillars still stand and how much HP they hold.
 *
 * ```
 * Engage  4:12
 * Red   ●●○  820 HP
 * Blue  ●●●  1500 HP
 * ```
 */
public object AnnihilationHudStatus {

    public fun of(state: AnnihilationMatchState, match: Match, nowTick: Long): MatchHudStatus {
        val remaining = AnnihilationPhaseRules.ticksUntilNextPhase(state, nowTick)
        val phaseLine = HudLine(
            key = MessageKey("astera.hud.sidebar.nexus.${state.phase.id}"),
            placeholders = if (remaining != null) mapOf("time" to clock(remaining)) else emptyMap(),
        )
        val lines = match.teams.map { team ->
            if (team.id in state.eliminatedTeams) {
                HudLine(MessageKey("astera.hud.sidebar.annihilation.eliminated"), team = team.id)
            } else {
                val pillars = state.pillarsOf(team.id)
                val alive = pillars.filter { it.state != NexusState.DESTROYED }
                HudLine(
                    key = MessageKey("astera.hud.sidebar.annihilation.team"),
                    placeholders = mapOf(
                        "tiers" to pillars.joinToString("") { if (it.state == NexusState.DESTROYED) "○" else "●" },
                        "alive" to alive.size.toString(),
                        "total" to pillars.size.toString(),
                        "hp" to alive.sumOf { it.hp }.toString(),
                    ),
                    team = team.id,
                )
            }
        }
        return MatchHudStatus(phaseLine = phaseLine, lines = lines)
    }

    /** `m:ss` for a tick count (20 ticks per second), rounded up so the display never reads 0:00 early. */
    public fun clock(ticks: Long): String {
        val seconds = (ticks.coerceAtLeast(0) + 19) / 20
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }
}
