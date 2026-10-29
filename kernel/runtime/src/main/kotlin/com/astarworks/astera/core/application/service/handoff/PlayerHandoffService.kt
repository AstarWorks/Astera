package com.astarworks.astera.core.application.service.handoff

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IPlayerInventoryGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerInventorySnapshotRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository
import com.astarworks.astera.core.application.port.outbound.IProxyMessenger
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerArrivedOnServer
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerInventorySnapshot
import com.astarworks.astera.core.domain.player.PlayerSession
import com.astarworks.astera.core.domain.player.SessionState
import com.astarworks.astera.core.domain.proxy.HandoffToken
import com.astarworks.astera.core.domain.proxy.ProxyChannel
import com.astarworks.astera.core.domain.proxy.ServerName
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Ordered inventory hand-off between backends (F3-6, ADR-0187).
 *
 * Source side ([onTransferRequested]): capture the inventory, save it with a
 * strictly increasing per-player version, then publish a [HandoffToken] to the
 * target - all before the proxy `Connect` is sent. The quit that follows does
 * not save again.
 *
 * Target side ([onJoin]): the player is held in [SessionState.Loading] until
 * the token for a snapshot at least that new has arrived (bounded by
 * [HandoffSettings.maxWaitTicks]); then the snapshot is restored and
 * [PlayerArrivedOnServer] published. No token in time: the latest snapshot is
 * restored and a WARN logged. With [awaitToken] false (a single `ALL` server)
 * the join restores immediately.
 *
 * Quit ([onQuit]) saves as before, except for hand-offs already saved, players
 * a match holds the entry snapshot for, and players still waiting to be
 * restored (their inventory is not theirs yet).
 */
