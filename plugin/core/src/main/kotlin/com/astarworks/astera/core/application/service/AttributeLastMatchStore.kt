package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.ILastMatchStore
import com.astarworks.astera.core.application.port.outbound.IPlayerAttributeContainerStore
import com.astarworks.astera.core.domain.attribute.AttributeKey
import com.astarworks.astera.core.domain.attribute.AttributeValue
import com.astarworks.astera.core.domain.attribute.IAttributeContainer
import com.astarworks.astera.core.domain.match.result.LastMatchRecord
import com.astarworks.astera.core.domain.match.result.MatchOutcome
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * [ILastMatchStore] over the player-attribute container (`lastmatch.*` keys),
 * so it travels with the wallet through the shared Postgres row and needs no
 * table of its own. [store] is resolved per call: the Postgres overlay registers after Core.
 * table of its own.
 */
public class AttributeLastMatchStore(
    private val store: () -> IPlayerAttributeContainerStore,
) : ILastMatchStore {

    override fun save(playerId: PlayerId, record: LastMatchRecord) {
        val c = store().containerFor(playerId)
        c.setRef(MATCH_ID, record.matchId)
        c.setNum(ENDED_AT, record.endedAtEpochSec)
        c.setRef(MODE, record.modeKey.ifBlank { "-" })
        c.setRef(OUTCOME, record.outcome.name)
        c.setRef(TEAMS, record.teams.joinToString(",") { "${it.first}:${it.second}" }.ifBlank { "-" })
        c.setNum(KILLS, record.kills)
        c.setNum(DEATHS, record.deaths)
        c.setNum(ASSISTS, record.assists)
        c.setNum(OBJECTIVE, record.objective)
        c.setNum(MVP, if (record.mvp) 1 else 0)
        c.setNum(GOLD, record.gold)
        c.setNum(XP, record.xp)
        c.setNum(LEVEL, record.level)
        c.setNum(XP_INTO, record.xpIntoLevel)
        c.setNum(XP_NEXT, record.xpForNextLevel)
        // Last write: the lobby treats a record without it as not yet complete.
        c.setNum(RECAP_SHOWN, 0)
    }

    override fun find(playerId: PlayerId): LastMatchRecord? {
        val c = store().containerFor(playerId)
        val matchId = (c.get(MATCH_ID) as? AttributeValue.RefValue)?.id ?: return null
        if (c.get(RECAP_SHOWN) == null) return null
        val outcome = runCatching {
            MatchOutcome.valueOf((c.get(OUTCOME) as? AttributeValue.RefValue)?.id.orEmpty())
        }.getOrNull() ?: return null
        val teamsRaw = (c.get(TEAMS) as? AttributeValue.RefValue)?.id.orEmpty()
        val teams = teamsRaw.split(',').mapNotNull { part ->
            val name = part.substringBeforeLast(':', "")
            val score = part.substringAfterLast(':', "").toIntOrNull()
            if (name.isEmpty() || score == null) null else name to score
        }
        return LastMatchRecord(
            matchId = matchId,
            endedAtEpochSec = c.long(ENDED_AT),
            modeKey = (c.get(MODE) as? AttributeValue.RefValue)?.id?.takeIf { it != "-" }.orEmpty(),
            outcome = outcome,
            teams = teams,
            kills = c.long(KILLS).toInt(),
            deaths = c.long(DEATHS).toInt(),
            assists = c.long(ASSISTS).toInt(),
            objective = c.long(OBJECTIVE).toInt(),
            mvp = c.long(MVP) == 1L,
            gold = c.long(GOLD),
            xp = c.long(XP),
            level = c.long(LEVEL).toInt(),
            xpIntoLevel = c.long(XP_INTO),
            xpForNextLevel = c.long(XP_NEXT),
        )
    }

    override fun recapShown(playerId: PlayerId): Boolean =
        store().containerFor(playerId).long(RECAP_SHOWN) == 1L

    override fun markRecapShown(playerId: PlayerId) {
        store().containerFor(playerId).setNum(RECAP_SHOWN, 1)
    }

    private fun IAttributeContainer.setRef(key: AttributeKey, id: String) = set(key, AttributeValue.RefValue(id))

    private fun IAttributeContainer.setNum(key: AttributeKey, value: Number) =
        set(key, AttributeValue.NumberValue(value.toDouble()))

    private fun IAttributeContainer.long(key: AttributeKey): Long =
        (get(key) as? AttributeValue.NumberValue)?.value?.toLong() ?: 0L

    private companion object {
        val MATCH_ID = AttributeKey("lastmatch.match_id")
        val ENDED_AT = AttributeKey("lastmatch.ended_at")
        val MODE = AttributeKey("lastmatch.mode")
        val OUTCOME = AttributeKey("lastmatch.outcome")
        val TEAMS = AttributeKey("lastmatch.teams")
        val KILLS = AttributeKey("lastmatch.kills")
        val DEATHS = AttributeKey("lastmatch.deaths")
        val ASSISTS = AttributeKey("lastmatch.assists")
        val OBJECTIVE = AttributeKey("lastmatch.objective")
        val MVP = AttributeKey("lastmatch.mvp")
        val GOLD = AttributeKey("lastmatch.gold")
        val XP = AttributeKey("lastmatch.xp")
        val LEVEL = AttributeKey("lastmatch.level")
        val XP_INTO = AttributeKey("lastmatch.xp_into")
        val XP_NEXT = AttributeKey("lastmatch.xp_next")
        val RECAP_SHOWN = AttributeKey("lastmatch.recap_shown")
    }
}
