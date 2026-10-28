package com.astarworks.astera.resourcepack

import com.fasterxml.jackson.core.json.JsonWriteFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.node.ObjectNode
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
 * | `content/fonts/<tree>.json`                  | `assets/astera/font/<tree>.json` (`fonts/vfx/spark.json` = font `astera:vfx/spark`, ADR-0239) |
 * | `content/pack-assets/assets/<path>`          | `assets/<path>` unchanged (skin-rig item definition, ADR-0250) |
 * | `content/particle-skins/generated/<frame>.png` | `assets/minecraft/textures/particle/` (retextured vanilla particle frames, ADR-0274) |
 * | `content/particle-skins/generated/<type>.json` | `assets/minecraft/particles/<type>.json` (sprite list; a missing PNG fails the build) |
 * | `content/shader-patches/core/<file>.patch`   | `assets/minecraft/shaders/core/<file>`: vanilla shader from the client jar + our patch (ADR-0250) |
 * | `content/textures/sources/` (all of it)     | not shipped (raster sources for `:tools:item-art`, ADR-0238) |
 * | (generated)                              | transparent sprites hiding the vanilla hearts / armor bar |
 *
 * The zip is reproducible: sorted entries, fixed timestamps, stored (not
 * deflated), so the same content always gives the same SHA-1 on any JVM.
 */
