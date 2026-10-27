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

    public data class CameraShake(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val target: TargetRef,
        val intensity: Value<Double>,
        val ticks: Int,
    ) : ScreenOp
}
