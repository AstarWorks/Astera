package com.astarworks.astera.core.application.skillengine.compose.drycast

import com.astarworks.astera.core.application.skillengine.load.ParticleLifetimesLoader
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.skinrig.BodySkeleton
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigAnimation
import com.astarworks.astera.core.domain.skillengine.model.ParticleLifetimes
import java.nio.file.Files
import java.nio.file.Path

/** What a content particle definition (`content/particles/<id>.yaml`) contributes to the clutter model: the vanilla type it spawns, its dust size and its own burst count. */
public data class ParticleDefInfo(val vanilla: String, val scale: Double?, val count: Int)

/**
 * Everything the caster-eye check needs besides the skill: the lifetimes sourced from the vanilla jar, the content particle definitions,
 * and the body clips / skeleton the dry cast serves to `body` and `rig` rows (so they play as they do live).
 */
public class ClutterContext(
    public val lifetimes: ParticleLifetimes,
    public val particleDefs: Map<String, ParticleDefInfo>,
    public val bodySkeleton: BodySkeleton,
    public val bodyClip: (String) -> SkinRigAnimation?,
) {
    public companion object {
        /** Reads `engine/particle-lifetimes.yaml` and the `particles` directory under [contentDir]. */
        public fun load(contentDir: Path, bodySkeleton: BodySkeleton, bodyClip: (String) -> SkinRigAnimation?): Result<ClutterContext, com.astarworks.astera.core.application.skillengine.load.EngineConfigError> =
            when (val l = ParticleLifetimesLoader().load(contentDir.resolve("engine/particle-lifetimes.yaml"))) {
                is Result.Failure -> l
                is Result.Success -> Result.Success(ClutterContext(l.value, particleDefs(contentDir.resolve("particles")), bodySkeleton, bodyClip))
            }

        /** Flat line scan (the same fields the particle loader reads): `particle:`, the top-level `count:` and the dust `size:`. */
        public fun particleDefs(dir: Path): Map<String, ParticleDefInfo> {
            if (!Files.isDirectory(dir)) return emptyMap()
            val out = HashMap<String, ParticleDefInfo>()
            Files.list(dir).use { s ->
                s.filter { it.toString().endsWith(".yaml") }.forEach { f ->
                    val text = Files.readString(f)
                    val vanilla = VANILLA.find(text)?.groupValues?.get(1) ?: return@forEach
                    out[f.fileName.toString().removeSuffix(".yaml")] = ParticleDefInfo(
                        vanilla = vanilla,
                        scale = SCALE.find(text)?.groupValues?.get(1)?.toDoubleOrNull(),
                        count = COUNT.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 1,
                    )
                }
            }
            return out
        }

        private val VANILLA = Regex("(?m)^particle:\\s*(\\S+)")
        private val SCALE = Regex("(?m)^size:\\s*([0-9.]+)")
        private val COUNT = Regex("(?m)^count:\\s*(\\d+)")
    }
}
