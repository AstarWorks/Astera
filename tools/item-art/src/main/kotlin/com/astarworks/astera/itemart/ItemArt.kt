package com.astarworks.astera.itemart

import org.yaml.snakeyaml.Yaml
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.name

/** Element palettes of the staffs. */
internal enum class Element(val core: Ramp, val deep: Ramp, val glow: Int, val spark: List<Int>) {
    FIRE(
        Ramp(0xFF9B1C12.toInt(), 0xFFE8431A.toInt(), 0xFFFF8F2B.toInt(), 0xFFFFE066.toInt()),
        Ramp(0xFF6E0F10.toInt(), 0xFFB82A12.toInt(), 0xFFE8431A.toInt(), 0xFFFF8F2B.toInt()),
        0xFFFF6A1A.toInt(), listOf(0xFFFFB03A.toInt(), 0xFFFF5A1F.toInt(), 0xFFFFE066.toInt()),
    ),
    FROST(
        Ramp(0xFF2F7FA8.toInt(), 0xFF63C7E8.toInt(), 0xFFB5EDF8.toInt(), 0xFFFFFFFF.toInt()),
        Ramp(0xFF1E5C86.toInt(), 0xFF3C9FCB.toInt(), 0xFF63C7E8.toInt(), 0xFFB5EDF8.toInt()),
        0xFF7FDDF5.toInt(), listOf(0xFFFFFFFF.toInt(), 0xFFB5EDF8.toInt()),
    ),
    THUNDER(
        Ramp(0xFF5B2A9A.toInt(), 0xFF8E4BE0.toInt(), 0xFFC08CFF.toInt(), 0xFFEBD6FF.toInt()),
        Ramp(0xFF3C1B70.toInt(), 0xFF5B2A9A.toInt(), 0xFF8E4BE0.toInt(), 0xFFC08CFF.toInt()),
        0xFFB070FF.toInt(), listOf(0xFFFFF04D.toInt(), 0xFFFFFFFF.toInt(), 0xFFC08CFF.toInt()),
    ),
    VERDANT(
        Ramp(0xFF1F6B2A.toInt(), 0xFF3FB04A.toInt(), 0xFF8EE36B.toInt(), 0xFFE2FFB8.toInt()),
        Ramp(0xFF174A22.toInt(), 0xFF2A8A38.toInt(), 0xFF3FB04A.toInt(), 0xFF8EE36B.toInt()),
        0xFF6BE070.toInt(), listOf(0xFFE2FFB8.toInt(), 0xFF8EE36B.toInt()),
    ),
    ARCANE(
        Ramp(0xFF6A2A78.toInt(), 0xFFB04CC8.toInt(), 0xFFE08CF0.toInt(), 0xFFFFE0FF.toInt()),
        Ramp(0xFF45194F.toInt(), 0xFF84389A.toInt(), 0xFFB04CC8.toInt(), 0xFFE08CF0.toInt()),
        0xFFD070E8.toInt(), listOf(0xFFFFE0FF.toInt(), 0xFFE08CF0.toInt()),
    ),
}

internal enum class GunKind { PISTOL, RIFLE, SHOTGUN }

internal enum class ModelStyle(val parent: String) {
    HANDHELD("minecraft:item/handheld"),
    GENERATED("minecraft:item/generated"),
}

/** What to draw for one item. */
internal sealed interface Design {
    val style: ModelStyle

    data class Gun(val kind: GunKind, val accent: Int) : Design { override val style = ModelStyle.GENERATED }

    data class Blade(
        val length: Double, val halfWidth: Double, val guardHalfWidth: Double,
        val blade: Ramp, val guard: Ramp, val grip: Ramp, val fuller: Boolean, val halo: Int? = null,
    ) : Design { override val style = ModelStyle.HANDHELD }

    data class Staff(val element: Element) : Design { override val style = ModelStyle.HANDHELD }

    /** Hand-drawn persona weapon silhouette (ADR-0221). */
    data class Persona(val art: PersonaArt) : Design { override val style = art.style }
}

/** A weapon read from `content/items` YAML files: an item that binds at least one skill. */
internal data class ItemRecord(val id: String, val material: String)

internal object ItemArt {
    const val TEXTURE_DIR: String = "textures/generated/items"
    const val MODEL_DIR: String = "models/generated/items"
    const val RESOURCE_PREFIX: String = "generated/items"
    const val NAMESPACE: String = "astera"

