package com.astarworks.astera.vfxpreview

import com.astarworks.astera.core.application.config.ParticleSkinYamlConfig
import com.astarworks.astera.core.application.config.ParticleYamlConfig
import com.charleskorn.kaml.Yaml
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Frames of retextured vanilla particle types (ADR-0252). A particle emission whose `content/particles/<id>.yaml` names a
 * vanilla type that has `content/particle-skins/<type>.yaml` is drawn as that skin's frame for its age instead of a dot:
 * frame = age / the definition's `lifetime_ticks` over the frame count, multiplied by the `data: {kind: tint}` colour for
 * grey skins. The vanilla client picks the frame by the particle's own (type-specific) lifetime; this only approximates it.
 */
class ParticleSkinFrames(private val contentDir: File) {

    private class Skin(val frames: List<BufferedImage>, val grey: Boolean)

    private class Resolved(val skin: Skin, val lifetime: Int, val tint: Int?)

    private val yaml = Yaml.default
    private val skins = HashMap<String, Skin?>()
    private val resolved = HashMap<String, Resolved?>()

    /** The frame for a particle of [particleId] [age] ticks after it was emitted, or `null` when its type is not skinned. */
    fun frame(particleId: String, age: Int): BufferedImage? {
        val r = resolved.getOrPut(particleId) { resolve(particleId) } ?: return null
        val n = r.skin.frames.size
        val i = (age * n / r.lifetime.coerceAtLeast(1)).coerceIn(0, n - 1)
        val img = r.skin.frames[i]
        return if (r.skin.grey && r.tint != null) multiply(img, r.tint) else img
    }

    private fun resolve(particleId: String): Resolved? {
        val file = File(contentDir, "particles/$particleId.yaml")
        if (!file.isFile) return null
        val def = runCatching { yaml.decodeFromString(ParticleYamlConfig.serializer(), file.readText()) }.getOrNull() ?: return null
        val type = def.vanillaParticle?.removePrefix("minecraft:") ?: return null
        val skin = skins.getOrPut(type) { load(type) } ?: return null
        val tint = def.data?.takeIf { it.kind.equals("tint", ignoreCase = true) }?.color?.let { (it.r shl 16) or (it.g shl 8) or it.b }
        return Resolved(skin, def.lifetimeTicks.toInt(), tint)
    }

    private fun load(type: String): Skin? {
        val file = File(contentDir, "particle-skins/$type.yaml")
        if (!file.isFile) return null
        val cfg = runCatching { yaml.decodeFromString(ParticleSkinYamlConfig.serializer(), file.readText()) }.getOrNull() ?: return null
        val skin = cfg.toDefinition()
        val frames = (0 until skin.frames).map { i ->
            val png = File(contentDir, "particle-skins/generated/astera_${skin.channel.replace('-', '_')}_$i.png")
            if (!png.isFile) return null
            ImageIO.read(png)
        }
        return Skin(frames, cfg.tint.equals("grey", ignoreCase = true))
    }

    private fun multiply(src: BufferedImage, rgb: Int): BufferedImage {
        val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB)
        val tr = rgb shr 16 and 0xFF
        val tg = rgb shr 8 and 0xFF
        val tb = rgb and 0xFF
        for (y in 0 until src.height) for (x in 0 until src.width) {
            val p = src.getRGB(x, y)
            val r = (p shr 16 and 0xFF) * tr / 255
            val g = (p shr 8 and 0xFF) * tg / 255
            val b = (p and 0xFF) * tb / 255
            out.setRGB(x, y, (p ushr 24 shl 24) or (r shl 16) or (g shl 8) or b)
        }
        return out
    }

    companion object {
        /** Content dir for callers that do not pass one: `-Dastera.contentDir` (tests) or `./content`. */
        fun default(): ParticleSkinFrames = ParticleSkinFrames(File(System.getProperty("astera.contentDir") ?: "content"))
    }
}
