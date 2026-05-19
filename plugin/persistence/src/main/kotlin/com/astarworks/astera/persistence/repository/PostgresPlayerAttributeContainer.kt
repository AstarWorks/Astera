package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.application.port.outbound.IPlayerAttributeContainerStore
import com.astarworks.astera.core.domain.attribute.AttributeKey
import com.astarworks.astera.core.domain.attribute.AttributeModifier
import com.astarworks.astera.core.domain.attribute.AttributeValue
import com.astarworks.astera.core.domain.attribute.IAttributeContainer
import com.astarworks.astera.core.domain.attribute.SimpleAttributeContainer
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.persistence.jsonb.PlayerAttributesJson
import com.astarworks.astera.persistence.table.PlayerAttributesTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant

/**
 * Postgres-backed [IPlayerAttributeContainerStore] (Wave 5e / ADR-0058 §5).
 *
 * Lazy-loads one container per [PlayerId] on first call to [containerFor];
 * subsequent calls return the cached instance. Each cached container is a
 * [WriteThroughContainer] wrapping a [SimpleAttributeContainer] — every
 * `set` / `addModifier` / `removeModifiersBySource` writes through to the
 * V14 `player_attributes` JSONB row in the same call (per-call flush is
 * simpler than a debounced timer, and Phase 1 traffic doesn't justify the
 * extra machinery; revisit when HUD refresh hits >100 ops/sec/player).
 *
 * Registered by `AsteraPersistencePlugin` at priority 100; overlays the
 * in-memory `InMemoryPlayerAttributeContainer` (priority 0).
 *
 * Threading: same contract as [IAttributeContainer] — main-thread only.
 * Exposed transactions are short and synchronous; long-running batches
 * should debounce via a future scheduler hook, not here.
 */
public class PostgresPlayerAttributeContainer(private val db: Database) : IPlayerAttributeContainerStore {

    private val cache: MutableMap<PlayerId, WriteThroughContainer> = mutableMapOf()

    override fun containerFor(playerId: PlayerId): IAttributeContainer =
        cache.getOrPut(playerId) { load(playerId) }

    override fun flush(playerId: PlayerId) {
        val container = cache[playerId] ?: return
        write(playerId, container)
    }

    override fun loaded(): Map<PlayerId, IAttributeContainer> = cache.toMap()

    private fun load(playerId: PlayerId): WriteThroughContainer {
        val backing = SimpleAttributeContainer()
        val payload = transaction(db) {
            PlayerAttributesTable
                .selectAll()
                .where { PlayerAttributesTable.playerId eq playerId.uuid }
                .firstOrNull()
                ?.get(PlayerAttributesTable.payload)
        }
        if (payload != null) {
            payload.values.forEach { (keyStr, json) ->
                backing.set(AttributeKey(keyStr), json.toDomain())
            }
            payload.modifiers.forEach { mod ->
                backing.addModifier(mod.toDomain())
            }
        }
        return WriteThroughContainer(playerId, backing) { id, c -> write(id, c) }
    }

    private fun write(playerId: PlayerId, container: WriteThroughContainer) {
        val payload = PlayerAttributesJson.fromDomain(
            values = container.snapshotValues(),
            modifiers = container.snapshotModifiers(),
        )
        transaction(db) {
            PlayerAttributesTable.upsert(PlayerAttributesTable.playerId) {
                it[PlayerAttributesTable.playerId] = playerId.uuid
                it[PlayerAttributesTable.payload] = payload
                it[updatedAt] = Instant.now()
            }
        }
    }
}

/**
 * Decorator that delegates reads to a backing [SimpleAttributeContainer] and
 * flushes on each mutation. Snapshot helpers extract the current value/modifier
 * lists so the JSONB payload can be re-serialized without exposing the
 * backing fields directly.
 */
internal class WriteThroughContainer(
    private val playerId: PlayerId,
    private val backing: SimpleAttributeContainer,
    private val onWrite: (PlayerId, WriteThroughContainer) -> Unit,
) : IAttributeContainer {

    override fun get(key: AttributeKey): AttributeValue? = backing.get(key)

    override fun set(key: AttributeKey, value: AttributeValue) {
        backing.set(key, value)
        onWrite(playerId, this)
    }

    override fun remove(key: AttributeKey) {
        backing.remove(key)
        onWrite(playerId, this)
    }

    override fun keys(): Set<AttributeKey> = backing.keys()

    override fun modifiers(): List<AttributeModifier> = backing.modifiers()

    override fun addModifier(modifier: AttributeModifier) {
        backing.addModifier(modifier)
        onWrite(playerId, this)
    }

    override fun removeModifiersBySource(sourceId: String) {
        backing.removeModifiersBySource(sourceId)
        onWrite(playerId, this)
    }

    override fun effectiveNumber(key: AttributeKey): Double = backing.effectiveNumber(key)

    internal fun snapshotValues(): Map<AttributeKey, AttributeValue> =
        backing.keys().associateWith { backing.get(it)!! }

    internal fun snapshotModifiers(): List<AttributeModifier> = backing.modifiers()
}