    private val STEEL = Ramp(0xFF4C5568.toInt(), 0xFF8C96A8.toInt(), 0xFFC6CEDB.toInt(), 0xFFF4F8FC.toInt())
    private val GUNMETAL = Ramp(0xFF23262F.toInt(), 0xFF454B58.toInt(), 0xFF6B7384.toInt(), 0xFF9AA3B5.toInt())
    private val DARK_STEEL = Ramp(0xFF2E3340.toInt(), 0xFF5A6273.toInt(), 0xFF8790A3.toInt(), 0xFFB9C1D1.toInt())
    private val GOLD = Ramp(0xFF8C5A0E.toInt(), 0xFFD9A020.toInt(), 0xFFFFD34D.toInt(), 0xFFFFF2A8.toInt())
    private val LEATHER = Ramp(0xFF3B2314.toInt(), 0xFF6B4023.toInt(), 0xFF946236.toInt(), 0xFFB8854D.toInt())
    private val CRIMSON = Ramp(0xFF4A0F14.toInt(), 0xFF8E1B24.toInt(), 0xFFC03340.toInt(), 0xFFE86A70.toInt())
    private val WOODS = listOf(
        Ramp(0xFF3E2814.toInt(), 0xFF70491F.toInt(), 0xFF9A6A33.toInt(), 0xFFC49355.toInt()),
        Ramp(0xFF4A2A18.toInt(), 0xFF7E4A2A.toInt(), 0xFFA8693E.toInt(), 0xFFCF9162.toInt()),
        Ramp(0xFF3A2C1A.toInt(), 0xFF68502E.toInt(), 0xFF8E7244.toInt(), 0xFFB89A66.toInt()),
    )
    private val BLACK_METAL = Ramp(0xFF0F1117.toInt(), 0xFF262A34.toInt(), 0xFF3F4555.toInt(), 0xFF626A7D.toInt())

    private val DESIGNS: Map<String, Design> = mapOf(
        "pistol" to Design.Gun(GunKind.PISTOL, 0xFF39C6E8.toInt()),
        "rifle" to Design.Gun(GunKind.RIFLE, 0xFF4CC95A.toInt()),
        "rtm-blaster" to Design.Gun(GunKind.RIFLE, 0xFFD070E8.toInt()),
        "shotgun" to Design.Gun(GunKind.SHOTGUN, 0xFFE0412F.toInt()),
        "knight-sword" to Design.Blade(25.5, 1.9, 4.2, STEEL, GOLD, LEATHER, fuller = true, halo = 0xFFFFE066.toInt()),
        "greatsword" to Design.Blade(30.0, 2.8, 5.8, DARK_STEEL, CRIMSON, BLACK_METAL, fuller = true, halo = 0xFFE8434C.toInt()),
        "dagger" to Design.Blade(19.5, 1.5, 2.7, GUNMETAL, DARK_STEEL, LEATHER, fuller = false),
        "couture-rapier" to Design.Persona(PersonaArt.RAPIER),
        "gossamer-katana" to Design.Persona(PersonaArt.KATANA),
        "vendetta-rifle" to Design.Persona(PersonaArt.VENDETTA_RIFLE),
        "ember-gauntlet" to Design.Persona(PersonaArt.GAUNTLET),
        "verdict-hammer" to Design.Persona(PersonaArt.HAMMER),
        "crimson-chain-axe" to Design.Persona(PersonaArt.CHAIN_AXE),
        "sigil-rifle" to Design.Persona(PersonaArt.SIGIL_RIFLE),
        "orrery-staff" to Design.Persona(PersonaArt.ORRERY),
        "edict-blade" to Design.Persona(PersonaArt.EDICT),
        "bastion-greatsword" to Design.Blade(
            28.5, 3.5, 7.0, Ramp(0xFFB8841A.toInt(), 0xFFFFD23A.toInt(), 0xFFFFE680.toInt(), 0xFFFFFFFF.toInt()),
            Ramp(0xFF7A3A06.toInt(), 0xFFE07818.toInt(), 0xFFFFA83A.toInt(), 0xFFFFD880.toInt()), BLACK_METAL,
            fuller = true, halo = 0xFFFFD23A.toInt(),
        ),
        "fire-staff" to Design.Staff(Element.FIRE),
        "frost-staff" to Design.Staff(Element.FROST),
        "thunder-staff" to Design.Staff(Element.THUNDER),
        "verdant-staff" to Design.Staff(Element.VERDANT),
    )

    /** Hand-picked design, else a best guess from the vanilla base material and the id. */
    fun designFor(item: ItemRecord): Design = DESIGNS[item.id] ?: run {
        val m = item.material.uppercase()
        when {
            m.endsWith("_SWORD") -> Design.Blade(26.0, 1.8, 3.8, STEEL, GOLD, LEATHER, fuller = true)
            m.endsWith("HORSE_ARMOR") || m == "CROSSBOW" -> Design.Gun(GunKind.PISTOL, 0xFFE8B030.toInt())
            else -> Design.Staff(
                when {
                    "fire" in item.id || "flame" in item.id -> Element.FIRE
                    "frost" in item.id || "ice" in item.id -> Element.FROST
                    "thunder" in item.id || "lightning" in item.id -> Element.THUNDER
                    "verdant" in item.id || "nature" in item.id -> Element.VERDANT
                    else -> Element.ARCANE
                },
            )
        }
    }

