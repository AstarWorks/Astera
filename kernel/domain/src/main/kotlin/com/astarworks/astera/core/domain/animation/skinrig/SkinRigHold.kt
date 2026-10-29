package com.astarworks.astera.core.domain.animation.skinrig

/**
 * A stretch where a performance clip stands still: once the clip clock reaches [at] it holds there for [ticks] server ticks
 * (the hit-stop of the rig that owns the clip, so the body and the held part never drift apart).
 */
public data class SkinRigHold(public val at: Int, public val ticks: Int)
