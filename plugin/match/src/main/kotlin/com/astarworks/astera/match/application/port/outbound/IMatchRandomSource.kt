package com.astarworks.astera.match.application.port.outbound

import kotlin.random.Random

/**
 * Source of the random streams plugin/match services draw from. A host that needs
 * reproducible matches (the simulator) registers one that derives every stream from its seed and
 * the stream [name], so adding a service never shifts the others; the Paper wiring falls back to
 * [Random.Default].
 */
public fun interface IMatchRandomSource {
    public fun stream(name: String): Random
}
