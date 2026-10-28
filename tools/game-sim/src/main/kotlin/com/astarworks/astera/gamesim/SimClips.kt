package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.contents.SkinRigContents
import com.astarworks.astera.core.domain.animation.player.CustomAnimationId
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigAnimation

/** The shipped `content/animations` skin-rig clips, loaded once for the sims that stand in for the Paper caster-anim gateway. */
object SimClips {
    private val registry: SkinRigContents by lazy {
        val dir = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .map { it.resolve("content/animations") }.first { it.isDirectory }
        SkinRigContents().also { it.bootstrap(dir.toPath()) }
    }

    fun clip(anim: String): SkinRigAnimation? = registry.find(CustomAnimationId(anim))
}
