package com.astarworks.astera.persistence.adapter.io

import com.astarworks.astera.core.application.port.outbound.*
import com.astarworks.astera.core.application.port.outbound.cosmetic.ICosmeticLoadoutRepository
import com.astarworks.astera.core.domain.cosmetic.CosmeticKind
import com.astarworks.astera.core.domain.cosmetic.CosmeticLoadout
import com.astarworks.astera.core.domain.player.*
import com.astarworks.astera.persistence.repository.*
import com.astarworks.astera.stats.application.port.outbound.IPlayerXpRepository
import org.jetbrains.exposed.v1.jdbc.Database
import java.util.concurrent.ConcurrentHashMap

/** Entity-owned records, published by async pre-login before any join listener runs. */
public class PlayerDataCache(db: Database, private val writes: WriteBehind) {
    private val profileDb = PostgresPlayerProfileRepository(db)
    private val settingsDb = PostgresPlayerSettingsRepository(db)
    private val loadoutDb = PostgresCosmeticLoadoutRepository(db)
    private val xpDb = PostgresPlayerXpRepository(db)
    private val sessionDb = PostgresPlayerSessionRepository(db)
    private val inventoryDb = PostgresPlayerInventorySnapshotRepository(db)
    private data class Entry<T>(val value: T)
    private val profile = ConcurrentHashMap<PlayerId, Entry<PlayerProfile?>>()
    private val settings = ConcurrentHashMap<PlayerId, Entry<PlayerSettings?>>()
    private val loadout = ConcurrentHashMap<PlayerId, CosmeticLoadout>()
    private val xp = ConcurrentHashMap<PlayerId, Long>()
    private val session = ConcurrentHashMap<PlayerId, Entry<PlayerSession?>>()
    private val inventory = ConcurrentHashMap<PlayerId, Entry<PlayerInventorySnapshot?>>()
    public val attributes: PostgresPlayerAttributeContainer = PostgresPlayerAttributeContainer(db, writes)

    /** Called only by the IO worker. Null records are cached too; a miss never goes to JDBC. */
    public fun preload(id: PlayerId) {
        IoThread.check()
        profile[id] = Entry(profileDb.find(id))
        settings[id] = Entry(settingsDb.find(id))
        loadout[id] = loadoutDb.find(id)
        xp[id] = xpDb.totalXp(id)
        session[id] = Entry(sessionDb.find(id))
        inventory[id] = Entry(inventoryDb.findLatest(id))
        attributes.preload(id)
    }

    public fun evict(id: PlayerId) {
        profile.remove(id); settings.remove(id); loadout.remove(id); xp.remove(id)
        session.remove(id); inventory.remove(id); attributes.evict(id)
    }

    public val profiles: IPlayerProfileRepository = object : IPlayerProfileRepository {
        override fun find(playerId: PlayerId): PlayerProfile? = checkNotNull(profile[playerId]) { "Profile not preloaded: $playerId" }.value
        override fun insertIfAbsent(profile: PlayerProfile): Boolean {
            val existing = checkNotNull(this@PlayerDataCache.profile[profile.id]) { "Profile not preloaded" }
            if (existing.value != null) return false
            this@PlayerDataCache.profile[profile.id] = Entry(profile)
            writes.enqueue("profile" to profile.id) { profileDb.insertIfAbsent(profile) }
            return true
        }
    }
    public val playerSettings: IPlayerSettingsRepository = object : IPlayerSettingsRepository {
        override fun find(playerId: PlayerId): PlayerSettings? = checkNotNull(settings[playerId]) { "Settings not preloaded" }.value
        override fun upsert(settings: PlayerSettings) {
            this@PlayerDataCache.settings[settings.playerId] = Entry(settings)
            writes.enqueue("settings" to settings.playerId) { settingsDb.upsert(settings) }
        }
    }
    public val cosmetics: ICosmeticLoadoutRepository = object : ICosmeticLoadoutRepository {
        override fun find(playerId: PlayerId): CosmeticLoadout = checkNotNull(loadout[playerId]) { "Loadout not preloaded" }
        override fun set(playerId: PlayerId, kind: CosmeticKind, cosmeticId: String?) {
            loadout[playerId] = find(playerId).with(kind, cosmeticId)
            writes.enqueue(Triple("cosmetic", playerId, kind)) { loadoutDb.set(playerId, kind, cosmeticId) }
        }
    }
    public val playerXp: IPlayerXpRepository = object : IPlayerXpRepository {
        override fun totalXp(playerId: PlayerId): Long = checkNotNull(xp[playerId]) { "XP not preloaded" }
        override fun addXp(playerId: PlayerId, amount: Long): Long {
            require(amount >= 0)
            val total = Math.addExact(totalXp(playerId), amount)
            xp[playerId] = total
            writes.enqueue("xp" to playerId) { xpDb.setTotalXp(playerId, total) }
            return total
        }
    }
    public val sessions: IPlayerSessionRepository = object : IPlayerSessionRepository {
        override fun find(playerId: PlayerId): PlayerSession? = session[playerId]?.value
        override fun upsert(session: PlayerSession) {
            this@PlayerDataCache.session[session.playerId] = Entry(session)
            writes.enqueue("session" to session.playerId) { sessionDb.upsert(session) }
        }
        override fun remove(playerId: PlayerId) {
            session.remove(playerId)
            writes.enqueue("session" to playerId) { sessionDb.remove(playerId) }
        }
        override fun all(): List<PlayerSession> = session.values.mapNotNull { it.value }
    }
    public val snapshots: IPlayerInventorySnapshotRepository = object : IPlayerInventorySnapshotRepository {
        override fun findLatest(playerId: PlayerId): PlayerInventorySnapshot? = inventory[playerId]?.value
        override fun save(snapshot: PlayerInventorySnapshot) {
            inventory[snapshot.playerId] = Entry(snapshot)
            writes.enqueue("inventory" to snapshot.playerId) { inventoryDb.save(snapshot) }
        }
        override fun delete(playerId: PlayerId) {
            inventory[playerId] = Entry(null)
            writes.enqueue("inventory" to playerId) { inventoryDb.delete(playerId) }
        }
    }
}
