package com.astarworks.astera.particlesim

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * Which texture each frame of a particle type's sprite set is, as the resource pack the player gets resolves it: the
 * vanilla list is `assets/minecraft/particles/<type>.json` in the client jar, and our ADR-0252 particle skins replace it
 * with `content/particle-skins/generated/<type>.json` (textures `astera_<channel>_<i>` in the same directory).
 */
class SpriteCatalog(private val contentDir: Path, private val clientJar: Path) {

    class Source(val kind: String, val path: String)

    private val skinDir: Path = contentDir.resolve("particle-skins/generated")
    private val jar = JarFile(clientJar.toFile())

    /** `type` -> texture ids (`minecraft:flame`), skin first. Empty when the type has no sprite list. */
    fun textures(type: String): List<String> {
        val skin = skinDir.resolve("$type.json")
        val text = if (Files.isRegularFile(skin)) Files.readString(skin)
        else jar.getJarEntry("assets/minecraft/particles/$type.json")?.let { e -> jar.getInputStream(e).use { String(it.readBytes()) } } ?: return emptyList()
        return Json.parseToJsonElement(text).jsonObject["textures"]?.jsonArray?.map { normalise(it.jsonPrimitive.content) } ?: emptyList()
    }

    fun isSkinned(type: String): Boolean = Files.isRegularFile(skinDir.resolve("$type.json"))

    /** Where the PNG of [textureId] comes from: our content (pack skin) or the client jar (vanilla); `null` = not found. */
    fun source(textureId: String): Source? {
        val name = textureId.substringAfter(':')
        val ours = skinDir.resolve("$name.png")
        if (Files.isRegularFile(ours)) return Source("content", contentDir.parent.relativize(ours).toString())
        val entry = "assets/${textureId.substringBefore(':', "minecraft")}/textures/particle/$name.png"
        return if (jar.getJarEntry(entry) != null) Source("client-jar", entry) else null
    }

    private fun normalise(id: String): String = if (':' in id) id else "minecraft:$id"
}
