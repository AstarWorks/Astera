package com.astarworks.astera.core.domain.skillengine.op

import com.astarworks.astera.core.domain.skillengine.model.TargetRef
import com.astarworks.astera.core.domain.skillengine.model.Value

/** Viewer-screen effects. Family owner: screen worker. */
public sealed interface ScreenOp : PresentationOp {

    public data class Title(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val target: TargetRef,
        val text: String,
        val subtitle: String?,
        val ticks: Int,
    ) : ScreenOp

    public data class ActionBar(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val target: TargetRef,
        val text: String,
    ) : ScreenOp

    /**
     * Full-screen cut-in: [overlay] (a `ScreenOverlay` yaml name) tinted [colorRgb] (null = the overlay's own colour), shown
     * as a title so it fades in / holds / fades out over the given ticks. Vanilla: `Player.showTitle` with the overlay font glyph.
     */
    public data class Flash(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val target: TargetRef,
        val overlay: String,
        val colorRgb: Int?,
        val fadeIn: Int,
        val stay: Int,
        val fadeOut: Int,
    ) : ScreenOp

    /** Stops the named sound for the target players (vanilla `Player.stopSound`). */
    public data class StopSound(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val target: TargetRef,
        val sound: String,
    ) : ScreenOp

    public data class CameraShake(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val target: TargetRef,
        val intensity: Value<Double>,
        val ticks: Int,
    ) : ScreenOp
}
