package com.astarworks.astera.vfxpreview

import com.astarworks.astera.core.application.config.SpriteYamlConfig
import com.astarworks.astera.core.application.config.VfxYamlConfig
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.effect.vfx.SpriteFrames
import com.astarworks.astera.core.domain.effect.vfx.SpriteId
import com.astarworks.astera.core.domain.effect.vfx.VfxDefinition
import com.astarworks.astera.core.domain.effect.vfx.VfxFreeze
import com.astarworks.astera.core.domain.effect.vfx.VfxTimeline
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.charleskorn.kaml.PolymorphismStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.system.exitProcess

data class PreviewOptions(
    val pitchDeg: Double = 0.0,
    val followPath: Boolean = false,
    val view: ViewMode = ViewMode.DEFAULT,
    val seed: Long = 1L,
    /** Hit-stop windows handed to the planner (`--freeze at:ticks`, repeatable). */
    val freezes: List<VfxFreeze> = emptyList(),
    val width: Int = 960,
    val height: Int = 720,
    val sheetColumns: Int = 6,
    val sheetWidth: Int = 1800,
    /** Flipbook frame counts (`content/sprites`); unknown sprites plan as one frame. */
    val sprites: SpriteFrames = SpriteFrames.NONE,
    /** Glyph art of those sprites (`content/textures/generated/sprites`). */
    val spriteStrips: SpriteStrips = SpriteStrips.default(),
)

/** Frames of one effect, rendered but not yet written. */
class RenderedEffect(val id: String, val replay: PlanReplay, val frames: List<BufferedImage>)

object VfxPreview {
    /** Blocks the simulated caster walks per tick with `--follow-path` (only effects with `follow: true` move). */
    const val FOLLOW_SPEED: Double = 0.45

    private val yaml = Yaml(configuration = YamlConfiguration(polymorphismStyle = PolymorphismStyle.Property))

    fun load(file: File): VfxDefinition {
        val config = yaml.decodeFromString(VfxYamlConfig.serializer(), file.readText())
        return when (val r = config.toDefinition()) {
            is Result.Success -> r.value
            is Result.Failure -> error("${file.name}: ${r.error.message}")
        }
    }

    /** Frame counts of every the `<contentDir>/sprites` YAML files. */
    fun loadSprites(spritesDir: File): SpriteFrames {
        val frames = spritesDir.listFiles { f -> f.extension == "yaml" }.orEmpty().associate { f ->
            val def = yaml.decodeFromString(SpriteYamlConfig.serializer(), f.readText()).toDefinition()
            def.id to def.frames
        }
        return SpriteFrames { id: SpriteId -> frames[id] }
    }

    fun plan(def: VfxDefinition, opts: PreviewOptions) =
        VfxTimeline.plan(def, Frame.fromYawPitch(Vec3.ZERO, 0.0, opts.pitchDeg), opts.seed, 1.0, opts.sprites, opts.freezes)

    fun render(def: VfxDefinition, opts: PreviewOptions): RenderedEffect {
        val plan = plan(def, opts)
        val replay = PlanReplay(plan)
        val ticks = 0 until plan.durationTicks
        fun shiftAt(t: Int) = if (opts.followPath && plan.follow) Vec3(0.0, 0.0, FOLLOW_SPEED * t) else Vec3.ZERO

        // Frame once for the whole effect so tiles are comparable.
        val all = ticks.flatMap { SceneBuilder.points(SceneBuilder.build(replay, it, shiftAt(it))) }
        val camera = Camera.fit(opts.view, all, opts.width, opts.height)
        val renderer = Renderer(camera, def.id.value, opts.spriteStrips)
        val frames = ticks.map { t -> renderer.render(SceneBuilder.build(replay, t, shiftAt(t)), t, shiftAt(t)) }
        return RenderedEffect(def.id.value, replay, frames)
    }

    fun write(effect: RenderedEffect, outDir: File, opts: PreviewOptions) {
        outDir.mkdirs()
        effect.frames.forEachIndexed { t, img -> ImageIO.write(img, "png", File(outDir, "frame_%03d.png".format(t))) }
        ImageIO.write(sheet(effect, opts), "png", File(outDir, "sheet.png"))
    }

