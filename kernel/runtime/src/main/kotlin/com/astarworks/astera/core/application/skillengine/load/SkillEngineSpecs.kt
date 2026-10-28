package com.astarworks.astera.core.application.skillengine.load

import com.astarworks.astera.core.application.skillengine.load.ops.control.CancelOpSpec
import com.astarworks.astera.core.application.skillengine.load.ops.control.GroupOpSpec
import com.astarworks.astera.core.application.skillengine.load.ops.control.LockOpSpec
import com.astarworks.astera.core.application.skillengine.load.ops.control.UseOpSpec
import com.astarworks.astera.core.application.skillengine.load.ops.gameplay.DamageOpSpec
import com.astarworks.astera.core.application.skillengine.load.ops.gameplay.DashOpSpec
import com.astarworks.astera.core.application.skillengine.load.ops.gameplay.EmitOpSpec
import com.astarworks.astera.core.application.skillengine.load.ops.gameplay.HealOpSpec
import com.astarworks.astera.core.application.skillengine.load.ops.gameplay.HitOpSpec
import com.astarworks.astera.core.application.skillengine.load.ops.gameplay.ImpulseOpSpec
import com.astarworks.astera.core.application.skillengine.load.ops.gameplay.KnockbackOpSpec
import com.astarworks.astera.core.application.skillengine.load.ops.gameplay.ModifyAttributeOpSpec
import com.astarworks.astera.core.application.skillengine.load.ops.gameplay.ProjectileOpSpec
import com.astarworks.astera.core.application.skillengine.load.ops.gameplay.ReelOpSpec
import com.astarworks.astera.core.application.skillengine.load.ops.gameplay.RemoveOpSpec
import com.astarworks.astera.core.application.skillengine.load.ops.gameplay.ResourceOpSpec
import com.astarworks.astera.core.application.skillengine.load.ops.gameplay.RigOpSpec
import com.astarworks.astera.core.application.skillengine.load.ops.gameplay.StatusOpSpec
import com.astarworks.astera.core.application.skillengine.load.ops.presentation.DisplayOpSpec
import com.astarworks.astera.core.application.skillengine.load.ops.presentation.FrameOpSpec
import com.astarworks.astera.core.application.skillengine.load.ops.presentation.ParticleDefOpSpec
import com.astarworks.astera.core.application.skillengine.load.ops.presentation.ParticleOpSpec
import com.astarworks.astera.core.application.skillengine.load.ops.presentation.RibbonOpSpec
import com.astarworks.astera.core.application.skillengine.load.ops.presentation.SoundOpSpec
import com.astarworks.astera.core.application.skillengine.load.ops.world.BreakAreaOpSpec
import com.astarworks.astera.core.application.skillengine.load.ops.world.ExplodeOpSpec
import com.astarworks.astera.core.application.skillengine.load.ops.world.LightningOpSpec
import com.astarworks.astera.core.application.skillengine.load.ops.world.PlaceShapeOpSpec
import com.astarworks.astera.core.application.skillengine.load.spec.OpSpec

/**
 * The ONLY list of v3 op specs (ADR-0303). Append one line per new op; the decoder, the compiler, the schema generator and
 * `ComposeOptions.presentationOps` all read this list, so an op that is not here does not exist.
 */
public object SkillEngineSpecs {
    public fun all(): List<OpSpec<*>> = listOf(
        GroupOpSpec,
        LockOpSpec,
        CancelOpSpec,
        UseOpSpec,
        FrameOpSpec,
        DisplayOpSpec,
        ParticleOpSpec,
        ParticleDefOpSpec,
        SoundOpSpec,
        RibbonOpSpec,
        ProjectileOpSpec,
        HitOpSpec,
        DamageOpSpec,
        StatusOpSpec,
        ModifyAttributeOpSpec,
        ImpulseOpSpec,
        KnockbackOpSpec,
        HealOpSpec,
        ResourceOpSpec,
        DashOpSpec,
        ReelOpSpec,
        RigOpSpec,
        EmitOpSpec,
        RemoveOpSpec,
        BreakAreaOpSpec,
        PlaceShapeOpSpec,
        ExplodeOpSpec,
        LightningOpSpec,
    )
}
