package com.astarworks.astera.core.domain.stats

import com.astarworks.astera.core.domain.attribute.AttributeKey
import com.astarworks.astera.core.domain.attribute.AttributeValue
import com.astarworks.astera.core.domain.attribute.Attributes
import com.astarworks.astera.core.domain.attribute.IAttributeContainer
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Per-player aggregate stats — Wave 5e facade over [IAttributeContainer]
 * (ADR-0058 §5).
 *
 * Each numeric field is a computed getter reading
 * `container.effectiveNumber(Attributes.STAT_*).toLong()`. The container is
 * the source of truth; the Postgres overlay in `plugin:persistence` flushes
 * to V14 `player_attributes` JSONB.
 *
 * Construction must always pass a `container` — use [PlayerStats.empty] for
 * test scaffolding, which wraps a fresh
 * [com.astarworks.astera.core.domain.attribute.SimpleAttributeContainer].
 *
 * Mutation goes through the use cases (`RecordKillUseCase` etc.) which
 * `container.set(STAT_KILLS, NumberValue(current + 1))`. The previously
 * exposed `.copy(kills = ...)` API is gone — `data class` copy semantics
 * would have re-constructed the parallel state ADR-0058 §5 forbids.
 */
public class PlayerStats(
    public val playerId: PlayerId,
    public val container: IAttributeContainer,
) {
    public val kills: Long get() = readLong(Attributes.STAT_KILLS)
    public val deaths: Long get() = readLong(Attributes.STAT_DEATHS)
    public val assists: Long get() = readLong(Attributes.STAT_ASSISTS)
    public val damageDealt: Long get() = readLong(Attributes.STAT_DAMAGE_DEALT)
    public val damageTaken: Long get() = readLong(Attributes.STAT_DAMAGE_TAKEN)
    public val playtimeSec: Long get() = readLong(Attributes.STAT_PLAYTIME_SEC)
    public val matchesPlayed: Long get() = readLong(Attributes.STAT_MATCHES_PLAYED)
    public val wins: Long get() = readLong(Attributes.STAT_WINS)
    public val losses: Long get() = readLong(Attributes.STAT_LOSSES)

    public val kdRatio: Double
        get() = if (deaths == 0L) kills.toDouble() else kills.toDouble() / deaths

    private fun readLong(key: AttributeKey): Long =
        container.effectiveNumber(key).toLong()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlayerStats) return false
        return playerId == other.playerId && container === other.container
    }

    override fun hashCode(): Int = playerId.hashCode()

    override fun toString(): String =
        "PlayerStats(playerId=$playerId, kills=$kills, deaths=$deaths, assists=$assists)"

    public companion object {
        /**
         * Empty stats backed by a fresh
         * [com.astarworks.astera.core.domain.attribute.SimpleAttributeContainer].
         *
         * Production code goes through `IPlayerAttributeContainerStore.containerFor(playerId)`
         * so all reads/writes share state with `Wallet` + `ManaPool` for the same player;
         * this helper exists for tests that need a standalone instance.
         */
        public fun empty(playerId: PlayerId): PlayerStats =
            PlayerStats(playerId, com.astarworks.astera.core.domain.attribute.SimpleAttributeContainer())

        /** Empty stats over a caller-provided container. */
        public fun empty(playerId: PlayerId, container: IAttributeContainer): PlayerStats =
            PlayerStats(playerId, container)

        /**
         * Test helper: produce a [PlayerStats] seeded with the given counters
         * over a fresh [com.astarworks.astera.core.domain.attribute.SimpleAttributeContainer].
         * Equivalent to the old `data class` constructor + `copy`.
         */
        @JvmOverloads
        public fun of(
            playerId: PlayerId,
            kills: Long = 0L,
            deaths: Long = 0L,
            assists: Long = 0L,
            damageDealt: Long = 0L,
            damageTaken: Long = 0L,
            playtimeSec: Long = 0L,
            matchesPlayed: Long = 0L,
            wins: Long = 0L,
            losses: Long = 0L,
        ): PlayerStats {
            val c = com.astarworks.astera.core.domain.attribute.SimpleAttributeContainer()
            c.set(Attributes.STAT_KILLS, AttributeValue.NumberValue(kills.toDouble()))
            c.set(Attributes.STAT_DEATHS, AttributeValue.NumberValue(deaths.toDouble()))
            c.set(Attributes.STAT_ASSISTS, AttributeValue.NumberValue(assists.toDouble()))
            c.set(Attributes.STAT_DAMAGE_DEALT, AttributeValue.NumberValue(damageDealt.toDouble()))
            c.set(Attributes.STAT_DAMAGE_TAKEN, AttributeValue.NumberValue(damageTaken.toDouble()))
            c.set(Attributes.STAT_PLAYTIME_SEC, AttributeValue.NumberValue(playtimeSec.toDouble()))
            c.set(Attributes.STAT_MATCHES_PLAYED, AttributeValue.NumberValue(matchesPlayed.toDouble()))
            c.set(Attributes.STAT_WINS, AttributeValue.NumberValue(wins.toDouble()))
            c.set(Attributes.STAT_LOSSES, AttributeValue.NumberValue(losses.toDouble()))
            return PlayerStats(playerId, c)
        }
    }
}
