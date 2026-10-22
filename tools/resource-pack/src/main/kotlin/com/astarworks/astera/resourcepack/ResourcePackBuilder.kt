package com.astarworks.astera.resourcepack

import com.fasterxml.jackson.core.json.JsonWriteFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.json.JsonMapper
import org.yaml.snakeyaml.Yaml
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.name
import kotlin.io.path.relativeTo

/** The built pack: zip location, its SHA-1 and how many files it holds. */
public data class BuiltPack(val zip: Path, val sha1: String, val entryCount: Int)

/**
 * Assembles the Astera resource pack from `content/` (ADR-0182).
 *
 * | Source                                   | In the pack                                          |
 * |------------------------------------------|------------------------------------------------------|
 * | `content/textures/<tree>.png` (+ `.mcmeta.json`) | `assets/astera/textures/<tree>` (animation sidecars become `.png.mcmeta`) |
 * | `content/models/<tree>.model.json`           | `assets/astera/models/<tree>.json`                       |
 * | `content/models/manifest.json`           | `assets/astera/items/<id>.json` item model definitions |
 * | `content/sounds/<tree>.ogg` + `*.yaml`       | `assets/astera/sounds/<tree>` + `assets/astera/sounds.json` |
 * | `content/hud/<tree>.png`                     | `assets/astera/textures/hud/<tree>`                      |
 * | `content/hud/top-level .json`                     | providers merged into `assets/astera/font/default.json` |
 * | (generated)                              | transparent sprites hiding the vanilla hearts / armor bar |
 *
 * The zip is reproducible: sorted entries, fixed timestamps, stored (not
 * deflated), so the same content always gives the same SHA-1 on any JVM.
 */
public class ResourcePackBuilder(private val contentDir: Path) {

    /** Every file of the pack: path inside the zip to bytes, sorted. */
    public fun collect(): Map<String, ByteArray> {
        val files = sortedMapOf<String, ByteArray>()
        files["pack.mcmeta"] = packMcmeta().toByteArray()
        addTextures(files)
        addModels(files)
        addSounds(files)
        addHud(files)
        addVanillaOverrides(files)
        return files
    }

    public fun buildTo(outDir: Path): BuiltPack {
        val files = collect()
        Files.createDirectories(outDir)
        val zip = outDir.resolve(ZIP_NAME)
        val bytes = zipBytes(files)
        Files.write(zip, bytes)
        val sha1 = sha1Hex(bytes)
        // `sha1sum` format, so `sha1sum -c astera.zip.sha1` works next to the zip.
        Files.writeString(outDir.resolve("$ZIP_NAME.sha1"), "$sha1  $ZIP_NAME\n")
        return BuiltPack(zip, sha1, files.size)
    }

    // ---- sections ---------------------------------------------------------------

    private fun addTextures(files: MutableMap<String, ByteArray>) {
        for (file in filesUnder("textures")) {
            val rel = file.relativeTo(contentDir.resolve("textures")).invariantSeparatorsPathString
            when {
                file.extension == "png" -> files["assets/$NAMESPACE/textures/$rel"] = Files.readAllBytes(file)
                file.name.endsWith(".mcmeta.json") ->
                    files["assets/$NAMESPACE/textures/${rel.removeSuffix(".mcmeta.json")}.png.mcmeta"] = Files.readAllBytes(file)
            }
        }
    }

    private fun addModels(files: MutableMap<String, ByteArray>) {
        val modelsDir = contentDir.resolve("models")
        for (file in filesUnder("models")) {
            if (!file.name.endsWith(".model.json")) continue
            val rel = file.relativeTo(modelsDir).invariantSeparatorsPathString.removeSuffix(".model.json")
            files["assets/$NAMESPACE/models/$rel.json"] = Files.readAllBytes(file)
        }
        // Item model definitions: only for models the manifest lists AND whose model file made it in.
        val manifest = modelsDir.resolve("manifest.json")
        if (Files.isRegularFile(manifest)) {
            val models = mapper.readTree(Files.readAllBytes(manifest)).path("models")
            for (id in models.fieldNames().asSequence().toList().sorted()) {
                if ("assets/$NAMESPACE/models/$id.json" !in files) continue
                val itemId = id.removePrefix("items/")
                files["assets/$NAMESPACE/items/$itemId.json"] =
                    json(mapOf("model" to mapOf("type" to "minecraft:model", "model" to "$NAMESPACE:$id"))).toByteArray()
            }
        }
    }

    private fun addSounds(files: MutableMap<String, ByteArray>) {
        val soundsDir = contentDir.resolve("sounds")
        val oggs = filesUnder("sounds").filter { it.extension == "ogg" }
        val oggKeys = oggs.map { it.relativeTo(soundsDir).invariantSeparatorsPathString.removeSuffix(".ogg") }.toSet()
        for (ogg in oggs) {
            val rel = ogg.relativeTo(soundsDir).invariantSeparatorsPathString
            files["assets/$NAMESPACE/sounds/$rel"] = Files.readAllBytes(ogg)
        }
        // event id -> sound file key. A registry yaml names its event; a bare ogg is its own event.
        val events = sortedMapOf<String, String>()
        for (yamlFile in filesUnder("sounds").filter { it.extension == "yaml" }) {
            val key = yamlFile.relativeTo(soundsDir).invariantSeparatorsPathString.removeSuffix(".yaml")
            if (key !in oggKeys) continue
            val doc = Yaml().load<Map<String, Any?>>(Files.readString(yamlFile)).orEmpty()
            val id = (doc["id"] as? String)?.substringAfter(':') ?: continue
            events[id] = key
        }
        for (key in oggKeys) if (key !in events.values) events[key] = key
        if (events.isNotEmpty()) {
            val soundsJson = events.mapValues { (_, key) -> mapOf("sounds" to listOf("$NAMESPACE:$key")) }
            files["assets/$NAMESPACE/sounds.json"] = json(soundsJson).toByteArray()
        }
    }

