package com.astarworks.astera.itemart

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ItemArtTest {

    private val contentDir: Path = Path.of(checkNotNull(System.getProperty("astera.contentDir")))
    private val weapons = ItemArt.weapons(contentDir)

    @Test
    fun `every skill-bearing item is a weapon with art`() {
        assertThat(weapons.map { it.id }).contains(
            "pistol", "rifle", "shotgun", "knight-sword", "greatsword", "dagger",
            "frost-staff", "fire-staff", "thunder-staff", "verdant-staff",
        )
    }

    @Test
    fun `the generator is deterministic`() {
        val first = ItemArt.renderAll(contentDir)
        val second = ItemArt.renderAll(contentDir)
        assertThat(first.keys).isEqualTo(second.keys)
        for ((path, bytes) in first) assertThat(bytes).`as`(path).isEqualTo(second.getValue(path))
    }

    @Test
    fun `every weapon has a committed 32x32 texture and a model that points at it`() {
        for (item in weapons) {
            val texture = contentDir.resolve("${ItemArt.TEXTURE_DIR}/${item.id}.png")
            val model = contentDir.resolve("${ItemArt.MODEL_DIR}/${item.id}.model.json")
            assertThat(texture).`as`("texture of ${item.id}").exists()
            assertThat(model).`as`("model of ${item.id}").exists()
            val image = ItemArt.decode(Files.readAllBytes(texture))
            assertThat(image.width).isEqualTo(32)
            assertThat(image.height).isEqualTo(32)
            val opaque = ItemArt.pixels(image).count { it ushr 24 == 0xFF }
            assertThat(opaque).`as`("${item.id} has visible pixels").isGreaterThan(60)
            val json = Files.readString(model)
            assertThat(json).contains("\"layer0\": \"astera:generated/items/${item.id}\"")
            assertThat(json).contains("\"parent\": \"minecraft:item/")
        }
    }

    @Test
    fun `the committed art is exactly what the generator draws`() {
        assertThat(ItemArt.stale(contentDir)).isEmpty()
    }

    @Test
    fun `icons of different items differ`() {
        val pixels = weapons.map { ItemArt.pixels(ItemArt.render(it)) }
        assertThat(pixels.toSet()).hasSize(weapons.size)
    }

    @Test
    fun `swords and staffs are held like tools and guns are flat icons`() {
        assertThat(ItemArt.modelJson(ItemRecord("knight-sword", "IRON_SWORD"))).contains("item/handheld")
        assertThat(ItemArt.modelJson(ItemRecord("fire-staff", "BLAZE_ROD"))).contains("item/handheld")
        assertThat(ItemArt.modelJson(ItemRecord("pistol", "IRON_HORSE_ARMOR"))).contains("item/generated")
    }

    @Test
    fun `an unknown weapon still gets a design from its material`() {
        val item = ItemRecord("moon-staff", "BLAZE_ROD")
        assertThat(ItemArt.designFor(item)).isInstanceOf(Design.Staff::class.java)
        assertThat(ItemArt.pixels(ItemArt.render(item))).isNotEmpty()
    }
}
