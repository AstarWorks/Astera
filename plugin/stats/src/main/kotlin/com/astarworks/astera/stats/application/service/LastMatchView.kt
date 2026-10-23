package com.astarworks.astera.stats.application.service

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.result.LastMatchRecord
import com.astarworks.astera.core.domain.match.result.MatchOutcome

/**
 * The "last match" card as a list of (i18n key, placeholders) lines, shared by
 * `/astera stats last` and the recap shown when a player is back in the lobby.
 */
public object LastMatchView {

    public data class Line(val key: MessageKey, val params: Map<String, String> = emptyMap())

    public fun lines(record: LastMatchRecord, nowEpochSec: Long): List<Line> = buildList {
        val outcome = when (record.outcome) {
            MatchOutcome.WIN -> "win"
            MatchOutcome.LOSE -> "lose"
            MatchOutcome.DRAW -> "draw"
        }
        add(
            Line(
                MessageKey("astera.stats.last.header.$outcome"),
                mapOf("mode" to record.modeKey.ifBlank { "-" }, "ago" to ago(nowEpochSec - record.endedAtEpochSec)),
            ),
        )
        if (record.teams.isNotEmpty()) {
            add(Line(MessageKey("astera.stats.last.score"), mapOf("scores" to record.teams.joinToString(" - ") { "${it.first} ${it.second}" })))
        }
        add(
            Line(
                MessageKey("astera.stats.last.you"),
                mapOf(
                    "kills" to record.kills.toString(),
                    "deaths" to record.deaths.toString(),
                    "assists" to record.assists.toString(),
                    "objective" to record.objective.toString(),
                ),
            ),
        )
        if (record.mvp) add(Line(MessageKey("astera.stats.last.mvp")))
        add(
            Line(
                MessageKey("astera.stats.last.rewards"),
                mapOf("gold" to record.gold.toString(), "xp" to record.xp.toString()),
            ),
        )
        if (record.level > 0) {
            add(
                Line(
                    MessageKey("astera.stats.last.level"),
                    mapOf(
                        "level" to record.level.toString(),
                        "into" to record.xpIntoLevel.toString(),
                        "next" to record.xpForNextLevel.toString(),
                    ),
                ),
            )
        }
    }

    /** `45s`, `12m`, `3h`, `2d`; a clock that went backwards reads `0s`. */
    internal fun ago(seconds: Long): String {
        val s = seconds.coerceAtLeast(0L)
        return when {
            s < 60L -> "${s}s"
            s < 3_600L -> "${s / 60L}m"
            s < 86_400L -> "${s / 3_600L}h"
            else -> "${s / 86_400L}d"
        }
    }
}
