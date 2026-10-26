package com.astarworks.astera.persistence.adapter.io

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.persistence.repository.PostgresMatchRepository
import java.util.concurrent.ConcurrentHashMap

/** Active-match ticks never query the database. Initial rows are loaded during IO bootstrap. */
public class CachedMatchRepository(private val storage: PostgresMatchRepository, private val writes: WriteBehind) : IMatchRepository {
    private val matches = ConcurrentHashMap<MatchId, Match>()

    public fun preload() { storage.all().forEach { matches[it.id] = it } }
    override fun findById(id: MatchId): Match? = matches[id]
    override fun all(): Collection<Match> = matches.values.toList()
    override fun findActiveMatches(): Collection<Match> = matches.values.filter { it.phase != MatchPhase.ENDED }
    override fun findMatchOf(playerId: PlayerId): Match? = findActiveMatches().firstOrNull { it.hasParticipant(playerId) }
    override fun findInPhase(phase: MatchPhase): Collection<Match> = matches.values.filter { it.phase == phase }
    override fun save(entity: Match) {
        matches[entity.id] = entity
        writes.enqueue("match" to entity.id) { storage.save(entity) }
    }
    override fun delete(id: MatchId) {
        matches.remove(id)
        writes.enqueue("match" to id) { storage.delete(id) }
    }
}
