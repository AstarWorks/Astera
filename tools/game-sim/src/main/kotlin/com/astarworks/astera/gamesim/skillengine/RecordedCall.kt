package com.astarworks.astera.gamesim.skillengine

import java.util.Locale

/**
 * One gateway call as the in-memory recorder saw it, or as the table says it will happen (A2 diffs the two).
 *
 * [verb] is the gateway verb: `spawn`, `write`, `trail`, `remove`, `particle`, `sound`, plus the target-dependent
 * `damage` / `status` / `effect` (recorded only when the scripted query returns victims). [instance] is the ordinal of the
 * thing the gateway handed out (`#0`, `#1`, ... in spawn order), `-` when the verb has no instance. [payload] is a space
 * separated `key=value` list; the expected side lists only the fields the table fixes at compile time, the recorded side
 * lists everything the gateway received, so a match is "expected tokens are a subset of recorded tokens".
 */
data class RecordedCall(val tick: Int, val verb: String, val instance: String, val payload: String) {
    /**
     * Extra `key=value` tokens only the parity oracle reads (poses, colours, positions). Outside the constructor on purpose: it is
     * part of neither equality nor [toString], so every other scenario sees the same call it always saw.
     */
    var detail: String = ""

    /** The op instance that issued this call when the gateway call itself names none (particle, sound, hit volume); null = take it from [instance]. */
    var op: String? = null

    val tokens: Set<String> get() = payload.split(' ').filter { it.isNotEmpty() }.toSet()

    override fun toString(): String = "@$tick $verb $instance $payload".trimEnd()

    companion object {
        /** Verbs that exist only on one side of the diff and are therefore never compared (lock/unlock until W1-21 lands them). */
        val uncompared: Set<String> = setOf("lock", "unlock")

        fun num(d: Double): String =
            if (d == Math.rint(d) && kotlin.math.abs(d) < 1e9) d.toLong().toString()
            else "%.4f".format(Locale.ROOT, d).trimEnd('0').trimEnd('.')
    }
}
