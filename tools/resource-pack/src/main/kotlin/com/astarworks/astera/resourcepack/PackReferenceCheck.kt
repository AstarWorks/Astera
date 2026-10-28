package com.astarworks.astera.resourcepack

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Finds every reference in a built pack that the client could not resolve (SKL-12): such a reference renders as the
 * purple-black missing texture / missing model. Checked:
 *
 *  - `assets/<ns>/items/(name).json`: every `model` it names exists as a model;
 *  - `assets/<ns>/models/(any depth)`: `parent` exists; every non-`#` texture exists and, for the pack's own textures, lies in a
 *    directory some atlas source covers (the PNG would otherwise exist but never be stitched); every `#name` an element face
 *    uses is bound along the parent chain;
 *  - `assets/<ns>/font/(any depth)`: bitmap `file` and `reference` targets;
 *  - atlas `directory` sources have at least one PNG, `single` sources exist;
 *  - `.png.mcmeta` belongs to a PNG; `sounds.json` names existing `.ogg` files; particle sprite lists name existing textures.
 *
 * Vanilla (`minecraft:`) targets that the pack does not ship are valid only when they exist in the vanilla client jar; when
 * [vanilla] is null they are not checked and counted in [Result.vanillaSkipped].
 */
public object PackReferenceCheck {

    public class Result(public val problems: List<String>, public val vanillaSkipped: Int)

    private val mapper = ObjectMapper()

