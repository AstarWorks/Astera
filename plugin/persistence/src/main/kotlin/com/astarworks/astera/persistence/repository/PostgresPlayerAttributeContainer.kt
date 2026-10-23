package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.application.port.outbound.IPlayerAttributeContainerStore
import com.astarworks.astera.core.domain.attribute.AttributeKey
import com.astarworks.astera.core.domain.attribute.AttributeModifier
import com.astarworks.astera.core.domain.attribute.AttributeValue
import com.astarworks.astera.core.domain.attribute.IAttributeContainer
import com.astarworks.astera.core.domain.attribute.SimpleAttributeContainer
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.persistence.jsonb.PlayerAttributesJson
import com.astarworks.astera.persistence.jsonb.JsonbColumnType
import com.astarworks.astera.persistence.table.PlayerAttributesTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

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

    override fun reload(playerId: PlayerId) {
        val cached = cache[playerId] ?: return
        val fresh = SimpleAttributeContainer()
        readInto(playerId, fresh)
        cached.replaceWith(fresh)
    }

    private fun load(playerId: PlayerId): WriteThroughContainer {
        val backing = SimpleAttributeContainer()
        readInto(playerId, backing)
        return WriteThroughContainer(playerId, backing) { id, c -> write(id, c) }
    }

    private fun readInto(playerId: PlayerId, backing: SimpleAttributeContainer) {
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
    }

    /**
     * Whole-document upsert, except that `wallet.*` entries already in the row
     * win over this JVM's cached ones: money is owned by [PostgresCurrencyLedger]
     * (atomic SQL) and another server may have changed it since this cache
     * loaded, so a stat / XP / modifier write here must never roll it back.
     * A wallet entry that is only in the cache (row without one) is stored.
     */
    private fun write(playerId: PlayerId, container: WriteThroughContainer) {
        val payload = PlayerAttributesJson.fromDomain(
            values = container.snapshotValues(),
            modifiers = container.snapshotModifiers(),
        )
        val text = JsonbColumnType.DefaultJson.encodeToString(PlayerAttributesJson.serializer(), payload)
        transaction(db) {
            (connection.connection as java.sql.Connection).prepareStatement(UPSERT_KEEPING_WALLET).use { st ->
                st.setObject(1, playerId.uuid)
                st.setString(2, text)
                st.executeUpdate()
            }
        }
    }

    private companion object {
        const val UPSERT_KEEPING_WALLET: String =
            "INSERT INTO player_attributes AS pa (player_id, payload, updated_at) VALUES (?, ?::jsonb, now()) " +
                "ON CONFLICT (player_id) DO UPDATE SET " +
                "payload = jsonb_set(EXCLUDED.payload, '{values}', " +
                "COALESCE(EXCLUDED.payload -> 'values', '{}'::jsonb) || COALESCE((" +
                "SELECT jsonb_object_agg(e.key, e.value) FROM jsonb_each(pa.payload -> 'values') e " +
                "WHERE e.key IN ('wallet.gold', 'wallet.gem')), '{}'::jsonb)), " +
                "updated_at = now()"
    }
}

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

    /** Swap in [fresh] state without writing it back (it was just read from the row). */
    internal fun replaceWith(fresh: SimpleAttributeContainer) {
        backing.keys().forEach { backing.remove(it) }
        backing.modifiers().map { it.sourceId }.distinct().forEach { backing.removeModifiersBySource(it) }
        fresh.keys().forEach { backing.set(it, fresh.get(it)!!) }
        fresh.modifiers().forEach { backing.addModifier(it) }
    }

    internal fun snapshotValues(): Map<AttributeKey, AttributeValue> =
        backing.keys().associateWith { backing.get(it)!! }

    internal fun snapshotModifiers(): List<AttributeModifier> = backing.modifiers()
}
