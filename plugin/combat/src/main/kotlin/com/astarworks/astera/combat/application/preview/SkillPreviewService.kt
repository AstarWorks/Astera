package com.astarworks.astera.combat.application.preview

import com.astarworks.astera.combat.application.admin.AdminSkillCaster
import com.astarworks.astera.combat.application.port.outbound.ISkillPreviewStage
import com.astarworks.astera.combat.application.port.outbound.ITickRateControl
import com.astarworks.astera.combat.application.port.outbound.PreviewStage
import com.astarworks.astera.combat.application.port.outbound.PreviewStageSpec
import com.astarworks.astera.combat.application.usecase.skill.CastSkillError
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IPlayerPoseLookup
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runLaterTicks
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.item.ItemTemplate
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId

public sealed interface PreviewError {
    public data class SkillNotFound(public val skill: SkillId) : PreviewError
    public data object ViewerUnavailable : PreviewError
    public data object StageFailed : PreviewError
    public data object SpeedNeedsOperator : PreviewError
    public data object NothingToRepeat : PreviewError
    public data object NotFrozen : PreviewError
    public data class CastFailed(public val skill: SkillId, public val error: CastSkillError) : PreviewError
}

/**
 * `/astera skill preview` (founder: "a way to see skills from another person's viewpoint").
 *
 * Spawns a player-like dummy caster in front of the viewer plus a target dummy, makes the dummy cast the
 * skill through the real [AdminSkillCaster] pipeline (so damage, hit_stop, dash_strike, VFX all run), parks the
 * viewer's camera per [PreviewView], optionally slows the server tick rate, and cleans everything up
 * [PLAYBACK_TICKS] + [CLEANUP_TICKS] later (or on [shutdown] / viewer quit). One preview at a time: the tick
 * rate is global to the backend.
 */
public class SkillPreviewService(
    private val stage: ISkillPreviewStage,
    private val tick: ITickRateControl,
    private val caster: AdminSkillCaster,
    private val poses: IPlayerPoseLookup,
    private val items: IItemRegistry?,
    private val scheduler: IScheduler,
    /** The delayed cast was rejected (silenced, ...); the preview has already ended. */
    private val onCastFailed: (viewer: PlayerId, error: PreviewError.CastFailed) -> Unit = { _, _ -> },
) {

    private class Session(
        val viewer: PlayerId,
        val stage: PreviewStage,
        val restoreRate: Float?,
        var tasks: List<IScheduler.TaskHandle> = emptyList(),
    )

    private data class Request(val skill: SkillId, val options: PreviewOptions)

    private var session: Session? = null
    private var last: Request? = null
    private var frozenByUs: Boolean = false

    init {
        stage.onViewerQuit { viewer -> if (session?.viewer == viewer) end() }
    }

    public fun start(
        viewer: PlayerId,
        skill: SkillId,
        options: PreviewOptions,
        operator: Boolean,
    ): Result<Unit, PreviewError> {
        if (caster.all().none { it.id == skill }) return Result.failure(PreviewError.SkillNotFound(skill))
        if (options.speed < 1.0 && !operator) return Result.failure(PreviewError.SpeedNeedsOperator)
        val pose = poses.poseOf(viewer) ?: return Result.failure(PreviewError.ViewerUnavailable)
        end()

        val layout = PreviewGeometry.layout(pose.position, pose.direction)
        val spec = PreviewStageSpec(pose.world, layout.caster, layout.target, weaponFor(skill))
        val built = stage.spawn(spec) ?: return Result.failure(PreviewError.StageFailed)
        last = Request(skill, options)

        PreviewGeometry.viewPoint(options.view, layout, options.distance)?.let { stage.placeViewer(viewer, it) }

        val restoreRate = if (options.speed < 1.0) tick.rate() else null
        val current = Session(viewer, built, restoreRate)
        session = current

        // Let the camera / chunks settle, then slow time and cast.
        val cast = scheduler.runLaterTicks(LEAD_TICKS) {
            if (session !== current) return@runLaterTicks
            if (options.speed < 1.0) tick.setRate((20.0 * options.speed).toFloat())
            val result = caster.cast(built.caster, skill, force = true)
            if (result is Result.Failure) {
                end()
                onCastFailed(viewer, PreviewError.CastFailed(skill, result.error))
            }
        }
        val heal = scheduler.runRepeatingTicks(LEAD_TICKS, HEAL_PERIOD_TICKS) {
            if (session === current) stage.healTarget(built)
        }
        val cleanup = scheduler.runLaterTicks(LEAD_TICKS + PLAYBACK_TICKS + CLEANUP_TICKS) {
            if (session === current) end()
        }
        current.tasks = listOf(cast, heal, cleanup)
        return Result.success(Unit)
    }

    /** Repeats the last preview ([again]); the rate / view are as in the original request. */
    public fun again(viewer: PlayerId, operator: Boolean): Result<Unit, PreviewError> {
        val request = last ?: return Result.failure(PreviewError.NothingToRepeat)
        return start(viewer, request.skill, request.options, operator)
    }

    public fun freeze() {
        tick.setFrozen(true)
        frozenByUs = true
    }

    public fun unfreeze() {
        tick.setFrozen(false)
        frozenByUs = false
    }

    public fun step(ticks: Int): Result<Unit, PreviewError> =
        if (tick.step(ticks.coerceIn(1, MAX_STEP))) Result.success(Unit) else Result.failure(PreviewError.NotFrozen)

    /** Ends the running preview: camera, tick rate, dummies. Safe to call with nothing running. */
    public fun end() {
        val current = session ?: return
        session = null
        current.tasks.forEach { runCatching { it.cancel() } }
        current.restoreRate?.let { tick.setRate(it) }
        if (frozenByUs) unfreeze()
        stage.restoreViewer(current.viewer)
        stage.despawn(current.stage)
    }

    /** Plugin disable. */
    public fun shutdown() {
        end()
        if (frozenByUs) unfreeze()
    }

    private fun weaponFor(skill: SkillId): String {
        val template = items?.all()
            ?.firstOrNull { item -> item.skillBindings.any { it.skillId == skill } }
            ?.template
        val material = (template as? ItemTemplate.Vanilla)?.material
        return material?.substringAfter(':')?.lowercase() ?: FALLBACK_WEAPON
    }

    public companion object {
        public const val LEAD_TICKS: Long = 10
        public const val PLAYBACK_TICKS: Long = 80
        public const val CLEANUP_TICKS: Long = 40
        public const val HEAL_PERIOD_TICKS: Long = 2
        public const val MAX_STEP: Int = 200
        public const val FALLBACK_WEAPON: String = "iron_sword"
    }
}