    public fun check(pack: Map<String, ByteArray>, vanilla: Set<String>?): Result {
        val problems = sortedSetOf<String>()
        var skipped = 0

        fun exists(ns: String, kind: String, path: String, ext: String, from: String, what: String) {
            val key = "assets/$ns/$kind/$path$ext"
            if (key in pack) return
            if (ns == "minecraft") {
                if (vanilla == null) { skipped++; return }
                if (key in vanilla) return
            }
            problems += "$from: $what '$ns:$path' not found ($key)"
        }

        fun split(ref: String): Pair<String, String> =
            if (':' in ref) ref.substringBefore(':') to ref.substringAfter(':') else "minecraft" to ref

        fun json(path: String): JsonNode? = runCatching { mapper.readTree(pack.getValue(path)) }.getOrElse {
            problems += "$path: not valid JSON (${it.message?.lineSequence()?.firstOrNull()})"; null
        }

        val atlasDirs = sortedSetOf<String>()
        for (atlas in pack.keys.filter { it.startsWith("assets/") && "/atlases/" in it && it.endsWith(".json") }) {
            val doc = json(atlas) ?: continue
            val ns = atlas.removePrefix("assets/").substringBefore('/')
            for (source in doc.path("sources")) {
                val type = source.path("type").asText().removePrefix("minecraft:")
                when (type) {
                    "directory" -> {
                        val dir = source.path("source").asText()
                        atlasDirs += dir
                        if (ns == "astera" && pack.keys.none { it.startsWith("assets/astera/textures/$dir/") && it.endsWith(".png") }) {
                            problems += "$atlas: directory source '$dir' holds no PNG"
                        }
                    }
                    "single" -> {
                        val (rns, rp) = split(source.path("resource").asText())
                        exists(rns, "textures", rp, ".png", atlas, "single source")
                    }
                }
            }
        }

        fun covered(texturePath: String): Boolean = atlasDirs.any { texturePath.startsWith("$it/") }

        // ---- model parent chain -------------------------------------------------------------------------------------
        fun bound(modelKey: String, depth: Int = 0): Set<String> {
            if (depth > 16) return emptySet()
            val doc = (if (modelKey in pack) json(modelKey) else null) ?: return emptySet()
            val own = doc.path("textures").fieldNames().asSequence().toSet()
            val parent = doc.path("parent").asText("")
            if (parent.isEmpty()) return own
            val (pns, pp) = split(parent)
            return own + bound("assets/$pns/models/$pp.json", depth + 1)
        }

        for (path in pack.keys.sorted()) {
            if (!path.startsWith("assets/")) continue
            val ns = path.removePrefix("assets/").substringBefore('/')
            val rest = path.removePrefix("assets/$ns/")
            when {
                rest.startsWith("items/") && path.endsWith(".json") -> {
                    val doc = json(path) ?: continue
                    fun walk(node: JsonNode) {
                        if (node.isObject) {
                            val m = node.get("model")
                            if (m != null && m.isTextual) {
                                val (mns, mp) = split(m.asText())
                                exists(mns, "models", mp, ".json", path, "model")
                            }
                            node.elements().forEach(::walk)
                        } else if (node.isArray) node.forEach(::walk)
                    }
                    walk(doc)
                }
                rest.startsWith("models/") && path.endsWith(".json") -> {
                    val doc = json(path) ?: continue
                    val parent = doc.path("parent").asText("")
                    if (parent.isNotEmpty() && !parent.removePrefix("minecraft:").startsWith("builtin/")) {
                        val (pns, pp) = split(parent)
                        exists(pns, "models", pp, ".json", path, "parent")
                    }
                    for ((slot, ref) in doc.path("textures").fields().asSequence().map { it.key to it.value.asText() }) {
                        if (ref.startsWith("#")) continue
                        val (tns, tp) = split(ref)
                        exists(tns, "textures", tp, ".png", path, "texture '$slot'")
                        if (tns == "astera" && "assets/astera/textures/$tp.png" in pack && !covered(tp)) {
                            problems += "$path: texture '$slot' astera:$tp is in no atlas source directory (renders missing)"
                        }
                    }
                    val have = bound(path)
                    for (element in doc.path("elements")) for (face in element.path("faces")) {
                        val ref = face.path("texture").asText("")
                        if (ref.startsWith("#") && ref.substring(1) !in have) problems += "$path: face uses unbound texture slot '$ref'"
                    }
                    for (slot in doc.path("textures").fields().asSequence()) {
                        var ref = slot.value.asText()
                        var hops = 0
                        while (ref.startsWith("#") && hops++ < 16) ref = doc.path("textures").path(ref.substring(1)).asText("")
                        if (ref.startsWith("#")) problems += "$path: texture slot '${slot.key}' points at unbound '$ref'"
                    }
                }
                rest.startsWith("font/") && path.endsWith(".json") -> {
                    val doc = json(path) ?: continue
                    for (provider in doc.path("providers")) when (provider.path("type").asText().removePrefix("minecraft:")) {
                        "bitmap" -> {
                            val (fns, fp) = split(provider.path("file").asText())
                            exists(fns, "textures", fp, "", path, "bitmap file")
                        }
                        "reference" -> {
                            val (fns, fp) = split(provider.path("id").asText())
                            exists(fns, "font", fp, ".json", path, "font reference")
                        }
                    }
                }
                path.endsWith(".png.mcmeta") && path.removeSuffix(".mcmeta") !in pack ->
                    problems += "$path: .png.mcmeta without its PNG"
                rest == "sounds.json" -> {
                    val doc = json(path) ?: continue
                    for ((event, entry) in doc.fields().asSequence().map { it.key to it.value }) for (sound in entry.path("sounds")) {
                        val name = if (sound.isTextual) sound.asText() else sound.path("name").asText()
                        val (sns, sp) = split(name)
                        exists(sns, "sounds", sp, ".ogg", path, "sound event '$event'")
                    }
                }
                ns == "minecraft" && rest.startsWith("particles/") && path.endsWith(".json") -> {
                    val doc = json(path) ?: continue
                    for (texture in doc.path("textures")) {
                        val (tns, tp) = split(texture.asText())
                        exists(tns, "textures/particle", tp, ".png", path, "particle sprite")
                    }
                }
            }
        }
        return Result(problems.toList(), skipped)
    }

    /** Every file name inside the vanilla client jar of the version the build targets, or null when none is available. */
    public fun vanillaAssets(): Set<String>? {
        val jar = locateClientJar() ?: return null
        return ZipFile(jar.toFile()).use { zip -> zip.entries().asSequence().map { it.name }.filter { it.startsWith("assets/") }.toSet() }
    }

    private fun locateClientJar(): Path? {
        System.getenv("ASTERA_VANILLA_CLIENT_JAR")?.takeIf { it.isNotBlank() }?.let(Path::of)?.takeIf(Files::isRegularFile)?.let { return it }
        val version = System.getProperty("astera.minecraftVersion") ?: return null
        val home = System.getProperty("user.home") ?: return null
        return listOf(
            "$home/.cache/astera-vfx-shot/mcdir/versions/$version/$version.jar",
            "$home/.minecraft/versions/$version/$version.jar",
        ).map(Path::of).firstOrNull(Files::isRegularFile)
    }
}
