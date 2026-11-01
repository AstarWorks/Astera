package com.astarworks.astera.match.application.service

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-match runtime state that outlives a Match generation (ADR-0311 rewire carry). Services keep their per-match maps
 * (star clock, norma tallies, siege runs, phase-timer stamps, ...) in named slots of this bag, so a rewired generation
 * keeps counting where the old one stopped instead of resetting what the players see.
 */
public class MatchCarriedState {

    private val slots: MutableMap<String, Any> = ConcurrentHashMap()

    /** The slot called [name], created by [create] on first use and then the very same instance for every generation. */
    @Suppress("UNCHECKED_CAST")
    public fun <T : Any> slot(name: String, create: () -> T): T = slots.computeIfAbsent(name) { create() } as T
}
