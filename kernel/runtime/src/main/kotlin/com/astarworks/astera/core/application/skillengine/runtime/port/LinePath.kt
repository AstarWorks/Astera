package com.astarworks.astera.core.application.skillengine.runtime.port

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.table.InstanceId
import com.astarworks.astera.core.domain.skillengine.table.Row

/**
 * The vertices of a polyline line ribbon (`ribbon` `from`/`to` with `segments` N, ADR-0303 rev4.14): `v_0 = from`, `v_N = to` exactly, every inner vertex
 * is `from + (to - from) * (k/N + a_k) + u * s_k + w * t_k` with `u`, `w` the two unit vectors perpendicular to the line (built from its direction alone,
 * never from the eye (see [LineBasis]), so the bolt does not swim when the camera moves). `s_k`, `t_k` in `[-spread, spread]`, `a_k` in `[-along, along] / N`.
 *
 * The offsets are visual randomness: a pure function of (row seed, [epoch], k, axis). The segment count, the epochs and the end are table facts; only the
 * displacement of the inner vertices is drawn. Sim and Paper both call this, so the goldens and the client agree byte for byte.
 */
public object LinePath {
    public fun vertices(from: Vec3, to: Vec3, segments: Int, spread: Double, along: Double, seed: Long, epoch: Int): List<Vec3> {
        require(segments >= 1) { "a line has at least one segment" }
        if (segments == 1) return listOf(from, to)
        val span = to - from
        val basis = LineBasis.of(from, to)
        val u = basis.u
        val w = basis.w
        return List(segments + 1) { k ->
            when (k) {
                0 -> from
                segments -> to
                else -> {
                    val a = unit(seed, epoch, k, 0) * along / segments
                    val s = unit(seed, epoch, k, 1) * spread
                    val t = unit(seed, epoch, k, 2) * spread
                    from + span * (k.toDouble() / segments + a) + u * s + w * t
                }
            }
        }
    }

    /** A value in [-1, 1] from (seed, epoch, vertex, axis): splitmix64 over the packed key. */
    private fun unit(seed: Long, epoch: Int, k: Int, axis: Int): Double {
        var z = seed + (epoch.toLong() * 0x632BE59BD9B4E019L) + (k.toLong() * 0x9E3779B97F4A7C15uL.toLong()) + axis.toLong() * 0x1B873593L
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        z = z xor (z ushr 31)
        return ((z ushr 11).toDouble() / (1L shl 53).toDouble()) * 2.0 - 1.0
    }

    /** True when [row] is the spawn row a `jitter.follow: <id>` written on [follower] names: a sibling op (same enclosing path) with the same copy index, or the unindexed one. */
    public fun isFollowTarget(follower: InstanceId, followId: String, row: Row): Boolean {
        val p = row.instance.path
        val f = follower.path
        if (p.size != f.size || p.dropLast(1) != f.dropLast(1)) return false
        val last = p.last()
        return last.op.value == followId && (last.index == f.last().index || last.index == null)
    }
}