public class PlayerHandoffService(
    private val localServer: ServerName,
    private val awaitToken: Boolean,
    private val messenger: IProxyMessenger,
    private val snapshots: IPlayerInventorySnapshotRepository,
    private val inventory: () -> IPlayerInventoryGateway?,
    private val sessions: IPlayerSessionRepository,
    private val scheduler: IScheduler,
    private val eventBus: IDomainEventBus,
    private val isHeld: (PlayerId) -> Boolean,
    private val settings: HandoffSettings = HandoffSettings(),
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {

    /** Bounds of the arrival wait. */
    public data class HandoffSettings(
        val maxWaitTicks: Long = 40,
        val pollEveryTicks: Long = 2,
        val tokenTtlTicks: Long = 600,
        /** How long after a no-token fallback restore a late token still corrects the (possibly stale) inventory. */
        val lateTokenTicks: Long = 200,
    )

    private class Waiting(val deadlineTick: Long) {
        @Volatile var poll: IScheduler.TaskHandle? = null
        @Volatile var token: HandoffToken? = null
        val generation = java.util.concurrent.atomic.AtomicLong()
    }

    private class Stored(val token: HandoffToken, val atTick: Long)

    /** An arrival restored by the no-token fallback: [appliedMillis] is the `savedAt` of what was applied (0 = nothing). */
    private class Fallback(val appliedMillis: Long, val untilTick: Long)

    private val log = LoggerFactory.getLogger(javaClass)
    private val lastVersion: MutableMap<PlayerId, Long> = ConcurrentHashMap()
    private val handedOff: MutableSet<PlayerId> = ConcurrentHashMap.newKeySet()
    private val tokens: MutableMap<PlayerId, Stored> = ConcurrentHashMap()
    private val waiting: MutableMap<PlayerId, Waiting> = ConcurrentHashMap()
    private val dropped: MutableMap<PlayerId, PlayerSession> = ConcurrentHashMap()

    /**
     * Arrivals restored by the no-token fallback, for [HandoffSettings.lateTokenTicks]. The source's token may still be
     * on its way (slow save / slow channel): the inventory applied here can be hours old. While an entry lives the live
     * inventory is not trusted (never captured or saved: that would overwrite the source's newer snapshot), and a token
     * that arrives newer than what was applied triggers exactly one corrective apply of the matching snapshot.
     */
    private val fallbacks: MutableMap<PlayerId, Fallback> = ConcurrentHashMap()

    private fun fallbackLive(playerId: PlayerId): Fallback? {
        val fb = fallbacks[playerId] ?: return null
        if (scheduler.currentTick() < fb.untilTick) return fb
        fallbacks.remove(playerId, fb)
        return null
    }

    /** A fallback restore that applied a real (possibly stale) snapshot: the live inventory is not trusted until the window ends. */
    private fun fallbackActive(playerId: PlayerId): Boolean = (fallbackLive(playerId)?.appliedMillis ?: 0L) > 0L

    /** Arrived without a confirmed restore (timeout, stale/null snapshot, load or apply failure): the live inventory is not theirs. */
    private val unrestored: MutableSet<PlayerId> = ConcurrentHashMap.newKeySet()
    private var subscription: IProxyMessenger.Subscription? = null

    /**
     * `true` while [playerId] is waiting for the restore or arrived without a successful one. Nothing may capture or
     * save their live inventory then: it would overwrite the last good snapshot with an empty / default inventory.
     */
    public fun isRestorePending(playerId: PlayerId): Boolean = playerId in waiting || playerId in unrestored || fallbackActive(playerId)

    /** Starts listening for tokens (only a networked server needs it). */
    public fun start() {
        subscription = messenger.subscribe(ProxyChannel.HANDOFF) { message ->
            val token = HandoffTokenCodec.decode(message.payload)
            if (token == null) log.warn("Dropping malformed hand-off token from {}", message.fromServer) else onToken(token)
        }
    }

    public fun stop() {
        subscription?.cancel()
        subscription = null
        waiting.values.forEach { it.poll?.cancel() }
        waiting.clear()
    }

    // ---- Source side --------------------------------------------------

    /** Saves [playerId]'s state and publishes the token to [target]. Returns the token version, or `null` if nothing was saved. */
    public fun onTransferRequested(playerId: PlayerId, target: ServerName): Long? {
        if (playerId in waiting) return null // never restored here; the stored snapshot is still the truth
        val version = try {
            if (isHeld(playerId) || playerId in unrestored || fallbackActive(playerId)) {
                snapshots.findLatest(playerId)?.savedAt?.toEpochMilli() // the pre-match entry snapshot stays authoritative
            } else {
                val captured = inventory()?.capture(playerId) ?: return null
                val next = maxOf(clockMillis(), (lastVersion[playerId] ?: 0L) + 1)
                snapshots.save(captured.copy(savedAt = Instant.ofEpochMilli(next)))
                lastVersion[playerId] = next
                next
            }
        } catch (ex: Exception) {
            log.warn("Hand-off save failed for {} -> {}; the target will restore the latest snapshot.", playerId, target, ex)
            return null
        } ?: return null
        handedOff += playerId
        val sent = messenger.sendMessage(
            target,
            ProxyChannel.HANDOFF,
            HandoffTokenCodec.encode(HandoffToken(playerId, version, localServer)),
        )
        if (sent is Result.Failure) log.warn("Hand-off token for {} could not be sent to {}: {}", playerId, target, sent.error)
        return version
    }

    // ---- Target side --------------------------------------------------

    public fun onJoin(playerId: PlayerId) {
        handedOff -= playerId
        unrestored -= playerId
        fallbacks.remove(playerId)
        markLoading(playerId)
        if (!awaitToken || inventory() == null) { // single server, or a host without an inventory: nothing to order
            finish(playerId, applySnapshot(playerId, loadLatest(playerId)), null)
            return
        }
        val now = scheduler.currentTick()
        val entry = Waiting(deadlineTick = now + settings.maxWaitTicks)
        waiting[playerId] = entry
        purgeTokens()
        val early = tokens.remove(playerId)?.token
        if (early != null) {
            entry.token = early
            attempt(playerId, entry)
        } else {
            schedulePoll(playerId, entry)
        }
    }

    public fun onQuit(playerId: PlayerId) {
        val wait = waiting.remove(playerId)
        val skipSave = unrestored.remove(playerId) or fallbackActive(playerId)
        fallbacks.remove(playerId)
        if (wait != null) {
            wait.poll?.cancel()
            dropped.remove(playerId)?.let { sessions.upsert(it) } // still dropped from the match: the window keeps running
            log.debug("{} left before the hand-off restore finished; nothing to save.", playerId)
            return
        }
        if (handedOff.remove(playerId)) return
        if (skipSave) {
            log.warn("{} left without a confirmed restore; keeping the last saved snapshot.", playerId)
            return
        }
        if (isHeld(playerId)) {
            log.debug("onQuit: {} is inside a match; keeping the pre-match entry snapshot", playerId)
            return
        }
        try {
            val captured = inventory()?.capture(playerId) ?: return
            val next = maxOf(clockMillis(), (lastVersion[playerId] ?: 0L) + 1)
            snapshots.save(captured.copy(savedAt = Instant.ofEpochMilli(next)))
            lastVersion[playerId] = next
        } catch (ex: Exception) {
            log.warn("Quit save failed for {}; previous snapshot retained", playerId, ex)
        }
    }

    private fun onToken(token: HandoffToken) {
        val entry = waiting[token.playerId]
        if (entry == null) {
            val fb = fallbacks[token.playerId]
            if (fb != null && fallbackLive(token.playerId) != null && token.version > fb.appliedMillis) {
                correctLate(token, deadlineTick = scheduler.currentTick() + settings.maxWaitTicks)
                return
            }
            purgeTokens()
            tokens[token.playerId] = Stored(token, scheduler.currentTick())
            return
        }
        if (entry.token == null || entry.token!!.version < token.version) entry.token = token
        attempt(token.playerId, entry)
    }

    private fun schedulePoll(playerId: PlayerId, entry: Waiting) {
        entry.poll = scheduler.run(ExecutionScope.Entity(playerId.uuid), ticks(settings.pollEveryTicks)) {
            if (waiting[playerId] === entry) attempt(playerId, entry)
        }
    }

    /** One try: restore when the token (and a snapshot at least that new) is there, else wait or give up. */
    private fun attempt(playerId: PlayerId, entry: Waiting) {
        entry.poll?.cancel()
        val generation = entry.generation.incrementAndGet()
        if (entry.token == null && scheduler.currentTick() < entry.deadlineTick) {
            schedulePoll(playerId, entry)
            return
        }
        // Snapshot reads can block on Postgres; only inventory application runs on the entity owner.
        entry.poll = scheduler.run(ExecutionScope.Io, kotlin.time.Duration.ZERO) {
            val snapshot = loadLatest(playerId)
            scheduler.run(ExecutionScope.Entity(playerId.uuid), kotlin.time.Duration.ZERO) {
                if (waiting[playerId] !== entry || entry.generation.get() != generation) return@run
                val token = entry.token
                val fresh = token != null && snapshot != null && snapshot.savedAt.toEpochMilli() >= token.version
                val expired = scheduler.currentTick() >= entry.deadlineTick
                if (!fresh && !expired) {
                    schedulePoll(playerId, entry)
                    return@run
                }
                if (!fresh && token != null) {
                    log.warn("Snapshot of {} older than hand-off version {} after the wait; restoring it anyway.", playerId, token.version)
                } else if (token == null) {
                    log.warn("No hand-off token for {} within {} ticks; restoring the latest snapshot.", playerId, settings.maxWaitTicks)
                }
                waiting.remove(playerId, entry)
                val applied = applySnapshot(playerId, snapshot)
                if (applied && token == null) {
                    fallbacks[playerId] = Fallback(snapshot?.savedAt?.toEpochMilli() ?: 0L, scheduler.currentTick() + settings.lateTokenTicks)
                }
                finish(playerId, applied && (fresh || token == null), token?.fromServer)
            }.onRetired { waiting.remove(playerId, entry) }
        }
    }

    /** The token came after the fallback restore: apply the snapshot that matches it, once. */
    private fun correctLate(token: HandoffToken, deadlineTick: Long) {
        val playerId = token.playerId
        val fb = fallbacks[playerId] ?: return
        scheduler.run(ExecutionScope.Io, kotlin.time.Duration.ZERO) {
            val snapshot = loadLatest(playerId)
            scheduler.run(ExecutionScope.Entity(playerId.uuid), kotlin.time.Duration.ZERO) {
                if (fallbacks[playerId] !== fb) return@run // quit, re-joined or already corrected
                val fresh = snapshot != null && snapshot.savedAt.toEpochMilli() >= token.version
                if (!fresh && scheduler.currentTick() < deadlineTick) {
                    scheduler.run(ExecutionScope.Entity(playerId.uuid), ticks(settings.pollEveryTicks)) { correctLate(token, deadlineTick) }
                    return@run
                }
                fallbacks.remove(playerId, fb)
                if (!fresh) {
                    log.warn("Late hand-off token for {} (version {}) but no matching snapshot; keeping the fallback restore.", playerId, token.version)
                    return@run
                }
                if (isHeld(playerId)) {
                    log.warn("Late hand-off token for {} arrived while a match holds them; not applying.", playerId)
                    return@run
                }
                log.warn("Late hand-off token for {} (version {}): replacing the stale fallback restore.", playerId, token.version)
                applySnapshot(playerId, snapshot)
            }
        }
    }

    private fun loadLatest(playerId: PlayerId): PlayerInventorySnapshot? = try {
        snapshots.findLatest(playerId)
    } catch (ex: Exception) {
        log.warn("Snapshot load failed for {}; joining with the default inventory", playerId, ex)
        null
    }

    private fun applySnapshot(playerId: PlayerId, snapshot: PlayerInventorySnapshot?): Boolean {
        if (snapshot == null) {
            log.debug("No snapshot for {}; leaving inventory untouched", playerId)
            return true
        }
        return try {
            val applied = inventory()?.apply(snapshot) ?: false
            if (applied) {
                lastVersion.merge(playerId, snapshot.savedAt.toEpochMilli(), ::maxOf)
                log.info("Restored snapshot for {} (savedAt={})", playerId, snapshot.savedAt)
            }
            applied
        } catch (ex: Exception) {
            log.warn("Restore failed for {}; player joins with the default inventory", playerId, ex)
            false
        }
    }

    private fun finish(playerId: PlayerId, restored: Boolean, from: ServerName?) {
        if (!restored) {
            unrestored += playerId
            log.warn("{} arrived without a confirmed inventory restore; captures are suspended so the saved snapshot stays intact.", playerId)
        }
        markLobby(playerId)
        eventBus.publish(PlayerArrivedOnServer(playerId, from ?: UNKNOWN_SERVER, localServer))
        if (!restored) log.debug("Arrival of {} completed without a restore.", playerId)
    }

    private fun purgeTokens() {
        val now = scheduler.currentTick()
        tokens.entries.removeIf { now - it.value.atTick > settings.tokenTtlTicks }
    }

    private fun markLoading(playerId: PlayerId) {
        val current = sessions.find(playerId)
        // A player who dropped out of a running match keeps that claim through the restore: markLobby puts it
        // back so the match can resume them on arrival instead of finding a plain lobby session.
        if (current != null && current.state is SessionState.Disconnected && current.currentMatch != null) {
            dropped[playerId] = current
        }
        sessions.upsert(
            current?.copy(state = SessionState.Loading)
                ?: PlayerSession(playerId = playerId, state = SessionState.Loading, currentMatch = null),
        )
    }

    private fun markLobby(playerId: PlayerId) {
        val current = sessions.find(playerId) ?: return
        val claim = dropped.remove(playerId)
        if (claim != null && current.state == SessionState.Loading) {
            sessions.upsert(claim)
            return
        }
        if (current.state == SessionState.Loading) {
            sessions.upsert(current.copy(state = SessionState.Lobby, currentMatch = null))
        }
    }

    public companion object {
        /** `fromServer` of an arrival whose token never came (a direct login). */
        public val UNKNOWN_SERVER: ServerName = ServerName("unknown")
    }
}
