package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.match.domain.match.MatchWorld
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Wave 5d [InMemoryMatchWorldRepository] tests.
 *
 * Pins:
 *  - `save` then `findByMatch` returns the stored [MatchWorld].
 *  - A second `save` for the same `matchId` replaces the previous entry
 *    (last-write-wins).
 *  - `remove` drops the entry; subsequent `findByMatch` returns null.
 *  - `all()` enumerates every distinct [MatchWorld] (one per [MatchId]).
 */
internal class InMemoryMatchWorldRepositoryTest {

    private lateinit var repo: InMemoryMatchWorldRepository

    private val matchA = MatchId("repo-test-a")
    private val matchB = MatchId("repo-test-b")
    private val worldA = WorldId("world-a")
    private val worldB = WorldId("world-b")
    private val pathA = "match-worlds/${matchA.value}.schem"
    private val pathB = "match-worlds/${matchB.value}.schem"

    @BeforeEach
    fun setUp() {
        repo = InMemoryMatchWorldRepository()
    }

    @Test
    fun `save then findByMatch returns the stored MatchWorld`() {
        val mw = MatchWorld(matchA, worldA, pathA)

        repo.save(mw)

        assertThat(repo.findByMatch(matchA)).isEqualTo(mw)
    }

    @Test
    fun `findByMatch returns null for an unknown match`() {
        assertThat(repo.findByMatch(MatchId("unknown"))).isNull()
    }

    @Test
    fun `save replaces the previous entry for the same matchId`() {
        val first = MatchWorld(matchA, worldA, pathA)
        val replacement = MatchWorld(matchA, WorldId("world-a-v2"), pathA)

        repo.save(first)
        repo.save(replacement)

        assertThat(repo.findByMatch(matchA)).isEqualTo(replacement)
        assertThat(repo.all()).containsExactly(replacement)
    }

    @Test
    fun `remove drops the entry`() {
        repo.save(MatchWorld(matchA, worldA, pathA))

        repo.remove(matchA)

        assertThat(repo.findByMatch(matchA)).isNull()
        assertThat(repo.all()).isEmpty()
    }

    @Test
    fun `remove on an unknown match is a no-op`() {
        // Should not throw; also should not affect other entries.
        repo.save(MatchWorld(matchB, worldB, pathB))

        repo.remove(MatchId("unknown"))

        assertThat(repo.findByMatch(matchB)).isNotNull()
        assertThat(repo.all()).hasSize(1)
    }

    @Test
    fun `all returns every distinct MatchWorld`() {
        val mwA = MatchWorld(matchA, worldA, pathA)
        val mwB = MatchWorld(matchB, worldB, pathB)

        repo.save(mwA)
        repo.save(mwB)

        assertThat(repo.all()).containsExactlyInAnyOrder(mwA, mwB)
    }

    @Test
    fun `all is a snapshot - mutating the returned list does not affect the repo`() {
        repo.save(MatchWorld(matchA, worldA, pathA))
        val snapshot = repo.all().toMutableList()

        snapshot.clear()

        assertThat(repo.all()).hasSize(1)
    }
}
