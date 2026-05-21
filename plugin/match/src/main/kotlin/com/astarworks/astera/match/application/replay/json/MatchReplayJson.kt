package com.astarworks.astera.match.application.replay.json

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.replay.DamageSourceKind
import com.astarworks.astera.core.domain.replay.KeyFrame
import com.astarworks.astera.core.domain.replay.RecordedReplay
import com.astarworks.astera.core.domain.replay.ReplayEvent
import com.astarworks.astera.core.domain.replay.ReplayFormat
import com.astarworks.astera.core.domain.replay.ReplayId
import com.astarworks.astera.core.domain.replay.Vec3Snapshot
import com.astarworks.astera.core.domain.skill.SkillId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * JSONB mirror of [ReplayFormat] for the `match_replays.format` column
 * (Wave 5v, ADR-0088).
 *
 * Sealed-by-key (`type: event-log-keyframe | full-per-tick`) per
 * Astera's standing JSONB convention (`PersonalVaultJson` /
 * `PlayerSettingsJson`). Lenient decoder ignores unknown keys so additive
 * fields don't break older rows.
 */
@Serializable
public sealed interface ReplayFormatJson {

    @Serializable
    @SerialName("event-log-keyframe")
    public data class EventLogKeyFrame(
        val keyFramePer: Int,
        val interpolateMovement: Boolean,
    ) : ReplayFormatJson

    @Serializable
    @SerialName("full-per-tick")
    public data class FullPerTick(
        val maxDurationTicks: Int,
    ) : ReplayFormatJson

    public fun toDomain(): ReplayFormat = when (this) {
        is EventLogKeyFrame -> ReplayFormat.EventLogKeyFrame(
            keyFramePer = keyFramePer,
            interpolateMovement = interpolateMovement,
        )
        is FullPerTick -> ReplayFormat.FullPerTick(maxDurationTicks = maxDurationTicks)
    }

    public companion object {
        public fun fromDomain(format: ReplayFormat): ReplayFormatJson = when (format) {
            is ReplayFormat.EventLogKeyFrame -> EventLogKeyFrame(
                keyFramePer = format.keyFramePer,
                interpolateMovement = format.interpolateMovement,
            )
            is ReplayFormat.FullPerTick -> FullPerTick(maxDurationTicks = format.maxDurationTicks)
        }
    }
}

@Serializable
public data class Vec3SnapshotJson(val x: Float, val y: Float, val z: Float) {
    public fun toDomain(): Vec3Snapshot = Vec3Snapshot(x, y, z)
    public companion object {
        public fun fromDomain(v: Vec3Snapshot): Vec3SnapshotJson = Vec3SnapshotJson(v.x, v.y, v.z)
    }
}

@Serializable
public data class KeyFrameJson(
    val tick: Long,
    val positions: Map<String, Vec3SnapshotJson>,
    val health: Map<String, Double>,
    val items: Map<String, String>,
) {
    public fun toDomain(): KeyFrame = KeyFrame(
        tick = tick,
        playerPositions = positions.mapKeys { (k, _) -> PlayerId(UUID.fromString(k)) }
            .mapValues { (_, v) -> v.toDomain() },
        playerHealth = health.mapKeys { (k, _) -> PlayerId(UUID.fromString(k)) },
        playerActiveItems = items.mapKeys { (k, _) -> PlayerId(UUID.fromString(k)) }
            .mapValues { (_, v) -> ItemId(v) },
    )

    public companion object {
        public fun fromDomain(frame: KeyFrame): KeyFrameJson = KeyFrameJson(
            tick = frame.tick,
            positions = frame.playerPositions.mapKeys { (k, _) -> k.uuid.toString() }
                .mapValues { (_, v) -> Vec3SnapshotJson.fromDomain(v) },
            health = frame.playerHealth.mapKeys { (k, _) -> k.uuid.toString() },
            items = frame.playerActiveItems.mapKeys { (k, _) -> k.uuid.toString() }
                .mapValues { (_, v) -> v.value },
        )
    }
}

