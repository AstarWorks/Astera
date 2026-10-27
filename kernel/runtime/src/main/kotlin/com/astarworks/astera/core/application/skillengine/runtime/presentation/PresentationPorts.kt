package com.astarworks.astera.core.application.skillengine.runtime.presentation

import com.astarworks.astera.core.application.skillengine.runtime.CastId
import com.astarworks.astera.core.domain.skillengine.model.TargetRef
import java.util.UUID

/** Caster body animation (the victim side is `ISkillVictimAnimGateway`). */
public interface ISkillCasterAnimGateway {
    public fun play(caster: UUID, anim: String, speed: Double)
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
