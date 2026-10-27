package com.astarworks.astera.core.domain.skillengine.op

import com.astarworks.astera.core.domain.skillengine.model.TargetRef
import com.astarworks.astera.core.domain.skillengine.model.Value

/** Status effects, buffs, attributes, resources. Family owner: status/buff worker. */
public sealed interface StatusOp : GameplayOp {

    public data class ApplyStatus(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val target: TargetRef,
        val statusId: String,
        val stacks: Value<Double>,
        val durationTicks: Value<Double>,
        val chance: Value<Double>?,
    ) : StatusOp

    public data class RemoveStatus(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val target: TargetRef,
        val statusId: String,
    ) : StatusOp

    public data class Cleanse(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val target: TargetRef,
        val negativeOnly: Boolean,
    ) : StatusOp

    public data class Ignite(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val target: TargetRef,
        val durationTicks: Value<Double>,
    ) : StatusOp

    public data class Silence(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val target: TargetRef,
        val durationTicks: Value<Double>,
    ) : StatusOp

    public data class ModifyAttribute(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val target: TargetRef,
        val attribute: String,
        val operation: String,
        val amount: Value<Double>,
        val durationTicks: Value<Double>?,
    ) : StatusOp

    public data class RestoreResource(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val target: TargetRef,
        val resource: String,
        val amount: Value<Double>,
    ) : StatusOp
}