/**
 * Sealed-by-key JSONB mirror of [ReplayEvent]. One variant per source
 * variant; the keys (`player-moved`, `damage-dealt`, …) MUST stay stable
 * across waves because old `match_replays.events` rows reference them.
 *
 * Phase 1 PoC posture: if a variant needs to be renamed / restructured,
 * the safer move is to ship a fresh `KeyFrameJson` schema (incrementing
 * `version` on the domain `MatchRecordingStarted` event); we do NOT
 * write dual-decoders. See `CLAUDE.md §"Pre-release posture"` and
 * coding-posture rules.
 */
@Serializable
public sealed interface ReplayEventJson {

    public val atTick: Long

    @Serializable
    @SerialName("player-moved")
    public data class PlayerMoved(
        val playerId: String,
        val deltaX: Float,
        val deltaY: Float,
        val deltaZ: Float,
        override val atTick: Long,
    ) : ReplayEventJson

    @Serializable
    @SerialName("damage-dealt")
    public data class DamageDealt(
        val attackerId: String?,
        val victimId: String,
        val amount: Double,
        val sourceKind: String,
        override val atTick: Long,
    ) : ReplayEventJson

    @Serializable
    @SerialName("player-killed")
    public data class PlayerKilled(
        val victimId: String,
        val killerId: String?,
        override val atTick: Long,
    ) : ReplayEventJson

    @Serializable
    @SerialName("player-respawned")
    public data class PlayerRespawned(
        val playerId: String,
        override val atTick: Long,
    ) : ReplayEventJson

    @Serializable
    @SerialName("item-used")
    public data class ItemUsed(
        val playerId: String,
        val itemId: String,
        override val atTick: Long,
    ) : ReplayEventJson

    @Serializable
    @SerialName("skill-cast")
    public data class SkillCast(
        val playerId: String,
        val skillId: String,
        override val atTick: Long,
    ) : ReplayEventJson

    @Serializable
    @SerialName("chat-sent")
    public data class ChatSent(
        val playerId: String,
        val messageKey: String,
        override val atTick: Long,
    ) : ReplayEventJson

    @Serializable
    @SerialName("match-phase-changed")
    public data class MatchPhaseChanged(
        val matchId: String,
        val from: String,
        val to: String,
        override val atTick: Long,
    ) : ReplayEventJson

    @Serializable
    @SerialName("pillar-captured")
    public data class PillarCaptured(
        val matchId: String,
        val pillarId: String,
        val team: String,
        override val atTick: Long,
    ) : ReplayEventJson

    public fun toDomain(): ReplayEvent = when (this) {
        is PlayerMoved -> ReplayEvent.PlayerMoved(
            playerId = PlayerId(UUID.fromString(playerId)),
            deltaX = deltaX, deltaY = deltaY, deltaZ = deltaZ,
            atTick = atTick,
        )
        is DamageDealt -> ReplayEvent.DamageDealt(
            attackerId = attackerId?.let { PlayerId(UUID.fromString(it)) },
            victimId = PlayerId(UUID.fromString(victimId)),
            amount = amount,
            sourceKind = DamageSourceKind.valueOf(sourceKind),
            atTick = atTick,
        )
        is PlayerKilled -> ReplayEvent.PlayerKilled(
            victimId = PlayerId(UUID.fromString(victimId)),
            killerId = killerId?.let { PlayerId(UUID.fromString(it)) },
            atTick = atTick,
        )
        is PlayerRespawned -> ReplayEvent.PlayerRespawned(
            playerId = PlayerId(UUID.fromString(playerId)),
            atTick = atTick,
        )
        is ItemUsed -> ReplayEvent.ItemUsed(
            playerId = PlayerId(UUID.fromString(playerId)),
            itemId = ItemId(itemId),
            atTick = atTick,
        )
        is SkillCast -> ReplayEvent.SkillCast(
            playerId = PlayerId(UUID.fromString(playerId)),
            skillId = SkillId(skillId),
            atTick = atTick,
        )
        is ChatSent -> ReplayEvent.ChatSent(
            playerId = PlayerId(UUID.fromString(playerId)),
            messageKey = MessageKey(messageKey),
            atTick = atTick,
        )
        is MatchPhaseChanged -> ReplayEvent.MatchPhaseChanged(
            matchId = MatchId(matchId),
            from = MatchPhase.valueOf(from),
            to = MatchPhase.valueOf(to),
            atTick = atTick,
        )
        is PillarCaptured -> ReplayEvent.PillarCaptured(
            matchId = MatchId(matchId),
            pillarId = pillarId,
            team = TeamId(team),
            atTick = atTick,
        )
    }