public class ResourcePackBuilder(
    private val contentDir: Path,
    private val vanilla: VanillaShaders = VanillaShaders.fromEnvironment(),
) {

    /** Every file of the pack: path inside the zip to bytes, sorted. */
    public fun collect(): Map<String, ByteArray> {
        val files = sortedMapOf<String, ByteArray>()
        files["pack.mcmeta"] = packMcmeta().toByteArray()
        addTextures(files)
        addEquipment(files)
        addModels(files)
        addBlockVisuals(files)
        addHeldHiddenDefinition(files)
        addSounds(files)
        addHud(files)
        addFonts(files)
        addVanillaOverrides(files)
        addAtlases(files)
        addVerbatim(files)
        addParticleSkins(files)
        addPatchedShaders(files)
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

    /**
     * Vanilla's atlas sources only cover `textures/item` / `textures/block`, so item-model
     * textures under `textures/items/` and `textures/generated/items/` render magenta unless
     * the pack adds `directory` sources. Atlas definitions merge across packs.
     */
    private fun addAtlases(files: MutableMap<String, ByteArray>) {
        val dirs = atlasDirectories(files.keys)
        for ((atlas, list) in dirs) {
            val sources = list.map { mapOf("type" to "directory", "source" to it, "prefix" to "$it/") }
            files["assets/minecraft/atlases/$atlas.json"] = json(mapOf("sources" to sources)).toByteArray()
        }
    }

    private fun addTextures(files: MutableMap<String, ByteArray>) {
        for (file in filesUnder("textures")) {
            val rel = file.relativeTo(contentDir.resolve("textures")).invariantSeparatorsPathString
            if (rel.startsWith("$SOURCES_DIR/") || rel.startsWith("equipment/")) continue
            when {
                file.extension == "png" -> files["assets/$NAMESPACE/textures/$rel"] = Files.readAllBytes(file)
                file.name.endsWith(".mcmeta.json") ->
                    files["assets/$NAMESPACE/textures/${rel.removeSuffix(".mcmeta.json")}.png.mcmeta"] = Files.readAllBytes(file)
            }
        }
    }

    /** Equipment sources use vanilla's 64x32 humanoid UV layout, separate from item icons. */
    private fun addEquipment(files: MutableMap<String, ByteArray>) {
        val items = filesUnder("items").filter { it.extension == "yaml" }
        val assets = items.mapNotNull { file ->
            Yaml().load<Map<String, Any?>>(Files.readString(file)).orEmpty()["equipment_asset"] as? String
        }.toSortedSet()
        for (asset in assets) {
            require(asset.matches(Regex("[a-z0-9._-]+"))) { "Invalid equipment_asset: $asset" }
            val layers = listOf("humanoid", "humanoid_leggings").associateWith { layer ->
                val source = contentDir.resolve("textures/equipment/$layer/$asset.png")
                require(Files.isRegularFile(source)) { "Missing equipment texture: $source" }
                val image = requireNotNull(ImageIO.read(source.toFile())) { "Unreadable equipment texture: $source" }
                require(image.width == 64 && image.height == 32) { "Equipment texture must be 64x32: $source" }
                files["assets/$NAMESPACE/textures/entity/equipment/$layer/$asset.png"] = Files.readAllBytes(source)
                listOf(mapOf("texture" to "$NAMESPACE:$asset"))
            }
            files["assets/$NAMESPACE/equipment/$asset.json"] = json(mapOf("layers" to layers)).toByteArray()
        }
    }

    private fun addModels(files: MutableMap<String, ByteArray>) {
        val modelsDir = contentDir.resolve("models")
        for (file in filesUnder("models")) {
            if (!file.name.endsWith(".model.json")) continue
            val rel = file.relativeTo(modelsDir).invariantSeparatorsPathString.removeSuffix(".model.json")
            val bytes = Files.readAllBytes(file)
            val model = mapper.readTree(bytes)
            modelTints(model) // Fail malformed palettes/indices before shipping the model.
            files["assets/$NAMESPACE/models/$rel.json"] = if (model.has("astera_tints") || model.has("astera_empty")) {
                (model as ObjectNode).remove("astera_tints")
                model.remove("astera_empty")
                json(model).toByteArray()
            } else bytes
        }
        // Item model definitions: only for models the manifest lists AND whose model file made it in.
        val manifest = modelsDir.resolve("manifest.json")
        if (Files.isRegularFile(manifest)) {
            val models = mapper.readTree(Files.readAllBytes(manifest)).path("models")
            for (id in models.fieldNames().asSequence().toList().sorted()) {
                if ("assets/$NAMESPACE/models/$id.json" !in files) continue
                val itemId = id.removePrefix("items/")
                files["assets/$NAMESPACE/items/$itemId.json"] =
                    itemDefinition(id)
            }
        }
        addGeneratedItemDefinitions(files)
    }

    /**
     * Procedural icons (`:tools:item-art`, ADR-0193): each `models/generated/items/<id>` gets the item
     * definition `astera:<id>` (the id the item materializer puts in `item_model`). A Studio-authored
     * model whose last path segment is the item id (`models/items/swords/dagger` for `dagger`)
     * overrides the generated icon: the definition for that id then points at the Studio model.
     */
    private fun addGeneratedItemDefinitions(files: MutableMap<String, ByteArray>) {
        val prefix = "assets/$NAMESPACE/models/$GENERATED_ITEM_DIR/"
        val studio = files.keys
            .filter { it.startsWith("assets/$NAMESPACE/models/items/") && it.endsWith(".json") }
            .associateBy { it.substringAfterLast('/').removeSuffix(".json") }
        for (path in files.keys.filter { it.startsWith(prefix) && it.endsWith(".json") }.toList()) {
            val id = path.removePrefix(prefix).removeSuffix(".json")
            val model = studio[id]?.removePrefix("assets/$NAMESPACE/models/")?.removeSuffix(".json")
                ?: "$GENERATED_ITEM_DIR/$id"
            files["assets/$NAMESPACE/items/$id.json"] =
                itemDefinition(model)
        }
    }

    /** Authoring metadata belongs in the item definition, never the cuboid model. */
    private fun itemDefinition(model: String): ByteArray {
        val source = mapper.readTree(Files.readAllBytes(contentDir.resolve("models/$model.model.json")))
        val definition = linkedMapOf<String, Any>("type" to "minecraft:model", "model" to "$NAMESPACE:$model")
        // `astera_empty: true` marks an invisible item (weapon_model hide key): the vanilla empty item model, no cuboid model.
        if (source.path("astera_empty").asBoolean(false)) {
            return json(mapOf("model" to mapOf("type" to "minecraft:empty"), "hand_animation_on_swap" to false)).toByteArray()
        }
        val tints = modelTints(source)
        if (tints.isNotEmpty()) definition["tints"] = tints.map { mapOf("type" to "minecraft:constant", "value" to it) }
        // The first-person hand swaps to / from an Astera item in one frame (no lower-and-raise): a rig that replaces the
        // held weapon (hide_held_item) hands over without two swords or the re-equip animation.
        return json(mapOf("model" to definition, "hand_animation_on_swap" to false)).toByteArray()
    }

    /**
     * `astera:held_hidden`: an item that draws nothing. The server sends it in place of the caster's weapon while a rig
     * draws that weapon; the client replaces the first-person item at once instead of lowering the old one first.
     */
    private fun addHeldHiddenDefinition(files: MutableMap<String, ByteArray>) {
        files["assets/$NAMESPACE/items/held_hidden.json"] =
            json(mapOf("model" to mapOf("type" to "minecraft:empty"), "hand_animation_on_swap" to false)).toByteArray()
    }

    private fun modelTints(model: JsonNode): List<Int> {
        val table = model.path("astera_tints")
        require(table.isMissingNode || table.isArray) { "astera_tints must be an RGB array" }
        val colors = table.map {
            require(it.isIntegralNumber && it.canConvertToInt() && it.intValue() in 0..0xffffff) {
                "astera_tints values must be integer RGB (0..16777215)"
            }
            it.intValue()
        }
        for (element in model.path("elements")) for (face in element.path("faces")) {
            val index = face.path("tintindex")
            require(index.isMissingNode || (index.isIntegralNumber && index.canConvertToInt() &&
                (index.intValue() == -1 || index.intValue() in colors.indices))) {
                "tintindex must be -1 or an index into astera_tints"
            }
        }
        return colors
    }

    /** ADR-0300: cube model is shared by the world and the materialized held/dropped item. */
    private fun addBlockVisuals(files: MutableMap<String, ByteArray>) {
        val variants = sortedMapOf<String, Any>()
        val instruments = listOf("harp", "basedrum", "snare", "hat", "bass", "flute", "bell", "guitar",
            "chime", "xylophone", "iron_xylophone", "cow_bell", "didgeridoo", "bit", "banjo", "pling",
            "zombie", "skeleton", "creeper", "dragon", "wither_skeleton", "piglin", "custom_head")
        for (instrument in instruments) for (note in 0..24) for (powered in listOf(false, true)) {
            variants["instrument=$instrument,note=$note,powered=$powered"] = mapOf("model" to "minecraft:block/note_block")
        }
        val claimed = mutableSetOf<String>()
        for (file in filesUnder("blocks").filter { it.extension == "yaml" }) {
            val doc = Yaml().load<Map<String, Any?>>(Files.readString(file))
            val visual = doc["visual"] as? Map<*, *> ?: continue
            require(visual["base"] == "note_block") { "$file: unsupported visual base" }
            val state = (visual["state"] as Map<*, *>).entries.associate { it.key.toString() to it.value.toString() }.toSortedMap()
            val key = state.entries.joinToString(",") { "${it.key}=${it.value}" }
            require(state["instrument"] == "harp" && state["note"]?.toIntOrNull() in 1..9 && state["powered"] == "false") { "$file: unsafe visual state" }
            require(claimed.add(key)) { "$file: duplicate visual state $key" }
            val id = file.name.removeSuffix(".yaml")
            val texture = doc["texture"].toString()
            require("assets/${texture.substringBefore(':')}/textures/${texture.substringAfter(':')}.png" in files) { "$file: missing texture $texture" }
            val model = "blocks/$id"
            files["assets/$NAMESPACE/models/$model.json"] = json(mapOf("parent" to "minecraft:block/cube_all", "textures" to mapOf("all" to texture))).toByteArray()
            variants[key] = mapOf("model" to "$NAMESPACE:$model")
            val item = requireNotNull(doc["placed_from"]) { "$file: visual needs placed_from" }.toString()
            files["assets/$NAMESPACE/items/$item.json"] = json(mapOf("model" to mapOf("type" to "minecraft:model", "model" to "$NAMESPACE:$model"))).toByteArray()
        }
        if (claimed.isNotEmpty()) files["assets/minecraft/blockstates/note_block.json"] = json(mapOf("variants" to variants)).toByteArray()
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
        val soundsJson = sortedMapOf<String, Any>()
        events.forEach { (id, key) -> soundsJson[id] = mapOf("sounds" to listOf("$NAMESPACE:$key")) }
        // BGM (content/music/<id>.ogg): event music/<id>, streamed from disk rather than preloaded (long files).
        val musicDir = contentDir.resolve("music")
        for (ogg in filesUnder("music").filter { it.extension == "ogg" }) {
            val rel = ogg.relativeTo(musicDir).invariantSeparatorsPathString
            files["assets/$NAMESPACE/sounds/music/$rel"] = Files.readAllBytes(ogg)
            val id = "music/" + rel.removeSuffix(".ogg")
            soundsJson[id] = mapOf("sounds" to listOf(mapOf("name" to "$NAMESPACE:$id", "stream" to true)))
        }
        if (soundsJson.isNotEmpty()) files["assets/$NAMESPACE/sounds.json"] = json(soundsJson).toByteArray()
    }

    private fun addHud(files: MutableMap<String, ByteArray>) {
        val hudDir = contentDir.resolve("hud")
        val providers = mutableListOf<JsonNode>()
        for (file in filesUnder("hud")) {
            val rel = file.relativeTo(hudDir).invariantSeparatorsPathString
            when {
                file.extension == "png" -> files["assets/$NAMESPACE/textures/hud/$rel"] = Files.readAllBytes(file)
                // The screen_flash overlay glyphs are their own font, astera:overlay.
                rel == "overlay.json" -> files["assets/$NAMESPACE/font/overlay.json"] = Files.readAllBytes(file)
                // Only the top-level *.json files are font fragments.
                file.extension == "json" && file.parent == hudDir ->
                    mapper.readTree(Files.readAllBytes(file)).path("providers").forEach { providers.add(it) }
            }
        }
        if (providers.isNotEmpty()) {
            files["assets/$NAMESPACE/font/default.json"] = json(mapOf("providers" to providers)).toByteArray()
        }
    }

    /**
     * Font definitions (ADR-0239): `content/fonts/<tree>.json` ships verbatim as
     * `assets/astera/font/<tree>.json`. The HUD owns `font/default.json` (merged in [addHud]), so a
     * `fonts/default.json` next to it would silently replace one of the two: refuse.
     */
    private fun addFonts(files: MutableMap<String, ByteArray>) {
        val fontsDir = contentDir.resolve("fonts")
        for (file in filesUnder("fonts")) {
            if (file.extension != "json") continue
            val rel = file.relativeTo(fontsDir).invariantSeparatorsPathString
            val key = "assets/$NAMESPACE/font/$rel"
            check(key !in files) { "content/fonts/$rel collides with $key already built (the HUD merge owns font/default.json)" }
            files[key] = Files.readAllBytes(file)
        }
    }

    /**
     * Hand-written pack files shipped as they are (ADR-0250): `content/pack-assets/<path>` becomes `<path>`
     * in the zip, and `<path>` must start with `assets/`. Used for the `astera:skinrig` item definition. A path another section already produced is refused.
     */
    private fun addVerbatim(files: MutableMap<String, ByteArray>) {
        val root = contentDir.resolve(VERBATIM_DIR)
        for (file in filesUnder(VERBATIM_DIR)) {
            val rel = file.relativeTo(root).invariantSeparatorsPathString
            check(rel.startsWith("assets/")) { "content/$VERBATIM_DIR/$rel must live under assets/" }
            check(rel !in files) { "content/$VERBATIM_DIR/$rel collides with a pack file already built" }
            files[rel] = Files.readAllBytes(file)
        }
    }

    /**
     * Retextured vanilla particle types (ADR-0274): `:tools:item-art` writes `content/particle-skins/generated/`
     * with `astera_<channel>_<i>.png` frames and a vanilla-format `<type>.json` sprite list per skinned type.
     * The PNGs ship as `assets/minecraft/textures/particle/` (the particle atlas reads that directory), the lists as
     * `assets/minecraft/particles/<type>.json`. A list that names a PNG that is not there fails the build (the client
     * would otherwise render the missing-texture checkerboard for every particle of that type), as does a list that
     * collides with a verbatim file.
     */
    private fun addParticleSkins(files: MutableMap<String, ByteArray>) {
        val dir = contentDir.resolve(PARTICLE_SKINS_DIR)
        for (file in filesUnder(PARTICLE_SKINS_DIR)) {
            val rel = file.relativeTo(dir).invariantSeparatorsPathString
            if (!rel.startsWith("generated/")) continue
            val name = rel.removePrefix("generated/")
            when {
                file.extension == "png" -> files["assets/minecraft/textures/particle/$name"] = Files.readAllBytes(file)
                file.extension == "json" -> {
                    val key = "assets/minecraft/particles/$name"
                    check(key !in files) { "content/$PARTICLE_SKINS_DIR/$rel collides with $key already built" }
                    files[key] = Files.readAllBytes(file)
                }
            }
        }
        for ((key, bytes) in files.toSortedMap()) {
            if (!key.startsWith("assets/minecraft/particles/") || !key.endsWith(".json")) continue
            for (texture in mapper.readTree(bytes).path("textures")) {
                val sprite = texture.asText().removePrefix("minecraft:")
                check("assets/minecraft/textures/particle/$sprite.png" in files) {
                    "$key lists '$texture' but content/$PARTICLE_SKINS_DIR/generated/$sprite.png is missing (run :tools:item-art:run)"
                }
            }
        }
    }

    /**
     * Core shader overrides (ADR-0250): `content/shader-patches/core/<file>.patch` holds only Astera's additions
     * and anchors; it is applied to the vanilla `core/<file>` of the target client at build time, so no Mojang
     * source is committed. A missing or ambiguous anchor fails the build.
     */
    private fun addPatchedShaders(files: MutableMap<String, ByteArray>) {
        val root = contentDir.resolve(PATCH_DIR)
        for (file in filesUnder(PATCH_DIR)) {
            if (!file.name.endsWith(".patch")) continue
            val rel = file.relativeTo(root).invariantSeparatorsPathString.removeSuffix(".patch")
            check(rel.startsWith("core/") && rel.count { it == '/' } == 1) { "content/$PATCH_DIR/$rel.patch must be core/<shader file>.patch" }
            val shader = rel.removePrefix("core/")
            val key = "assets/minecraft/shaders/$rel"
            check(key !in files) { "content/$PATCH_DIR/$rel.patch collides with a pack file already built" }
            files[key] = ShaderPatch.apply(shader, vanilla.coreShader(shader), Files.readString(file)).toByteArray()
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

        /** Subdirectory of `content/textures/` holding generator inputs (ADR-0238); never packed. */
        public const val SOURCES_DIR: String = "sources"

        /** Directory of files copied into the zip unchanged (shader overrides, special item definitions). */
        public const val VERBATIM_DIR: String = "pack-assets"

        /** Retextured vanilla particle types: generated frames + sprite lists live in `<dir>/generated/` (ADR-0274). */
        public const val PARTICLE_SKINS_DIR: String = "particle-skins"

        /** Shader patches applied to the vanilla client's shaders at build time (ADR-0250). */
        public const val PATCH_DIR: String = "shader-patches"

        /** Model directory (under `models/`) that `:tools:item-art` writes. */
        public const val GENERATED_ITEM_DIR: String = "generated/items"

        /**
         * Pack format window, `[major, minor]` pairs (26.x versions resource packs
         * as `major.minor`; `min_format` / `max_format` replaced `pack_format` in
         * 1.21.9). Source: `version.json` inside the Paper 26.3 server jar,
         * `pack_version.resource_major = 97`, `resource_minor = 1`. The window is
         * 97.0 - 97.1: every 26.3 client loads the pack silently, a different
         * major only earns the incompatibility warning (a server pack still loads).
         */
        public val MIN_FORMAT: List<Int> = listOf(97, 0)
        public val MAX_FORMAT: List<Int> = listOf(97, 1)

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

        /** Atlas name to texture directories (relative to `textures/`) the pack's PNGs live in. */
        internal fun atlasDirectories(paths: Collection<String>): Map<String, List<String>> {
            val prefix = "assets/$NAMESPACE/textures/"
            val items = sortedSetOf<String>()
            val blocks = sortedSetOf<String>()
            for (path in paths) {
                if (!path.startsWith(prefix) || !path.endsWith(".png")) continue
                val parts = path.removePrefix(prefix).split('/')
                when {
                    parts.size >= 3 && parts[0] == "generated" && parts[1] == "items" -> items += "generated/items"
                    parts.size >= 2 && parts[0] == "items" -> items += "items"
                    parts.size >= 2 && parts[0] == "blocks" -> blocks += "blocks"
                }
            }
            val out = sortedMapOf<String, List<String>>()
            if (items.isNotEmpty()) out["items"] = items.toList()
            if (blocks.isNotEmpty()) out["blocks"] = blocks.toList()
            return out
        }

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
