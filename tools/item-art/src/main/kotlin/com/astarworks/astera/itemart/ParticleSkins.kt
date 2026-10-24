package com.astarworks.astera.itemart

import org.yaml.snakeyaml.Yaml
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path

/**
 * Retextured vanilla particle types (ADR-0252): `content/particle-skins/<vanilla-type>.yaml` names the vanilla
 * particle type, the channel id (texture names), the painter, the frame count and whether the texture is
 * `grey` (the particle's server colour multiplies it) or `baked` (coloured as drawn).
 *
 * ```yaml
 * type: entity_effect
 * channel: ember
 * source: ember          # a painter of [ParticleSkinPainters]
 * frames: 8
 * tint: grey             # grey | baked
 * size: 64               # optional, px (default 64)
 * ```
 *
 * The tool writes, under `content/particle-skins/generated/`, one PNG per frame (`astera_<channel>_<i>.png`) and
 * the vanilla-format sprite list `<type>.json`. `ResourcePackBuilder` ships them as
 * `assets/minecraft/textures/particle/` and `assets/minecraft/particles/`, and refuses a list naming a missing PNG.
 */
internal object ParticleSkins {
    const val DIR: String = "particle-skins"
    const val GENERATED: String = "$DIR/generated"
    const val TEXTURE_PREFIX: String = "astera_"
    const val DEFAULT_SIZE: Int = 64
    const val MAX_FRAMES: Int = 64

    private val SNAKE = Regex("^[a-z][a-z0-9_]*$")
    private val KEBAB = Regex("^[a-z][a-z0-9-]*$")

    data class Skin(val type: String, val channel: String, val source: String, val frames: Int, val grey: Boolean, val size: Int) {
        fun textureName(i: Int): String = "$TEXTURE_PREFIX${channel.replace('-', '_')}_$i"
    }

    fun load(contentDir: Path): List<Skin> {
        val dir = contentDir.resolve(DIR)
        if (!Files.isDirectory(dir)) return emptyList()
        val files = Files.list(dir).use { s -> s.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".yaml") }.sorted().toList() }
        val skins = files.map { file ->
            val where = "$DIR/${file.fileName}"
            val doc = Yaml().load<Map<String, Any?>>(Files.readString(file)).orEmpty()
            val type = requireNotNull(doc["type"]?.toString()) { "$where: type is required" }
            require(type.matches(SNAKE)) { "$where: type must be a vanilla particle id (lower_snake): $type" }
            require(file.fileName.toString() == "$type.yaml") { "$where: file name must be <type>.yaml" }
            val channel = requireNotNull(doc["channel"]?.toString()) { "$where: channel is required" }
            require(channel.matches(KEBAB)) { "$where: channel must be lower-kebab: $channel" }
            val source = requireNotNull(doc["source"]?.toString()) { "$where: source is required" }
            require(source in ParticleSkinPainters.NAMES) { "$where: unknown source '$source' (${ParticleSkinPainters.NAMES})" }
            val frames = (doc["frames"] as? Number)?.toInt() ?: error("$where: frames is required")
            require(frames in 1..MAX_FRAMES) { "$where: frames must be 1..$MAX_FRAMES" }
            val tint = doc["tint"]?.toString() ?: error("$where: tint is required (grey|baked)")
            require(tint == "grey" || tint == "baked") { "$where: tint must be grey or baked: $tint" }
            val size = (doc["size"] as? Number)?.toInt() ?: DEFAULT_SIZE
            require(size in 16..256) { "$where: size must be 16..256" }
            Skin(type, channel, source, frames, tint == "grey", size)
        }
        require(skins.map { it.channel }.distinct().size == skins.size) { "$DIR: duplicate channel ids" }
        return skins
    }

    fun render(skin: Skin): List<BufferedImage> = ParticleSkinPainters.frames(skin.source, skin.frames, skin.size, skin.grey)

    /** Vanilla `assets/minecraft/particles/<type>.json` text for [skin]. */
    fun listJson(skin: Skin): String {
        val names = (0 until skin.frames).joinToString(",\n") { "    \"minecraft:${skin.textureName(it)}\"" }
        return "{\n  \"textures\": [\n$names\n  ]\n}\n"
    }

    /** Every generated file keyed by path relative to the content dir. */
    fun renderAll(contentDir: Path): Map<String, ByteArray> {
        val files = sortedMapOf<String, ByteArray>()
        for (skin in load(contentDir)) {
            render(skin).forEachIndexed { i, frame -> files["$GENERATED/${skin.textureName(i)}.png"] = ItemArt.png(frame) }
            files["$GENERATED/${skin.type}.json"] = listJson(skin).toByteArray()
        }
        return files
    }

    /** Generated files on disk that the generator no longer produces. */
    fun orphans(contentDir: Path, keep: Set<String>): List<Path> {
        val d = contentDir.resolve(GENERATED)
        if (!Files.isDirectory(d)) return emptyList()
        return Files.list(d).use { s -> s.filter { Files.isRegularFile(it) }.toList() }
            .filter { contentDir.relativize(it).toString().replace('\\', '/') !in keep }
    }
}
