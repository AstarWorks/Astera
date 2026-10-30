package com.astarworks.astera.core.domain.skillengine.model

/** Server-side caps of the skill engine (ADR-0303 11.4, skeleton section 5). Every number is printed by `expand`. */
public data class EngineConfig(
    val maxLiveDisplays: Int = 64,
    val maxInstancesPerOp: Int = 256,
    val maxGroupDepth: Int = 8,
    val maxPartDepth: Int = 6,
    val maxLifeTicks: Int = 1200,
    val maxCastTicks: Int = 1200,
    val segmentErrorBlocks: Double = 0.03,
    val segmentErrorDeg: Double = 2.0,
    val maxSegmentTicks: Int = 59,
    val splitTurnDeg: Double = 170.0,
    val maxSameTickDepth: Int = 4,
    /** Hit sweep samples per tick: `min(maxSweepSteps, ceil(travel / radius) + 1)` (ADR-0303 10.2). */
    val maxSweepSteps: Int = 32,
    /** Where `expand` writes its dump (skill-compare mounts this path). */
    val expandDir: String = "build/skill-expand",
    /** Caster-view clutter caps (load-time check 16). */
    val casterEye: CasterEyeCaps = CasterEyeCaps(),
)
