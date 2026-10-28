package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.adapter.paper.skillengine.toParticleCalls
import com.astarworks.astera.core.application.contents.ParticleContents
import com.astarworks.astera.core.application.skillengine.load.ItemFrames
import com.astarworks.astera.core.application.skillengine.load.ItemFramesDecoder
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillParticleGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ParticleBurst
import com.astarworks.astera.core.domain.effect.DisplayKind
import com.astarworks.astera.core.domain.effect.DisplayTransform
import com.astarworks.astera.core.domain.effect.ParticleId
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import java.nio.file.Path

/**
 * Vanilla-parity dump (SKL-17): every shipped skill cast by a scripted caster (feet at the origin, yaw 0, pitch 0) with a
 * dummy 5 blocks ahead, recorded as the calls the Paper adapters hand to Bukkit: each display spawn (kind, item / model /
 * text, billboard, brightness) and write (position, yaw, pitch, the whole transformation, interpolation and teleport
 * durations), each removal and each `spawnParticle` argument set. One JSON object per line; the same event vocabulary as the
 * v1 reference dump so `tools/skill-convert/vanilla_diff.py` can compare the two.
 */
object VanillaDump {
    const val TICKS = 120

    private val content: Path = Path.of(requireNotNull(System.getProperty("astera.sim.contentDir")) { "astera.sim.contentDir is not set" })

    private val tables: Map<String, ItemFrames> by lazy { ItemFramesDecoder.loadDirectory(content.resolve("frames")).first }
    private val particleRegistry: ParticleContents by lazy { ParticleContents().also { it.bootstrap(content.resolve("particles")) } }

    /** Ids of the shipped skills a player can cast (frame-bound `@` children are not casts of their own). */
    fun ids(): List<String> = CastSim(emptyMap()).catalog.ids().filter { '@' !in it }.sorted()

    private fun r(d: Double): String {
        val v = Math.round(d * 1e5) / 1e5
        return (if (v == 0.0) 0.0 else v).toString()
    }