    /** Items that bind at least one skill (weapons, staffs): they get generated art. */
    fun weapons(contentDir: Path): List<ItemRecord> {
        val dir = contentDir.resolve("items")
        return Files.list(dir).use { s ->
            s.filter { it.name.endsWith(".yaml") }.sorted().toList()
        }.mapNotNull { file ->
            val doc = Yaml().load<Map<String, Any?>>(Files.readString(file)).orEmpty()
            val skills = doc["skills"] as? List<*>
            val id = doc["id"] as? String
            if (id == null || skills.isNullOrEmpty()) null else ItemRecord(id, doc["material"]?.toString().orEmpty())
        }
    }

    /** Every item id under `content/items` (skill-bearing or not). */
    fun allItemIds(contentDir: Path): List<String> = Files.list(contentDir.resolve("items")).use { st ->
        st.filter { it.name.endsWith(".yaml") }.sorted().toList()
    }.mapNotNull { Yaml().load<Map<String, Any?>>(Files.readString(it)).orEmpty()["id"] as? String }

    /** GUI / kit / skill-kind icons (non-weapons) that exist as items and have a design in [Icons]. */
    fun icons(contentDir: Path): List<Pair<String, IconSpec>> =
        allItemIds(contentDir).mapNotNull { id -> Icons.SPECS[id]?.let { id to it } }

    fun iconModelJson(id: String): String =
        "{\n  \"parent\": \"minecraft:item/generated\",\n  \"textures\": {\n    \"layer0\": " +
            "\"$NAMESPACE:$RESOURCE_PREFIX/$id\"\n  }\n}\n"

    fun render(item: ItemRecord): BufferedImage {
        val seed = item.id.fold(1469598103934665603L) { h, c -> (h xor c.code.toLong()) * 1099511628211L }
        val sprite = Sprite(seed)
        val rnd = java.util.Random(seed)
        when (val d = designFor(item)) {
            is Design.Gun -> GunPainter.paint(sprite, d, rnd)
            is Design.Blade -> BladePainter.paint(sprite, d)
            is Design.Staff -> StaffPainter.paint(sprite, d.element, rnd)
            is Design.Persona -> PersonaPainter.paint(sprite, d.art)
        }
        return sprite.render()
    }

    fun modelJson(item: ItemRecord): String =
        "{\n  \"parent\": \"${designFor(item).style.parent}\",\n  \"textures\": {\n    \"layer0\": " +
            "\"$NAMESPACE:$RESOURCE_PREFIX/${item.id}\"\n  }\n}\n"

    fun png(image: BufferedImage): ByteArray {
        val out = ByteArrayOutputStream()
        check(ImageIO.write(image, "png", out)) { "no PNG writer" }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): BufferedImage = ImageIO.read(ByteArrayInputStream(bytes))

    /** Every generated file, keyed by path relative to the content dir. */
    fun renderAll(contentDir: Path): Map<String, ByteArray> {
        val files = sortedMapOf<String, ByteArray>()
        for (item in weapons(contentDir)) {
            files["$TEXTURE_DIR/${item.id}.png"] = png(render(item))
            files["$MODEL_DIR/${item.id}.model.json"] = modelJson(item).toByteArray()
        }
        for ((id, spec) in icons(contentDir)) {
            files["$TEXTURE_DIR/$id.png"] = png(Icons.render(id, spec))
            files["$MODEL_DIR/$id.model.json"] = iconModelJson(id).toByteArray()
        }
        return files
    }

    fun pixels(image: BufferedImage): List<Int> =
        (0 until image.height).flatMap { y -> (0 until image.width).map { x -> image.getRGB(x, y) } }

    /** Paths whose committed content differs from the generator (PNGs compared by pixels). */
    fun stale(contentDir: Path): List<String> = renderAll(contentDir).filter { (rel, bytes) ->
        val file = contentDir.resolve(rel)
        when {
            !Files.isRegularFile(file) -> true
            rel.endsWith(".png") -> pixels(decode(Files.readAllBytes(file))) != pixels(decode(bytes))
            else -> !Files.readAllBytes(file).contentEquals(bytes)
        }
    }.keys.toList()

    /** Rewrites the generated tree; removes generated files of items that no longer exist. */
    fun write(contentDir: Path): Int {
        val files = renderAll(contentDir)
        for (dirRel in listOf(TEXTURE_DIR, MODEL_DIR)) {
            val dir = contentDir.resolve(dirRel)
            if (Files.isDirectory(dir)) Files.list(dir).use { s -> s.forEach { if (contentDir.relativize(it).toString().replace('\\', '/') !in files) Files.delete(it) } }
        }
        for ((rel, bytes) in files) {
            val file = contentDir.resolve(rel)
            Files.createDirectories(file.parent)
            Files.write(file, bytes)
        }
        return files.size
    }
}
