package com.astarworks.astera.match.application.service.replay

import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.combat.domain.event.SkillCast
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.ChatMessageSent
import com.astarworks.astera.core.domain.event.MatchRecordingStarted
import com.astarworks.astera.core.domain.event.MatchRecordingStopped
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.replay.DamageSourceKind
import com.astarworks.astera.core.domain.replay.KeyFrame
import com.astarworks.astera.core.domain.replay.RecordedReplay
import com.astarworks.astera.core.domain.replay.ReplayError
import com.astarworks.astera.core.domain.replay.ReplayEvent
import com.astarworks.astera.core.domain.replay.ReplayFormat
import com.astarworks.astera.core.domain.replay.ReplayId
import com.astarworks.astera.match.application.port.inbound.IMatchRecorder
import com.astarworks.astera.match.application.port.outbound.IMatchReplayRepository
import com.astarworks.astera.match.application.port.outbound.IRecorderStateProvider
import com.astarworks.astera.match.domain.event.PhaseChanged
import org.slf4j.LoggerFactory

/**
 * Default [IMatchRecorder] (Wave 5v, ADR-0094).
 *
 * Subscribes to the published domain events on [bus] at construction time,
 * converts each into a [ReplayEvent], and appends to the buffer of every
 * **active** recording whose `matchId` matches. Recording state is owned
 * by this engine; admin commands toggle it via [startRecording] /
 * [stopRecording].
 *
 * A scheduler tick fires every `format.keyFramePer` ticks while a
 * recording is active and writes a [KeyFrame] using
 * [IRecorderStateProvider] (which returns empty maps under the Noop
 * provider — see that class for the wave-progression story).
 *
 * ## Size budget
 *
 * The 4-player × 1-hour ≈ 5 MB target assumes:
 *  - ~600 movement events / minute / player (one per ~2 ticks of motion)
 *    × 4 players × 60 min ≈ 144k events × ~30 bytes JSONB ≈ 4.3 MB
 *  - 720 key frames × ~80 bytes/player × 4 players ≈ 230 KB
 *  - misc events (damage / skill / chat) ≲ 500 KB
 *
 * The engine enforces a hard byte-cap of [MAX_BUFFER_BYTES] per match
 * (default 12 MB to leave headroom for `FullPerTick` outliers); on cap
 * hit the recorder logs a warning and continues — events past the cap
 * are silently dropped (the **earliest** ones are preserved, which is
 * the inverse of `BlockChangeJournal`'s LIFO; replay buffers want the
 * match's start so playback can scrub from t=0).
 *
 * ## Thread-safety
 *
 * Single-threaded: same Astera invariant as `BlockChangeJournal` etc.,
 * domain bus delivery and scheduler tick both run on the server main
 * thread (regional contract on `IScheduler`).
 */
