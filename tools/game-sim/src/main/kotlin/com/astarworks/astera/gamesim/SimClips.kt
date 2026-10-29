package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.contents.SkinRigContents
import com.astarworks.astera.core.application.skillengine.load.SkillEngineRegistries
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.skinrig.BodySkeleton
import com.astarworks.astera.core.domain.animation.player.CustomAnimationId
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigAnimation

/** The shipped `content/animations` skin-rig clips, loaded once for the sims that stand in for the Paper caster-anim gateway. */
object SimClips {
    private val registry: SkinRigContents by lazy {
        val dir = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .map { it.resolve("content/animations") }.first { it.isDirectory }
        SkinRigContents().also { it.bootstrap(dir.toPath()) }
    }

    /** Null for an unknown clip or an id that is not a valid animation id, exactly as the Paper gateway's lookup answers. */
    fun clip(anim: String): SkinRigAnimation? = runCatching { registry.find(CustomAnimationId(anim)) }.getOrNull()

    private val skeleton: BodySkeleton by lazy {
        val dir = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .map { it.resolve("content/frames") }.first { it.isDirectory }
        when (val r = SkillEngineRegistries.bodySkeleton(dir.toPath())) {
            is Result.Success -> r.value
            is Result.Failure -> error("content/frames/body.yaml: ${r.error}")
        }
    }

    /** The shipped `content/frames/body.yaml`, exactly as the Paper gateway serves it. */
    fun bodySkeleton(): BodySkeleton = skeleton
}
