package com.astarworks.astera.bots

import java.util.Random

/**
 * Deterministic tie-breaking for bot choices. A tie between equally good options (two enemy teams with the same pillars
 * standing, two players at the same score) is never decided by name or roster order: the order of names would make every
 * bot pick the same alphabetical neighbour, so a whole match could leave one team untouched. Ties go to the team that most
 * recently damaged us, then to a number seeded from a match key and a bot or team key (no unseeded randomness: the same
 * match replays the same way).
 */
object TieBreak {
    /** A stable 64-bit seed from [parts] (FNV-1a over the UTF-16 units, then a splitmix64 finish). */
    fun seed(vararg parts: String): Long {
        var h = -0x340d631b7bdddcdbL
        for (p in parts) {
            for (ch in p) h = (h xor ch.code.toLong()) * 0x100000001b3L
            h = (h xor 0x1fL) * 0x100000001b3L
        }
        return finish(h)
    }

    /** A per-[key] number derived from [seed]: equal seed and key give equal numbers, different keys unrelated ones. */
    fun priority(seed: Long, key: String): Long = finish(seed xor seed(key))

    fun stream(seed: Long): Random = Random(seed)

    private fun finish(z0: Long): Long {
        var z = z0 + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }
}
