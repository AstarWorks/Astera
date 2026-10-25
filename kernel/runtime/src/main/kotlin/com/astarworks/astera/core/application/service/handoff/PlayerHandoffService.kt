package com.astarworks.astera.core.application.service.handoff

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
    )

    private class Waiting(val deadlineTick: Long) {
        var poll: IScheduler.TaskHandle? = null
        var token: HandoffToken? = null
    }

    private class Stored(val token: HandoffToken, val atTick: Long)

    private val log = LoggerFactory.getLogger(javaClass)
    private val lastVersion: MutableMap<PlayerId, Long> = ConcurrentHashMap()
    private val handedOff: MutableSet<PlayerId> = ConcurrentHashMap.newKeySet()
    private val tokens: MutableMap<PlayerId, Stored> = ConcurrentHashMap()
    private val waiting: MutableMap<PlayerId, Waiting> = ConcurrentHashMap()
    private val dropped: MutableMap<PlayerId, PlayerSession> = ConcurrentHashMap()
    private var subscription: IProxyMessenger.Subscription? = null

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
            if (isHeld(playerId)) {
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
        if (wait != null) {
            wait.poll?.cancel()
            dropped.remove(playerId)?.let { sessions.upsert(it) } // still dropped from the match: the window keeps running
            log.debug("{} left before the hand-off restore finished; nothing to save.", playerId)
            return
        }
        if (handedOff.remove(playerId)) return
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
            purgeTokens()
            tokens[token.playerId] = Stored(token, scheduler.currentTick())
            return
        }
        if (entry.token == null || entry.token!!.version < token.version) entry.token = token
        attempt(token.playerId, entry)
    }

    private fun schedulePoll(playerId: PlayerId, entry: Waiting) {
        entry.poll = scheduler.runLater(ticks(settings.pollEveryTicks)) {
            if (waiting[playerId] === entry) attempt(playerId, entry)
        }
    }

    /** One try: restore when the token (and a snapshot at least that new) is there, else wait or give up. */
    private fun attempt(playerId: PlayerId, entry: Waiting) {
        entry.poll?.cancel()
        val token = entry.token
        val expired = scheduler.currentTick() >= entry.deadlineTick
        if (token != null) {
            val snapshot = loadLatest(playerId)
            val fresh = snapshot != null && snapshot.savedAt.toEpochMilli() >= token.version
            if (fresh) {
                waiting.remove(playerId)
                finish(playerId, applySnapshot(playerId, snapshot), token.fromServer)
                return
            }
            if (!expired) {
                schedulePoll(playerId, entry)
                return
            }
            log.warn("Snapshot of {} older than hand-off version {} after the wait; restoring it anyway.", playerId, token.version)
        } else if (!expired) {
            schedulePoll(playerId, entry)
            return
        } else {
            log.warn("No hand-off token for {} within {} ticks; restoring the latest snapshot.", playerId, settings.maxWaitTicks)
        }
        waiting.remove(playerId)
        finish(playerId, applySnapshot(playerId, loadLatest(playerId)), token?.fromServer)
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
