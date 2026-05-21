package com.astarworks.astera.core.domain.replay

/**
 * Capture-time configuration for [com.astarworks.astera.match.application
 * .port.inbound.IMatchRecorder] (Wave 5v, ADR-0088).
 *
 * Two encodings are committed. [EventLogKeyFrame] is the default and the
 * shape Wave 5v's 4×1h≈5MB budget assumes; [FullPerTick] exists for
 * debug / forensic captures where per-tick state is required and the size
 * cost is acceptable.
 */
public sealed interface ReplayFormat {

    /**
     * Sparse event-log + periodic key frames (Wave 5v default).
     *
     * @property keyFramePer interval (in ticks) between full state
     *   snapshots. Default 100 ticks = 5 seconds at 20 TPS — fine
     *   enough that interpolation between snapshots stays visually
     *   accurate for typical PvP movement, large enough that a 1-hour
     *   recording yields only ~720 [KeyFrame]s.
     * @property interpolateMovement if true, [ReplayEvent.PlayerMoved]
     *   events are delta-encoded and a playback client linearly
     *   interpolates between them; if false, the recorder emits
     *   absolute-position events but only at key-frame boundaries
     *   (smaller events, choppier playback).
     */
    public data class EventLogKeyFrame(
        val keyFramePer: Int = DEFAULT_KEYFRAME_PER,
        val interpolateMovement: Boolean = true,
    ) : ReplayFormat {
        init {
            require(keyFramePer in 1..MAX_KEYFRAME_PER) {
                "keyFramePer must be in 1..$MAX_KEYFRAME_PER ticks (got $keyFramePer)"
            }
        }

        public companion object {
            public const val DEFAULT_KEYFRAME_PER: Int = 100
            public const val MAX_KEYFRAME_PER: Int = 1200
        }
    }

    /**
     * Per-tick state dump (no event log; every tick is a full snapshot).
     *
     * Use for short forensic captures only — file size grows ~linearly
     * with [maxDurationTicks] × player count. 24000 ticks = 20 minutes
     * at 20 TPS; the recorder hard-stops at that cap to keep the
     * Postgres row bounded (~5MB at 4 players).
     */
    public data class FullPerTick(
        val maxDurationTicks: Int = DEFAULT_MAX_DURATION_TICKS,
    ) : ReplayFormat {
        init {
            require(maxDurationTicks in 1..HARD_CAP_TICKS) {
                "maxDurationTicks must be in 1..$HARD_CAP_TICKS (got $maxDurationTicks)"
            }
        }

        public companion object {
            public const val DEFAULT_MAX_DURATION_TICKS: Int = 24000
            public const val HARD_CAP_TICKS: Int = 72000
        }
    }
}