    private fun addHud(files: MutableMap<String, ByteArray>) {
        val hudDir = contentDir.resolve("hud")
        val providers = mutableListOf<JsonNode>()
        for (file in filesUnder("hud")) {
            val rel = file.relativeTo(hudDir).invariantSeparatorsPathString
            when {
                file.extension == "png" -> files["assets/$NAMESPACE/textures/hud/$rel"] = Files.readAllBytes(file)
                // Only the top-level *.json files are font fragments.
                file.extension == "json" && file.parent == hudDir ->
                    mapper.readTree(Files.readAllBytes(file)).path("providers").forEach { providers.add(it) }
            }
        }
        if (providers.isNotEmpty()) {
            files["assets/$NAMESPACE/font/default.json"] = json(mapOf("providers" to providers)).toByteArray()
        }
    }

    /** Fully transparent sprites over the vanilla hearts and armor bar: the status HUD draws HP itself. */
    private fun addVanillaOverrides(files: MutableMap<String, ByteArray>) {
        val transparent = transparentSprite()
        for (name in HIDDEN_SPRITES) files["assets/minecraft/textures/gui/sprites/hud/$name.png"] = transparent
    }

    // ---- format -------------------------------------------------------------------

    private fun packMcmeta(): String = json(
        mapOf(
            "pack" to linkedMapOf(
                "description" to "Astera resource pack",
                "min_format" to MIN_FORMAT,
                "max_format" to MAX_FORMAT,
            ),
        ),
    )

    private fun filesUnder(dir: String): List<Path> {
        val root = contentDir.resolve(dir)
        if (!Files.isDirectory(root)) return emptyList()
        return Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && !it.name.startsWith(".") }
                .sorted(compareBy { it.relativeTo(root).invariantSeparatorsPathString })
                .toList()
        }
    }

    private fun transparentSprite(): ByteArray {
        val out = ByteArrayOutputStream()
        check(ImageIO.write(BufferedImage(SPRITE_SIZE, SPRITE_SIZE, BufferedImage.TYPE_INT_ARGB), "png", out)) { "no PNG writer" }
        return out.toByteArray()
    }

    public companion object {
        public const val NAMESPACE: String = "astera"
        public const val ZIP_NAME: String = "astera.zip"
        public const val SPRITE_SIZE: Int = 9

        /**
         * Pack format window. Minecraft 1.21.9 replaced `pack_format` /
         * `supported_formats` with `min_format` / `max_format`; 75 is the format of
         * 1.21.11, the oldest client this pack is meant for. The exact resource pack
         * format of Minecraft 26.3 could not be looked up offline, so the window is
         * left open upwards - narrow `MAX_FORMAT` once the 26.3 value is confirmed
         * (a client only warns on a mismatch for a server pack, it still loads it).
         */
        public const val MIN_FORMAT: Int = 75
        public const val MAX_FORMAT: Int = 1000

        private val FIXED_TIME: LocalDateTime = LocalDateTime.of(1980, 1, 1, 0, 0)

        private val HEART_STATES = listOf("", "hardcore_", "poisoned_", "poisoned_hardcore_", "withered_", "withered_hardcore_",
            "frozen_", "frozen_hardcore_", "absorbing_", "absorbing_hardcore_")

        /** Sprite names under `textures/gui/sprites/hud/` that the pack blanks. */
        public val HIDDEN_SPRITES: List<String> = buildList {
            add("heart/container")
            add("heart/container_blinking")
            add("heart/container_hardcore")
            add("heart/container_hardcore_blinking")
            for (state in HEART_STATES) {
                for (part in listOf("full", "half")) {
                    add("heart/$state$part")
                    add("heart/$state${part}_blinking")
                }
            }
            add("armor_empty")
            add("armor_half")
            add("armor_full")
        }.distinct().sorted()

        private val mapper: ObjectMapper = JsonMapper.builder().enable(JsonWriteFeature.ESCAPE_NON_ASCII).build()

        private fun json(value: Any): String = mapper.writeValueAsString(value) + "\n"

        internal fun sha1Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

        internal fun zipBytes(files: Map<String, ByteArray>): ByteArray {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                for ((name, bytes) in files.toSortedMap()) {
                    val crc = CRC32().also { it.update(bytes) }
                    val entry = ZipEntry(name).apply {
                        method = ZipEntry.STORED
                        size = bytes.size.toLong()
                        compressedSize = bytes.size.toLong()
                        this.crc = crc.value
                        timeLocal = FIXED_TIME
                    }
                    zip.putNextEntry(entry)
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            return out.toByteArray()
        }
    }
}
