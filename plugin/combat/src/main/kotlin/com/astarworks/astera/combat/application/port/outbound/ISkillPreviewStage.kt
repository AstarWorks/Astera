package com.astarworks.astera.combat.application.port.outbound

import com.astarworks.astera.combat.application.preview.ViewPoint
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerPose
import com.astarworks.astera.core.domain.world.WorldId

/** Where the preview dummies stand and what the caster holds ([weaponMaterial] is a vanilla material key). */
public data class PreviewStageSpec(
    public val world: WorldId,
    public val casterAt: Vec3,
    public val targetAt: Vec3,
    public val weaponMaterial: String,
)

/** Live preview dummies; ids are entity ids (a caster id is usable wherever a [PlayerId] caster is). */
public data class PreviewStage(
    public val caster: PlayerId,
    public val target: PlayerId,
    public val spec: PreviewStageSpec,
)

/**
 * Outbound port: the bodies and the viewer camera of `/astera skill preview`. Main thread only.
 */
public interface ISkillPreviewStage {
    /** Spawns the player-like caster (holding the weapon) and the target dummy; `null` while the world is not loaded. */
    public fun spawn(spec: PreviewStageSpec): PreviewStage?

    /** Resets the target dummy's health so a long skill never kills it mid-preview. */
    public fun healTarget(stage: PreviewStage)

    public fun despawn(stage: PreviewStage)

    /** Remembers the viewer's gamemode + location (first call only), then spectates from [point]. */
    public fun placeViewer(viewer: PlayerId, point: ViewPoint)

    /** Puts the viewer's gamemode + location back; a no-op when nothing was saved. */
    public fun restoreViewer(viewer: PlayerId)

    /** Called when a viewer with saved state leaves the server (state is restored first). */
    public fun onViewerQuit(handler: (PlayerId) -> Unit)
}

/** Outbound port: the server tick manager (`/tick`), used to slow, freeze and step a preview. */
public interface ITickRateControl {
    public fun rate(): Float
    public fun setRate(ticksPerSecond: Float)
    public fun isFrozen(): Boolean
    public fun setFrozen(frozen: Boolean)

    /** Runs [ticks] ticks while frozen; false when the server is not frozen. */
    public fun step(ticks: Int): Boolean
}

/**
 * Outbound port: a non-player entity acting as a skill caster (a preview dummy, a mob). Skills address
 * casters by [PlayerId]; for an entity the id is its entity uuid, and this port answers the two things the
 * combat pipeline reads from a caster that Core's player-only lookups cannot (pose, teleport).
 */
public interface IEntityCasterBody {
    public fun poseOf(id: PlayerId): PlayerPose?
    public fun teleport(id: PlayerId, at: Vec3, world: WorldId?, yaw: Float?, pitch: Float?): Boolean
}
