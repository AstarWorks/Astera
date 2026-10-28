package com.astarworks.astera.core.application.skillengine.load

import com.astarworks.astera.core.application.skillengine.load.spec.OpSpec

/**
 * The ONLY list of v3 op specs. Wave 0 lands it EMPTY; the manager appends one line per wave-1 package at merge
 * (append-only). The stub objects already exist under `load/ops/{control,presentation,gameplay}/`:
 * GroupOpSpec, FrameOpSpec, DisplayOpSpec, ParticleOpSpec, ParticleDefOpSpec, SoundOpSpec, RibbonOpSpec, ProjectileOpSpec,
 * HitOpSpec, DamageOpSpec, StatusOpSpec, ModifyAttributeOpSpec, ImpulseOpSpec, KnockbackOpSpec, HealOpSpec, ResourceOpSpec,
 * DashOpSpec, ReelOpSpec, RigOpSpec, EmitOpSpec, LockOpSpec, CancelOpSpec, UseOpSpec, RemoveOpSpec.
 */
public object SkillEngineSpecs {
    public fun all(): List<OpSpec<*>> = listOf(
    )
}