    private fun v3(v: Vec3) = "[${r(v.x)},${r(v.y)},${r(v.z)}]"
    private fun q(q: Quaternion) = "[${r(q.x)},${r(q.y)},${r(q.z)},${r(q.w)}]"
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "'").replace("\n", " ")

    private fun transform(t: DisplayTransform) = "\"tr\":${v3(t.translation)},\"left\":${q(t.rotation)},\"scale\":${v3(t.scale)}"


    private fun ribbonArgb(hex: String, alpha: Double): Int {
        val rgb = hex.removePrefix("#").toInt(16)
        return ((Math.round(alpha * 255.0).toInt().coerceIn(0, 255)) shl 24) or rgb
    }

    /** Rotation of the (rotation * scale) matrix with the given columns: columns normalised, Shepperd's method. */
    private fun rotationOf(c0: Vec3, c1: Vec3, c2: Vec3): Quaternion {
        val a = c0 * (1.0 / maxOf(c0.length(), 1e-9)); val b = c1 * (1.0 / maxOf(c1.length(), 1e-9)); val c = c2 * (1.0 / maxOf(c2.length(), 1e-9))
        val m00 = a.x; val m10 = a.y; val m20 = a.z; val m01 = b.x; val m11 = b.y; val m21 = b.z; val m02 = c.x; val m12 = c.y; val m22 = c.z
        val tr = m00 + m11 + m22
        val qq = if (tr > 0) { val sq = Math.sqrt(tr + 1.0) * 2; Quaternion((m21 - m12) / sq, (m02 - m20) / sq, (m10 - m01) / sq, 0.25 * sq) }
        else if (m00 > m11 && m00 > m22) { val sq = Math.sqrt(1.0 + m00 - m11 - m22) * 2; Quaternion(0.25 * sq, (m01 + m10) / sq, (m02 + m20) / sq, (m21 - m12) / sq) }
        else if (m11 > m22) { val sq = Math.sqrt(1.0 + m11 - m00 - m22) * 2; Quaternion((m01 + m10) / sq, 0.25 * sq, (m12 + m21) / sq, (m02 - m20) / sq) }
        else { val sq = Math.sqrt(1.0 + m22 - m00 - m11) * 2; Quaternion((m02 + m20) / sq, (m12 + m21) / sq, 0.25 * sq, (m10 - m01) / sq) }
        return qq.normalized()
    }

    /** The lines of one cast. */
    fun dump(id: String, ticks: Int = TICKS): List<String> {
        val lines = ArrayList<String>()
        val feet = Vec3.ZERO
        lateinit var sim: CastSim
        val visuals = RecordingVisuals(now = { sim.now }, casterFeet = { feet }, frameTables = tables)
        val sink = object : ISkillParticleGateway {
            override fun emit(burst: ParticleBurst) {
                for (c in toParticleCalls(burst) { particleRegistry.find(ParticleId(it)) }) {
                    val s = c.spec
                    val data = s.color?.toString() ?: ""
                    lines += "{\"t\":${sim.now},\"e\":\"particle\",\"key\":\"${esc(s.key)}\",\"x\":${r(c.at.x)},\"y\":${r(c.at.y)},\"z\":${r(c.at.z)}," +
                        "\"count\":${s.count},\"ox\":${r(s.offset.x)},\"oy\":${r(s.offset.y)},\"oz\":${r(s.offset.z)},\"extra\":${r(s.extra)},\"data\":\"${esc(data)}\",\"scale\":${r(s.scale.toDouble())}}"
                }
            }
        }
        sim = CastSim(mapOf(simVictim(1) to ahead(feet, 0.0, 5.0)), particleGateway = sink, visualGateway = visuals, halfWidth = 0.3)
        // v1's recorder spawns a real `mob("dummy")` (100 hp, killed and respawned at the origin); an immortal dummy keeps being hit by later selectors v1 never saw.
        sim.victimHp = GameplayDump.DUMMY_HP
        lines += "{\"e\":\"meta\",\"id\":\"$id\"}"
        runCatching { sim.cast(id, ticks, drainWorld = false) }.onFailure { lines += "{\"e\":\"error\",\"msg\":\"${esc((it::class.simpleName ?: "") + ": " + it.message.orEmpty().take(200))}\"}" }
        val ord = HashMap<Long, Int>()
        for (c in visuals.calls) {
            val n = ord.getOrPut(c.handle) { ord.size }
            val w = c.write
            if (c.spawn) {
                val spec = visuals.specs[c.handle]
                val k = spec?.kind
                val kind = when (k) {
                    is DisplayKind.Item -> "\"kind\":\"item\",\"item\":\"${k.itemId.value}\",\"model\":${k.itemModel?.let { "\"${esc(it)}\"" } ?: "null"}"
                    DisplayKind.AnchorMainHand -> "\"kind\":\"item\",\"item\":\"main-hand\",\"model\":null"
                    is DisplayKind.Text -> "\"kind\":\"text\",\"text\":\"${esc(k.lines.firstOrNull()?.byLocale?.values?.firstOrNull().orEmpty())}\",\"bg\":${k.backgroundArgb},\"opacity\":${k.textOpacity},\"rgb\":${k.textRgb},\"seeThrough\":${k.seeThrough},\"shadow\":${k.shadow}"
                    is DisplayKind.Block -> "\"kind\":\"block\",\"block\":\"${k.blockKey}\""
                    is DisplayKind.SkinBone -> "\"kind\":\"skin_bone\",\"bone\":\"${k.bone.yamlName}\",\"ghost\":${k.ghost}"
                    else -> "\"kind\":\"?\""
                }
                lines += "{\"t\":${c.tick},\"e\":\"spawn\",\"id\":$n,$kind,\"br\":${spec?.brightness},\"rigshade\":${spec?.weaponRigShading}}"
            }
            lines += "{\"t\":${c.tick},\"e\":\"write\",\"id\":$n,\"x\":${r(w.at.x)},\"y\":${r(w.at.y)},\"z\":${r(w.at.z)},\"yaw\":${r(w.yaw.toDouble())},\"pitch\":${r(w.pitch.toDouble())}," +
                "${transform(w.transform)},\"bb\":\"${w.billboard.name.lowercase()}\",\"delay\":${w.interpolationDelay},\"interp\":${w.interpolationDuration},\"tp\":${w.teleportDuration}," +
                "\"anch\":${if (w.anchor == null) "null" else v3(w.anchor!!.feet)}}"
        }
        // Trail ribbons: the Paper gateway spawns one blank text display per band / edge / rim quad (spawnSegment), fixed billboard,
        // the segment matrix as its transformation. They are not in `visuals.calls`, so replay them here (R5: counts must see them).
        var ribbon = ord.size
        for (rq in visuals.ribbonQuads) {
            val n = ribbon++
            val scale = Vec3(rq.cols[0].length(), rq.cols[1].length(), rq.cols[2].length())
            val rot = rotationOf(rq.cols[0], rq.cols[1], rq.cols[2])
            val look = if (rq.block != null) "\"kind\":\"block\",\"block\":\"${rq.block}\"" else "\"kind\":\"text\",\"text\":\" \",\"bg\":${ribbonArgb(rq.colorHex, rq.alpha)},\"opacity\":255"
            lines += "{\"t\":${rq.tick},\"e\":\"spawn\",\"id\":$n,$look,\"br\":15,\"rigshade\":false,\"ribbon\":\"${rq.kind.name.lowercase()}\"}"
            lines += "{\"t\":${rq.tick},\"e\":\"write\",\"id\":$n,\"x\":${r(rq.centre.x)},\"y\":${r(rq.centre.y)},\"z\":${r(rq.centre.z)},\"yaw\":0.0,\"pitch\":0.0," +
                "\"tr\":${v3(rq.shift)},\"left\":${q(rot)},\"scale\":${v3(scale)},\"bb\":\"fixed\",\"delay\":0,\"interp\":0,\"tp\":0,\"anch\":null}"
            lines += "{\"t\":${rq.tick + rq.life},\"e\":\"remove\",\"id\":$n}"
        }
        for ((t, h, f) in visuals.plateFrames) {
            val n = ord[h] ?: continue
            lines += "{\"t\":$t,\"e\":\"plate\",\"id\":$n,\"frame\":${f.first},\"tint\":${f.second?.let { "\"$it\"" } ?: "null"}}"
        }
        for ((t, h, m) in visuals.modelSwaps) {
            val n = ord[h] ?: continue
            lines += "{\"t\":$t,\"e\":\"swap\",\"id\":$n,\"what\":\"item_model\",\"v\":${m?.let { "\"${esc(it)}\"" } ?: "null"}}"
        }
        // A trail is a ribbon of quads on Paper; v1 wrote it as block / text displays. One line per front-face piece.
        for (q in visuals.ribbonQuads.filter { !it.backFace }) lines += "{\"t\":${q.tick},\"e\":\"ribbon\",\"piece\":\"${q.kind.name.lowercase()}\",\"index\":${q.index}}"
        for ((h, life) in visuals.lives) {
            val n = ord[h] ?: continue
            life.removeTick?.let { lines += "{\"t\":$it,\"e\":\"remove\",\"id\":$n}" }
        }
        return lines
    }
}
