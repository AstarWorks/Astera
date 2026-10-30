package com.astarworks.astera.core.domain.skillengine.model.drycast

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.table.SrcPath

/** One particle burst of a dry cast: [particle] is the id the row named (a content definition or a vanilla type), [count] null = the definition's own. */
public data class ParticleSample(
    val tick: Int,
    val particle: String,
    val pos: Vec3,
    val count: Int?,
    val size: Double?,
    val offset: Vec3,
    val speed: Double,
    val look: Vec3,
    val src: SrcPath?,
)

/** One display event of a dry cast: spawn ([kind] `block` / `item` / ...), a pose write, or removal ([alive] = false). */
public data class DisplaySample(
    val tick: Int,
    val id: Long,
    val kind: String,
    val pos: Vec3?,
    val scale: Vec3?,
    val alive: Boolean,
    val spawn: Boolean,
    val src: SrcPath?,
)

/** What the skill did at the caster's gateways in one pose: the input of the clutter model. [endTick] = the last tick that was played. */
public data class DryCastTrace(
    val particles: List<ParticleSample>,
    val displays: List<DisplaySample>,
    val endTick: Int,
)
