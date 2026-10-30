package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.skillengine.compose.drycast.ClutterContext
import com.astarworks.astera.core.domain.Result

/** The shipped `content/engine/particle-lifetimes.yaml` + particle defs + skin-rig clips, as the load-time caster-eye check (16) sees them. */
object SimClutter {
    private val ctx: ClutterContext by lazy {
        val dir = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .map { it.resolve("content") }.first { it.resolve("engine/particle-lifetimes.yaml").isFile }
        when (val r = ClutterContext.load(dir.toPath(), SimClips.bodySkeleton()) { SimClips.clip(it) }) {
            is Result.Success -> r.value
            is Result.Failure -> error("content/engine/particle-lifetimes.yaml: ${r.error}")
        }
    }

    fun context(): ClutterContext = ctx
}