public class ReplayCaptureEngine(
    private val bus: IDomainEventBus,
    private val scheduler: IScheduler,
    private val repository: IMatchReplayRepository,
    private val stateProvider: IRecorderStateProvider,
) : IMatchRecorder {

    private val logger = LoggerFactory.getLogger(ReplayCaptureEngine::class.java)
    private val active: MutableMap<MatchId, RecordingBuffer> = mutableMapOf()
    private val subscriptions = mutableListOf<IDomainEventBus.Subscription>()
    private val scheduledTasks = mutableListOf<IScheduler.TaskHandle>()
    private var bootstrapped = false

    /**
     * Subscribe to the domain bus + start the global key-frame ticker.
     * Idempotent; safe to call multiple times.
     *
     * Separate from the constructor so callers can inject the engine into
     * the `ServiceRegistry` before subscriptions fire (avoids losing events
     * that publish during init).
     */
    public fun bootstrap() {
        if (bootstrapped) return
        bootstrapped = true

        // Cross-plugin combat events (softdep — these classes are present
        // at runtime because Astera-Match `softdepend: [Astera-Combat]`).
        subscriptions += bus.subscribe(DamageDealt::class) { e -> onDamageDealt(e) }
        subscriptions += bus.subscribe(PlayerKilled::class) { e -> onPlayerKilled(e) }
        subscriptions += bus.subscribe(SkillCast::class) { e -> onSkillCast(e) }

        // Core / Match events.
        subscriptions += bus.subscribe(ChatMessageSent::class) { e -> onChatSent(e) }
        subscriptions += bus.subscribe(PhaseChanged::class) { e -> onPhaseChanged(e) }
        subscriptions += bus.subscribe(MatchStarted::class) { e ->
            // Phase 1 PoC: do NOT auto-record. Logged at DEBUG so operators
            // can verify the listener wired.
            logger.debug("ReplayCaptureEngine: observed MatchStarted ${e.matchId}; opt-in recording.")
        }

        // Key-frame tick at the default cadence; per-recording cadence
        // override is honoured inside the tick by skipping frames whose
        // tick-count modulo the recording's own keyFramePer isn't zero.
        scheduledTasks += scheduler.runRepeatingTicks(
            initialDelayTicks = KEYFRAME_TICKER_PERIOD,
            periodTicks = KEYFRAME_TICKER_PERIOD,
        ) { tickKeyFrames() }
    }

    /** Cancel subscriptions + scheduled tasks. Used by `AsteraMatchPlugin.onDisable`. */
    public fun shutdown() {
        scheduledTasks.forEach { it.cancel() }
        scheduledTasks.clear()
        subscriptions.forEach { it.cancel() }
        subscriptions.clear()
        active.clear()
        bootstrapped = false
    }

    // ---- IMatchRecorder -----------------------------------------------------

    override fun startRecording(matchId: MatchId, format: ReplayFormat) {
        val startTick = scheduler.currentTick()
        val existing = active[matchId]
        val buffer = if (existing != null) {
            // Idempotent: replace format only, preserve buffered events / frames.
            existing.format = format
            existing
        } else {
            val newBuf = RecordingBuffer(
                replayId = ReplayId.random(),
                matchId = matchId,
                startTick = startTick,
                format = format,
            )
            active[matchId] = newBuf
            newBuf
        }
        bus.publish(
            MatchRecordingStarted(
                replayId = buffer.replayId,
                matchId = matchId,
                formatDescriptor = format.descriptor(),
                atTick = startTick,
            ),
        )
        logger.info(
            "ReplayCaptureEngine: startRecording match={} replay={} format={} atTick={}",
            matchId, buffer.replayId, format::class.simpleName, startTick,
        )
    }

    override fun stopRecording(matchId: MatchId): Result<RecordedReplay, ReplayError> {
        val buffer = active.remove(matchId)
            ?: return Result.failure(ReplayError.NotRecording(matchId))

        if (buffer.events.isEmpty() && buffer.keyFrames.isEmpty()) {
            return Result.failure(ReplayError.EmptyRecording(matchId))
        }

        // endTick must cover every buffered event / key frame (a publisher
        // may emit with atTick ahead of scheduler.currentTick when running
        // under FakeScheduler-like setups, or when domain events backfill
        // older tick values).
        val maxEventTick = buffer.events.maxOfOrNull { it.atTick } ?: buffer.startTick
        val maxFrameTick = buffer.keyFrames.maxOfOrNull { it.tick } ?: buffer.startTick
        val endTick = maxOf(
            scheduler.currentTick(),
            buffer.startTick,
            maxEventTick,
            maxFrameTick,
        )

        val finalised = RecordedReplay(
            id = buffer.replayId,
            matchId = matchId,
            startTick = buffer.startTick,
            endTick = endTick,
            format = buffer.format,
            events = buffer.events.toList(),
            keyFrames = buffer.keyFrames.toList(),
            sizeBytes = buffer.estimatedSize,
        )

        return when (val saveResult = repository.save(finalised)) {
            is Result.Success -> {
                bus.publish(
                    MatchRecordingStopped(
                        replayId = finalised.id,
                        matchId = matchId,
                        startTick = finalised.startTick,
                        endTick = finalised.endTick,
                        eventCount = finalised.events.size,
                        keyFrameCount = finalised.keyFrames.size,
                        sizeBytes = finalised.sizeBytes,
                        atTick = endTick,
                    ),
                )
                logger.info(
                    "ReplayCaptureEngine: stopRecording match={} replay={} events={} keyFrames={} sizeBytes={}",
                    matchId, finalised.id, finalised.events.size,
                    finalised.keyFrames.size, finalised.sizeBytes,
                )
                Result.success(finalised)
            }
            is Result.Failure -> saveResult
        }
    }

    override fun isRecording(matchId: MatchId): Boolean = matchId in active

    override fun activeRecordings(): Collection<MatchId> = active.keys.toList()

    // ---- Event listeners ----------------------------------------------------

    private fun onDamageDealt(event: DamageDealt) {
        // No matchId in DamageDealt — record into EVERY active buffer whose
        // recorder treats the victim or attacker as a participant. Phase 1 PoC
        // approximation: append to every active recording (cheap when one
        // match runs at a time, the common case).
        if (active.isEmpty()) return
        val replayEvent = ReplayEvent.DamageDealt(
            attackerId = event.attacker,
            victimId = event.target,
            amount = event.resolved.finalAmount,
            sourceKind = event.source.toReplayKind(),
            atTick = event.atTick,
        )
        active.values.forEach { it.appendEvent(replayEvent) }
    }

    private fun onPlayerKilled(event: PlayerKilled) {
        if (active.isEmpty()) return
        val replayEvent = ReplayEvent.PlayerKilled(
            victimId = event.victim,
            killerId = event.killer,
            atTick = event.atTick,
        )
        active.values.forEach { it.appendEvent(replayEvent) }
    }

    private fun onSkillCast(event: SkillCast) {
        if (active.isEmpty()) return
        val replayEvent = ReplayEvent.SkillCast(
            playerId = event.caster,
            skillId = event.skillId,
            atTick = event.atTick,
        )
        active.values.forEach { it.appendEvent(replayEvent) }
    }

    private fun onChatSent(event: ChatMessageSent) {
        if (active.isEmpty()) return
        // ChatMessageSent carries free-text `content`, not a MessageKey. The
        // replay schema keeps MessageKey because Phase 4 playback wants i18n
        // resolution; for free-text chat we synthesise a stable key with the
        // sender's UUID embedded — playback can render it as raw text.
        val stableKey = com.astarworks.astera.core.domain.i18n.MessageKey(
            "astera.match.replay.chat.${event.sender.uuid}",
        )
        val replayEvent = ReplayEvent.ChatSent(
            playerId = event.sender,
            messageKey = stableKey,
            atTick = scheduler.currentTick(),
        )
        active.values.forEach { it.appendEvent(replayEvent) }
    }

    private fun onPhaseChanged(event: PhaseChanged) {
        val buffer = active[event.matchId] ?: return
        val replayEvent = ReplayEvent.MatchPhaseChanged(
            matchId = event.matchId,
            from = event.from,
            to = event.to,
            atTick = event.atTick,
        )
        buffer.appendEvent(replayEvent)
    }

    // ---- Key-frame ticker ---------------------------------------------------

    private fun tickKeyFrames() {
        if (active.isEmpty()) return
        val now = scheduler.currentTick()
        active.forEach { (matchId, buffer) ->
            val period = (buffer.format as? ReplayFormat.EventLogKeyFrame)?.keyFramePer
                ?: (buffer.format as? ReplayFormat.FullPerTick)?.let { 1 }
                ?: ReplayFormat.EventLogKeyFrame.DEFAULT_KEYFRAME_PER
            if ((now - buffer.startTick) % period != 0L) return@forEach
            captureKeyFrame(matchId, buffer, now)
        }
    }

    private fun captureKeyFrame(matchId: MatchId, buffer: RecordingBuffer, tick: Long) {
        val participants = stateProvider.participantsOf(matchId)
        val positions = mutableMapOf<PlayerId, com.astarworks.astera.core.domain.replay.Vec3Snapshot>()
        val healths = mutableMapOf<PlayerId, Double>()
        val items = mutableMapOf<PlayerId, com.astarworks.astera.core.domain.item.ItemId>()
        participants.forEach { pid ->
            stateProvider.positionOf(pid)?.let { positions[pid] = it }
            stateProvider.healthOf(pid)?.let { healths[pid] = it }
            stateProvider.activeItemOf(pid)?.let { items[pid] = it }
        }
        buffer.appendKeyFrame(
            KeyFrame(
                tick = tick,
                playerPositions = positions,
                playerHealth = healths,
                playerActiveItems = items,
            ),
        )
    }

    // ---- Internals ----------------------------------------------------------

    private class RecordingBuffer(
        val replayId: ReplayId,
        val matchId: MatchId,
        val startTick: Long,
        var format: ReplayFormat,
    ) {
        val events: ArrayDeque<ReplayEvent> = ArrayDeque()
        val keyFrames: ArrayDeque<KeyFrame> = ArrayDeque()
        var estimatedSize: Int = 0
            private set
        private var capWarned = false

        fun appendEvent(event: ReplayEvent) {
            val cost = ESTIMATED_EVENT_BYTES
            if (estimatedSize + cost > MAX_BUFFER_BYTES) {
                logCap()
                return
            }
            events.addLast(event)
            estimatedSize += cost
        }

        fun appendKeyFrame(frame: KeyFrame) {
            val cost = ESTIMATED_KEYFRAME_BASE_BYTES +
                frame.playerPositions.size * ESTIMATED_KEYFRAME_PER_PLAYER_BYTES
            if (estimatedSize + cost > MAX_BUFFER_BYTES) {
                logCap()
                return
            }
            keyFrames.addLast(frame)
            estimatedSize += cost
        }

        private fun logCap() {
            if (capWarned) return
            capWarned = true
            LoggerFactory.getLogger(ReplayCaptureEngine::class.java).warn(
                "ReplayCaptureEngine: buffer for match {} hit cap {} bytes; subsequent events dropped",
                matchId, MAX_BUFFER_BYTES,
            )
        }
    }

    public companion object {
        /** Hard byte cap per match recording buffer. */
        public const val MAX_BUFFER_BYTES: Int = 12 * 1024 * 1024

        /** Ticker period for key-frame eligibility check. */
        public const val KEYFRAME_TICKER_PERIOD: Long = 1L

        /** Per-event JSONB cost estimate (avg across the 9 ReplayEvent variants). */
        public const val ESTIMATED_EVENT_BYTES: Int = 48

        /** Per-key-frame fixed overhead estimate. */
        public const val ESTIMATED_KEYFRAME_BASE_BYTES: Int = 32

        /** Additional bytes per participant in a key frame. */
        public const val ESTIMATED_KEYFRAME_PER_PLAYER_BYTES: Int = 64
    }
}

