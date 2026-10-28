package com.astarworks.astera.core.application.skillengine.compose

/** A sprite's natural size in pixels. */
public data class Vec2Px(val w: Int, val h: Int)

/**
 * The sprites the lowering may size against (ADR-0303 2.1): `size` (blocks) divided by the natural size gives the vanilla
 * `scale`, so the gateway computes nothing (C1). An unknown sprite is `UndefinedReference` at the op, never a guessed size.
 */
public interface SpriteCatalog {
    /** Natural size of [id], or null when the sprite does not exist. */
    public fun natural(id: String): Vec2Px?
}