    fun sheet(effect: RenderedEffect, opts: PreviewOptions): BufferedImage {
        val step = if (effect.frames.size > 36) 2 else 1
        val picks = effect.frames.indices.filter { it % step == 0 }
        val cols = opts.sheetColumns.coerceAtMost(picks.size).coerceAtLeast(1)
        val rows = (picks.size + cols - 1) / cols
        val tileW = opts.sheetWidth / cols
        val tileH = tileW * opts.height / opts.width
        val sheet = BufferedImage(tileW * cols, tileH * rows, BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.color = Color(0x0B0C12)
        g.fillRect(0, 0, sheet.width, sheet.height)
        picks.forEachIndexed { i, t ->
            val x = (i % cols) * tileW
            val y = (i / cols) * tileH
            g.drawImage(effect.frames[t], x, y, tileW - 2, tileH - 2, null)
        }
        g.dispose()
        return sheet
    }
}

fun main(args: Array<String>) {
    var opts = PreviewOptions()
    var all = false
    val ids = mutableListOf<String>()
    var contentDir = File("content")
    var outRoot = File("build/vfx-preview")
    var i = 0
    fun value(flag: String): String = args.getOrNull(++i) ?: usage("$flag needs a value")
    while (i < args.size) {
        when (val a = args[i]) {
            "--all" -> all = true
            "--pitch" -> opts = opts.copy(pitchDeg = value(a).toDoubleOrNull() ?: usage("bad --pitch"))
            "--follow-path" -> opts = opts.copy(followPath = true)
            "--view" -> opts = opts.copy(view = ViewMode.parse(value(a)) ?: usage("--view default|top|side|front|behind|eye"))
            "--seed" -> opts = opts.copy(seed = value(a).toLongOrNull() ?: usage("bad --seed"))
            "--freeze" -> {
                val (at, ticks) = value(a).split(":").map { it.toIntOrNull() ?: usage("bad --freeze (at:ticks)") }
                opts = opts.copy(freezes = opts.freezes + VfxFreeze(at, ticks))
            }
            "--cols" -> opts = opts.copy(sheetColumns = value(a).toIntOrNull() ?: usage("bad --cols"))
            "--content" -> contentDir = File(value(a))
            "--out" -> outRoot = File(value(a))
            else -> if (a.startsWith("--")) usage("unknown option $a") else ids += a
        }
        i++
    }
    opts = opts.copy(sprites = VfxPreview.loadSprites(File(contentDir, "sprites")), spriteStrips = SpriteStrips(contentDir))
    val vfxDir = File(contentDir, "vfx")
    val files = if (all) {
        vfxDir.listFiles { f -> f.extension == "yaml" }?.sortedBy { it.name }.orEmpty()
    } else {
        ids.map { File(vfxDir, "$it.yaml") }
    }
    if (files.isEmpty()) usage("no effect selected (give an id or --all; looked in ${vfxDir.absolutePath})")
    var failed = false
    for (f in files) {
        if (!f.isFile) {
            System.err.println("missing ${f.path}")
            failed = true
            continue
        }
        try {
            val def = VfxPreview.load(f)
            val out = File(outRoot, def.id.value)
            val effect = VfxPreview.render(def, opts)
            VfxPreview.write(effect, out, opts)
            println("${def.id.value}: ${effect.frames.size} frames -> ${File(out, "sheet.png").path}")
        } catch (e: Exception) {
            System.err.println("${f.name}: ${e.message}")
            failed = true
        }
    }
    if (failed) exitProcess(1)
}

private fun usage(msg: String): Nothing {
    System.err.println(
        "$msg\nusage: vfx-preview [<id>... | --all] [--pitch deg] [--follow-path] " +
            "[--view default|top|side|front|behind|eye] [--freeze at:ticks] [--seed n] [--cols n] [--content dir] [--out dir]",
    )
    exitProcess(2)
}
