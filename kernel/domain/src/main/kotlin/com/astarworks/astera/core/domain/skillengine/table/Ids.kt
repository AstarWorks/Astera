package com.astarworks.astera.core.domain.skillengine.table

/** The author's `id` of an op. */
@JvmInline
public value class OpId(public val value: String)

/** "cast", "g/windup", "volley[2]/ball.hit/splash/burst". */
@JvmInline
public value class StatePath(public val value: String)

/** "volley[k]/ball.hit", "g.parried", "cancel", "end". */
@JvmInline
public value class EventPath(public val value: String)

/** An instance of an op after unrolling: `volley[2]/ball`, `volley[2]/trail[7]`, `g/guard_plate`. */
public data class InstanceId(val path: List<Segment>) {
    /** [index] = occurrence `i` when the op repeats / is a family. */
    public data class Segment(val op: OpId, val index: Int?)

    public val text: String get() = path.joinToString("/") { s -> s.op.value + (s.index?.let { "[$it]" } ?: "") }
}

public data class TickRange(val min: Int, val max: Int) {
    init {
        require(min in 0..max) { "bad tick range [$min, $max]" }
    }
}

public sealed interface Duration {
    public data class Fixed(val ticks: Int) : Duration
    public data class Bounded(val range: TickRange) : Duration
}
