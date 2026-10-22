package com.astarworks.astera.hudpreview

import kotlinx.serialization.json.*
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import java.awt.Color
import java.awt.Graphics2D
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import kotlin.math.ceil

internal data class Letter(val codepoint: Int, val font: String, val color: Int, val bold: Boolean, val italic: Boolean)

internal class HudText(private val fonts: PackFont) {
    private val mini = MiniMessage.miniMessage()
    private val tinted = mutableMapOf<Pair<Glyph, Int>, BufferedImage>()

    fun parse(raw: String): List<Letter> = flatten(mini.deserialize(raw))

    fun decode(node: JsonElement?): List<Letter> {
        if (node == null || node is JsonNull) return emptyList()
        val obj = node.jsonObject
        obj["parts"]?.let { parts ->
            val spacing = obj.getValue("spacing").jsonPrimitive.int.coerceIn(0, 8)
            return parts.jsonArray.flatMapIndexed { index, part ->
                (if (index > 0) parse(" ".repeat(spacing)) else emptyList()) + decode(part)
            }
        }
        val raw = obj.getValue("text").jsonPrimitive.content
        val component = if (obj["font"] == null) mini.deserialize(raw)
            else Component.text(raw).font(Key.key(obj.getValue("font").jsonPrimitive.content))
        val styled = obj["color"]?.let { component.color(TextColor.color(it.jsonPrimitive.int)) } ?: component
        return flatten(styled)
    }

    private fun flatten(component: Component, parent: Style = Style.empty()): List<Letter> {
        val style = component.style().merge(parent, Style.Merge.Strategy.IF_ABSENT_ON_TARGET)
        val own = if (component is TextComponent) component.content().codePoints().toArray().map {
            Letter(it, style.font()?.asString() ?: "minecraft:default", style.color()?.value() ?: 0xFFFFFF,
                style.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE,
                style.decoration(TextDecoration.ITALIC) == TextDecoration.State.TRUE)
        } else emptyList()
        return own + component.children().flatMap { flatten(it, style) }
    }

    fun plain(text: List<Letter>): String = text.joinToString("") { String(Character.toChars(it.codepoint)) }
    private fun advance(letter: Letter): Float = fonts.glyph(letter.font, letter.codepoint).advance + if (letter.bold) 1 else 0
    fun width(text: List<Letter>): Int = ceil(text.sumOf { advance(it).toDouble() }).toInt()

    fun draw(g: Graphics2D, text: List<Letter>, x: Int, y: Int, shadow: Boolean = true) {
        if (shadow) drawPass(g, text, x + 1f, y + 1f, true)
        drawPass(g, text, x.toFloat(), y.toFloat(), false)
    }

    private fun drawPass(g: Graphics2D, text: List<Letter>, x: Float, y: Float, shadow: Boolean) {
        var pen = x
        for (letter in text) {
            val glyph = fonts.glyph(letter.font, letter.codepoint)
            glyph.image?.let { source ->
                val color = if (shadow) (letter.color and 0xFCFCFC) shr 2 else letter.color
                val image = tinted.getOrPut(glyph to color) {
                    BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB).also { target ->
                        val tint = Color(color)
                        for (iy in 0 until source.height) for (ix in 0 until source.width) {
                            val pixel = Color(source.getRGB(ix, iy), true)
                            target.setRGB(ix, iy, Color(pixel.red * tint.red / 255, pixel.green * tint.green / 255,
                                pixel.blue * tint.blue / 255, pixel.alpha).rgb)
                        }
                    }
                }
                val transform = AffineTransform.getTranslateInstance(pen.toDouble(), glyph.top(y).toDouble())
                if (letter.italic) {
                    transform.translate((1f - (glyph.top(y) - y) / 4f).toDouble(), 0.0)
                    transform.shear(-0.25, 0.0)
                }
                transform.scale((glyph.height / source.height).toDouble(), (glyph.height / source.height).toDouble())
                g.drawImage(image, transform, null)
                if (letter.bold) {
                    val bold = AffineTransform.getTranslateInstance(1.0, 0.0)
                    bold.concatenate(transform)
                    g.drawImage(image, bold, null)
                }
            }
            pen += advance(letter)
        }
    }
}
