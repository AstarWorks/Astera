package com.astarworks.astera.core.application.skillengine.runtime.player

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** What kind of thing a runtime fault was (the count key next to the skill id). */
public enum class FaultKind {
    /** The whole cast was aborted by a throw in the player. */
    ABORT,

    /** A server cap swept a cast / instance or cut a cascade (`maxCastTicks`, `maxLifeTicks`, `sameTickDepth`). */
    LIMIT,

    /** A guard could not be evaluated. */
    GUARD,

    /** A gateway / hook the row needs is not there (no lock sink, no display sink, no handler). */
    UNSUPPORTED,

    /** A dash driver fault. */
    DASH,

    /** A world op the world refused (`place_shape` outside an arena). */
    WORLD,

    /** Any other row-level fault (bad frame, wrong display kind, ...). */
    ROW,
}

/** Counted faults are keyed by the skill that ran and the [kind]. */
public data class SkillFaultKey(val skill: String, val kind: FaultKind)

/** Read side of the fault counters (no Bukkit): e2e and game-sim assert "0 faults after the run" through it. */
public interface ISkillFaultReader {
    /** Every fault counted since start (or the last [SkillFaultLedger.reset]). */
    public fun total(): Int

    /** Faults counted per skill id and kind, only non-zero entries. */
    public fun counts(): Map<SkillFaultKey, Int>
}

/** Thread-safe fault counters; the player writes, readers (commands, tests) read. */
public class SkillFaultLedger : ISkillFaultReader {
    private val counts = ConcurrentHashMap<SkillFaultKey, AtomicInteger>()

    public fun record(skill: String, kind: FaultKind) {
        counts.computeIfAbsent(SkillFaultKey(skill, kind)) { AtomicInteger() }.incrementAndGet()
    }

    override fun total(): Int = counts.values.sumOf { it.get() }

    override fun counts(): Map<SkillFaultKey, Int> = counts.mapValues { it.value.get() }.filterValues { it > 0 }

    public fun reset() {
        counts.clear()
    }
}