/** Map combat's full `DamageSource` sealed onto the coarser [DamageSourceKind]. */
internal fun com.astarworks.astera.combat.domain.damage.DamageSource.toReplayKind(): DamageSourceKind =
    when (this) {
        is com.astarworks.astera.combat.domain.damage.DamageSource.Environment ->
            when (this) {
                is com.astarworks.astera.combat.domain.damage.DamageSource.Environment.Fall -> DamageSourceKind.FALL
                else -> DamageSourceKind.ENVIRONMENT
            }
        is com.astarworks.astera.combat.domain.damage.DamageSource.Item ->
            DamageSourceKind.ITEM
        is com.astarworks.astera.combat.domain.damage.DamageSource.Skill ->
            DamageSourceKind.SKILL
        is com.astarworks.astera.combat.domain.damage.DamageSource.Explosion ->
            DamageSourceKind.EXPLOSION
        is com.astarworks.astera.combat.domain.damage.DamageSource.StatusEffectTick ->
            DamageSourceKind.STATUS_TICK
        is com.astarworks.astera.combat.domain.damage.DamageSource.Projectile ->
            DamageSourceKind.PROJECTILE
        is com.astarworks.astera.combat.domain.damage.DamageSource.Barrier ->
            DamageSourceKind.BARRIER
        is com.astarworks.astera.combat.domain.damage.DamageSource.Block ->
            DamageSourceKind.BARRIER // closest match; Wave 5x added Block variant
    }

/** Stable string discriminator for [MatchRecordingStarted.formatDescriptor]. */
internal fun ReplayFormat.descriptor(): String = when (this) {
    is ReplayFormat.EventLogKeyFrame -> "event-log-keyframe"
    is ReplayFormat.FullPerTick -> "full-per-tick"
}
