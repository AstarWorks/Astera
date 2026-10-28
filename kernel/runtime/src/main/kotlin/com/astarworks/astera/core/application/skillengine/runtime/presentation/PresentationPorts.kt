package com.astarworks.astera.core.application.skillengine.runtime.presentation

import com.astarworks.astera.core.application.skillengine.runtime.CastId
import com.astarworks.astera.core.domain.skillengine.model.TargetRef
import java.util.UUID

/** Caster body animation (the victim side is `ISkillVictimAnimGateway`). */
public interface ISkillCasterAnimGateway {
    /** Starts [anim] on the caster: a `content/animations` skin-rig clip, or a pose-only clip held for [ticks]. */
    public fun play(caster: UUID, anim: String, speed: Double, ticks: Int)

    /** The skin-rig clip [anim] (`content/animations`), or null when no such clip exists. */
    public fun clip(anim: String): com.astarworks.astera.core.domain.animation.skinrig.SkinRigAnimation?

    /** Whether the caster's skin is the slim (Alex) model: the skin rig is built for it. */
    public fun isSlim(caster: UUID): Boolean

    /** Blends [anim] out now (an `until:` event fired or the cast was cancelled); a no-op if it is not running. */
    public fun stop(caster: UUID, anim: String)

    /** The cast ended normally: a clip that loops must not outlive it, a clip with its own length finishes by itself. */
    public fun release(caster: UUID, anim: String)
}

/** Viewer-screen effects: titles, action bar, camera shake. Always addressed to concrete players. */
public interface ISkillScreenGateway {
    public fun title(viewer: UUID, text: String, subtitle: String?, ticks: Int)
    public fun actionBar(viewer: UUID, text: String)
    public fun cameraShake(viewer: UUID, intensity: Double, ticks: Int)
}

/**
 * What presentation ops need to know about the cast that `OpContext` does not expose (contract gap,
 * reported to the manager): who the caster is, who a [TargetRef] resolves to right now, and whether
 * an `until:` event has been raised. The cast runner implements this over its own state.
 */
public interface PresentationCastInfo {
    public fun owner(cast: CastId): UUID

    /** Entities a target reference resolves to at the moment of the call (`Victim` = current contact). */
    public fun targets(cast: CastId, target: TargetRef): List<UUID>

    public fun hasRaised(cast: CastId, event: String): Boolean
}