    public companion object {
        public fun fromDomain(event: ReplayEvent): ReplayEventJson = when (event) {
            is ReplayEvent.PlayerMoved -> PlayerMoved(
                playerId = event.playerId.uuid.toString(),
                deltaX = event.deltaX, deltaY = event.deltaY, deltaZ = event.deltaZ,
                atTick = event.atTick,
            )
            is ReplayEvent.DamageDealt -> DamageDealt(
                attackerId = event.attackerId?.uuid?.toString(),
                victimId = event.victimId.uuid.toString(),
                amount = event.amount,
                sourceKind = event.sourceKind.name,
                atTick = event.atTick,
            )
            is ReplayEvent.PlayerKilled -> PlayerKilled(
                victimId = event.victimId.uuid.toString(),
                killerId = event.killerId?.uuid?.toString(),
                atTick = event.atTick,
            )
            is ReplayEvent.PlayerRespawned -> PlayerRespawned(
                playerId = event.playerId.uuid.toString(),
                atTick = event.atTick,
            )
            is ReplayEvent.ItemUsed -> ItemUsed(
                playerId = event.playerId.uuid.toString(),
                itemId = event.itemId.value,
                atTick = event.atTick,
            )
            is ReplayEvent.SkillCast -> SkillCast(
                playerId = event.playerId.uuid.toString(),
                skillId = event.skillId.value,
                atTick = event.atTick,
            )
            is ReplayEvent.ChatSent -> ChatSent(
                playerId = event.playerId.uuid.toString(),
                messageKey = event.messageKey.value,
                atTick = event.atTick,
            )
            is ReplayEvent.MatchPhaseChanged -> MatchPhaseChanged(
                matchId = event.matchId.value,
                from = event.from.name,
                to = event.to.name,
                atTick = event.atTick,
            )
            is ReplayEvent.PillarCaptured -> PillarCaptured(
                matchId = event.matchId.value,
                pillarId = event.pillarId,
                team = event.team.value,
                atTick = event.atTick,
            )
        }
    }
}

/**
 * Wraps `List<KeyFrame>` for the `key_frames` column. Wrapper allows the
 * Postgres column to be a JSON object `{"frames": [...]}` rather than a
 * bare array — convention chosen for forward-compat (additive fields
 * possible at the wrapper level without invalidating decoders).
 */
@Serializable
public data class KeyFramesJson(val frames: List<KeyFrameJson>) {
    public fun toDomain(): List<KeyFrame> = frames.map { it.toDomain() }
    public companion object {
        public fun fromDomain(frames: List<KeyFrame>): KeyFramesJson =
            KeyFramesJson(frames.map { KeyFrameJson.fromDomain(it) })
    }
}

/** Same wrapping rationale as [KeyFramesJson]. */
@Serializable
public data class ReplayEventsJson(val events: List<ReplayEventJson>) {
    public fun toDomain(): List<ReplayEvent> = events.map { it.toDomain() }
    public companion object {
        public fun fromDomain(events: List<ReplayEvent>): ReplayEventsJson =
            ReplayEventsJson(events.map { ReplayEventJson.fromDomain(it) })
    }
}

/**
 * Convenience round-tripper: reconstruct a full [RecordedReplay] from the
 * three JSONB blobs + columnar fields read off the row.
 */
public fun assembleRecordedReplay(
    replayId: ReplayId,
    matchId: MatchId,
    startTick: Long,
    endTick: Long,
    sizeBytes: Int,
    formatJson: ReplayFormatJson,
    keyFramesJson: KeyFramesJson,
    eventsJson: ReplayEventsJson,
): RecordedReplay = RecordedReplay(
    id = replayId,
    matchId = matchId,
    startTick = startTick,
    endTick = endTick,
    format = formatJson.toDomain(),
    events = eventsJson.toDomain(),
    keyFrames = keyFramesJson.toDomain(),
    sizeBytes = sizeBytes,
)
