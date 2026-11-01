package com.astarworks.astera.core.application.skillengine.load

import com.astarworks.astera.core.application.skillengine.compose.AnchorNames
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarContext
import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.frame.grammar.AxisSpec
import com.astarworks.astera.core.domain.skillengine.frame.grammar.Basis3
import com.astarworks.astera.core.domain.skillengine.frame.grammar.Coeff
import com.astarworks.astera.core.domain.skillengine.frame.grammar.FramePoint
import com.astarworks.astera.core.domain.skillengine.frame.grammar.FrameDecl
import com.astarworks.astera.core.domain.skillengine.frame.grammar.LineDecl
import com.astarworks.astera.core.domain.skillengine.frame.grammar.MoveKey
import com.astarworks.astera.core.domain.skillengine.frame.grammar.OriginSpec
import com.astarworks.astera.core.domain.skillengine.frame.grammar.RotateKey
import com.astarworks.astera.core.domain.skillengine.frame.grammar.ScaleKey

/**
 * `frames:` (ADR-0314). One entry:
 *
 * ```yaml
 * - id: gun
 *   origin: caster.hand_r          # caster[.eye|feet|body|center|hand_r|hand_l], an instance id, victim[.point] (on: event.victim) or contact (on: event.block)
 *   v: aim                         # a named direction, "a x b" of two other axes of this frame, or [x, y, z]
 *   w: up
 *   s: v x w
 *   rotate: [{at: 2, for: 5, about: w, from: 120, to: -30, ease: in_quad}]
 *   move:   [{at: 0, for: 3, to: {v: 0.5}}]
 *   snapshot: cast                 # optional: origin + directions read once at that tick ("cast" or a whole tick), then held in world space
 * - id: stuck
 *   on: event.victim
 *   each: true
 *   v: incoming
 *   w: up
 *   s: v x w
 *   for: 20
 * ```
 *
 * Every error names the frame and the field; a coplanar literal basis fails here, a coplanar live basis fails at the tick it happens.
 */
internal object FrameDeclDecoder {
    private val KEYS = setOf("id", "origin", "v", "w", "s", "rotate", "move", "scale", "on", "each", "snapshot")
    private val CASTER_POINTS = setOf("", "eye", "feet", "body", "center", "hand_r", "hand_l", "shoulder_r", "shoulder_l")
    private val VICTIM_POINTS = setOf("", "eye", "feet", "body", "center")
    private val CROSS = Regex("""^\s*([vws])\s*x\s*([vws])\s*$""")

    fun read(ctx: GrammarContext, raw: Any?, anchorIds: Set<String>): List<FrameDecl> {
        if (raw == null) return emptyList()
        val list = raw as? List<*> ?: ctx.mismatch("frames", "list", raw)
        val seen = HashSet<String>()
        return list.mapIndexed { i, e ->
            val p = "frames[$i]"
            val m = e as? Map<*, *> ?: ctx.mismatch(p, "map {id, origin, v, w, s, rotate, move, scale, on, each}", e)
            @Suppress("UNCHECKED_CAST")
            m as Map<String, Any?>
            for (k in m.keys) if (k !in KEYS) ctx.abort(SkillDecodeError.UnknownKey(ctx.file, p, k, "expected one of " + KEYS.joinToString(", ")))
            val id = m["id"] as? String ?: ctx.abort(SkillDecodeError.MissingField(ctx.file, p, "id"))
            AnchorNames.reservedError(p, id)?.let { ctx.abort(SkillDecodeError.Invalid(ctx.file, "$p.id", it)) }
            if (id in anchorIds) ctx.fail("$p.id", "frame '$id' collides with an anchor of the same id")
            if (!seen.add(id)) ctx.abort(SkillDecodeError.DuplicateId(ctx.file, p, id))

            val on = m["on"]?.let { it as? String ?: ctx.mismatch("$p.on", "'event.victim', 'event.block', 'event.attacker' or 'state.<name>'", it) }
            val onState = on != null && on.startsWith(FrameDecl.STATE_PREFIX)
            if (on != null && !onState && on != FrameDecl.ON_VICTIM && on != FrameDecl.ON_BLOCK && on != FrameDecl.ON_ATTACKER) {
                ctx.fail("$p.on", "frame '$id': on must be event.victim (one instance per hit victim), event.block (one per block contact), event.attacker (one per attacker) or state.<name> (one per entry of that state), found '$on'")
            }
            if (onState && on!!.removePrefix(FrameDecl.STATE_PREFIX).let { it.isBlank() || it.any(Char::isWhitespace) }) ctx.fail("$p.on", "frame '$id': on: state.<name> needs a state name")
            val each = m["each"]?.let { it as? Boolean ?: ctx.mismatch("$p.each", "boolean", it) } ?: false
            if (on != null && !onState && !each) ctx.fail("$p.each", "frame '$id': on: $on needs each: true (one frame per victim / contact / attacker; there is no shared one)")
            if (onState && each) ctx.fail("$p.each", "frame '$id': on: $on is one frame per state entry; each: is for on: event.*")
            if (on == null && each) ctx.fail("$p.each", "frame '$id': each: true needs on: event.victim, on: event.block or on: event.attacker")

            val origin = origin(ctx, id, p, m["origin"], on)
            val v = axis(ctx, id, p, "v", m["v"])
            val w = axis(ctx, id, p, "w", m["w"])
            val s = axis(ctx, id, p, "s", m["s"])
            checkCross(ctx, id, p, mapOf("v" to v, "w" to w, "s" to s))
            checkLiteralBasis(ctx, id, p, v, w, s)

            val rotate = keys(ctx, "$p.rotate", m["rotate"], setOf("at", "for", "about", "from", "to", "ease", "reset"), { if (it["reset"] != null) RESET else it["about"] as? String }) { k, kp ->
                if (k["reset"] != null) {
                    if (k["reset"] != true) ctx.mismatch("$kp.reset", "true", k["reset"])
                    for (other in listOf("about", "from", "to")) if (k[other] != null) ctx.fail("$kp.$other", "frame '$id': a reset key turns the whole chain back to the rest pose; it takes at, for and ease only")
                    return@keys RotateKey.reset(tick(ctx, k["at"], "$kp.at"), ticks(ctx, k["for"], "$kp.for"), ease(ctx, k["ease"], "$kp.ease"))
                }
                val about = k["about"] as? String ?: ctx.abort(SkillDecodeError.MissingField(ctx.file, kp, "about"))
                if (about !in setOf("v", "w", "s")) ctx.fail("$kp.about", "frame '$id': about must be v, w or s (an axis of this frame), found '$about'")
                val to = k["to"]?.let { num(ctx, it, "$kp.to") } ?: ctx.abort(SkillDecodeError.MissingField(ctx.file, kp, "to"))
                val from = k["from"]?.let { num(ctx, it, "$kp.from") }
                RotateKey(tick(ctx, k["at"], "$kp.at"), ticks(ctx, k["for"], "$kp.for"), about, from, to, ease(ctx, k["ease"], "$kp.ease"))
            }
            val move = keys(ctx, "$p.move", m["move"], setOf("at", "for", "to", "ease")) { k, kp ->
                MoveKey(tick(ctx, k["at"], "$kp.at"), ticks(ctx, k["for"], "$kp.for"), coeff(ctx, k["to"] ?: ctx.abort(SkillDecodeError.MissingField(ctx.file, kp, "to")), "$kp.to", Coeff.ZERO), ease(ctx, k["ease"], "$kp.ease"))
            }
            val scale = keys(ctx, "$p.scale", m["scale"], setOf("at", "for", "to", "ease")) { k, kp ->
                ScaleKey(tick(ctx, k["at"], "$kp.at"), ticks(ctx, k["for"], "$kp.for"), coeff(ctx, k["to"] ?: ctx.abort(SkillDecodeError.MissingField(ctx.file, kp, "to")), "$kp.to", Coeff.ONE), ease(ctx, k["ease"], "$kp.ease"))
            }
            val snapshotAt = m["snapshot"]?.let { raw ->
                if (raw == "cast") 0 else if (raw is Number) tick(ctx, raw, "$p.snapshot") else ctx.mismatch("$p.snapshot", "'cast' or a whole tick", raw)
            }
            FrameDecl(id, origin, v, w, s, rotate, move, scale, on, each, snapshotAt = snapshotAt)
        }
    }

    private fun origin(ctx: GrammarContext, id: String, p: String, raw: Any?, on: String?): OriginSpec {
        val perVictim = on == FrameDecl.ON_VICTIM
        val perBlock = on == FrameDecl.ON_BLOCK
        val perAttacker = on == FrameDecl.ON_ATTACKER
        val onState = on != null && on.startsWith(FrameDecl.STATE_PREFIX)
        if (raw == null) {
            return when {
                perVictim -> OriginSpec.Victim("center")
                perBlock -> OriginSpec.Contact
                perAttacker -> OriginSpec.Attacker("center")
                else -> ctx.abort(SkillDecodeError.MissingField(ctx.file, p, "origin"))
            }
        }
        if (raw is Map<*, *>) {
            if (on != null && !onState) ctx.fail("$p.origin", "frame '$id': an on: event frame is rooted on its victim / contact / attacker; a frame-point origin is for plain frames")
            if (raw.keys == setOf("aim")) {
                if (on != null) ctx.fail("$p.origin", "frame '$id': an aim origin is for plain frames (not on: event / state)")
                return OriginSpec.Aim(com.astarworks.astera.core.application.skillengine.load.grammar.Types.number.decode(raw["aim"], ctx, "$p.origin.aim"))
            }
            for (key in raw.keys) if (key != "frame" && key != "pos") ctx.abort(SkillDecodeError.UnknownKey(ctx.file, "$p.origin", key.toString(), "expected frame, pos or aim"))
            val parent = raw["frame"] as? String ?: ctx.abort(SkillDecodeError.MissingField(ctx.file, "$p.origin", "frame"))
            return OriginSpec.Frame(parent, coeff(ctx, raw["pos"], "$p.origin.pos", Coeff.ZERO))
        }
        val s = raw as? String ?: ctx.mismatch("$p.origin", "caster[.point], victim[.point], attacker[.point], contact, an instance id or {frame, pos}", raw)
        val base = s.substringBefore('.')
        val point = if ('.' in s) s.substringAfter('.') else ""
        if (perBlock) {
            if (s != "contact" && s != "contact.point") ctx.fail("$p.origin", "frame '$id': an on: event.block frame is rooted on the block contact; origin must be contact or absent")
            return OriginSpec.Contact
        }
        return when (base) {
            "caster" -> {
                if (point !in CASTER_POINTS) ctx.fail("$p.origin", "frame '$id': caster point '$point' is not one of eye, feet, body, center, hand_r, hand_l, shoulder_r, shoulder_l")
                OriginSpec.Caster(point)
            }
            "victim", "event" -> {
                if (!perVictim && !onState) ctx.fail("$p.origin", "frame '$id': a victim origin needs on: event.victim, each: true (or on: state.<name>)")
                val pt = if (base == "event") point.removePrefix("victim").removePrefix(".") else point
                if (pt !in VICTIM_POINTS) ctx.fail("$p.origin", "frame '$id': victim point '$pt' is not one of eye, feet, body, center")
                OriginSpec.Victim(pt.ifEmpty { "center" })
            }
            "attacker" -> {
                if (!perAttacker && !onState) ctx.fail("$p.origin", "frame '$id': an attacker origin needs on: event.attacker, each: true (or on: state.<name>)")
                if (point !in VICTIM_POINTS) ctx.fail("$p.origin", "frame '$id': attacker point '$point' is not one of eye, feet, body, center")
                OriginSpec.Attacker(point.ifEmpty { "center" })
            }
            "contact" -> ctx.fail("$p.origin", "frame '$id': a contact origin needs on: event.block, each: true")
            else -> {
                if (perVictim) ctx.fail("$p.origin", "frame '$id': an on: event.victim frame is rooted on the victim; origin must be victim[.point] or absent")
                if (perAttacker) ctx.fail("$p.origin", "frame '$id': an on: event.attacker frame is rooted on the attacker; origin must be attacker[.point] or absent")
                if (s.isBlank() || s.any { it.isWhitespace() }) ctx.fail("$p.origin", "frame '$id': '$s' is not an instance id")
                if (base in AnchorNames.BUILTIN_FRAMES) ctx.fail("$p.origin", "frame '$id': origin '$s' is a builtin frame; only caster[.point], victim[.point] / attacker[.point] (with on:) or an instance id root a frame")
                OriginSpec.Instance(s)
            }
        }
    }

    /**
     * `lines: {<id>: {frame: <frame id>, from: {v, w, s}, to: {v, w, s}}}` (or each end `{frame, pos}` on its own frame): named segments draw ops read with `along:`. Every error names
     * the line and the field; a line on an undeclared frame fails here.
     */
    fun readLines(ctx: GrammarContext, raw: Any?, frameIds: Set<String>): List<LineDecl> {
        if (raw == null) return emptyList()
        val m = raw as? Map<*, *> ?: ctx.mismatch("lines", "map {<id>: {frame, from, to}}", raw)
        val out = ArrayList<LineDecl>()
        for ((k, e) in m) {
            val id = k.toString()
            val p = "lines.$id"
            if (id.isBlank() || id.any { it.isWhitespace() }) ctx.fail(p, "'$id' is not a line id")
            val body = e as? Map<*, *> ?: ctx.mismatch(p, "map {frame, from, to}", e)
            for (key in body.keys) if (key !in LINE_KEYS) ctx.abort(SkillDecodeError.UnknownKey(ctx.file, p, key.toString(), "expected ${LINE_KEYS.joinToString()}"))
            // two spellings: `frame:` + `from`/`to` coefficients (one frame), or each end `{frame, pos}` (the ends on different frames)
            val crossEnds = body["from"] is Map<*, *> && (body["from"] as Map<*, *>).containsKey("frame")
            val from: FramePoint
            val to: FramePoint
            if (crossEnds) {
                if (body.containsKey("frame")) ctx.fail("$p.frame", "line '$id': frame: and per-end {frame, pos} are exclusive")
                fun end(name: String): FramePoint {
                    val e2 = body[name] as? Map<*, *> ?: ctx.mismatch("$p.$name", "{frame, pos}", body[name])
                    for (key in e2.keys) if (key !in END_KEYS) ctx.abort(SkillDecodeError.UnknownKey(ctx.file, "$p.$name", key.toString(), "expected frame, pos"))
                    val f = e2["frame"]?.let { it as? String ?: ctx.mismatch("$p.$name.frame", "frame id", it) } ?: ctx.abort(SkillDecodeError.MissingField(ctx.file, "$p.$name", "frame"))
                    if (f !in frameIds && !com.astarworks.astera.core.application.skillengine.compose.BuiltinFrames.isBuiltin(f)) ctx.abort(SkillDecodeError.UndefinedReference(ctx.file, "$p.$name.frame", "frame '$f' (not under frames:)"))
                    return FramePoint(f, coeff(ctx, e2["pos"] ?: emptyMap<String, Any>(), "$p.$name.pos", Coeff.ZERO))
                }
                from = end("from")
                to = end("to")
                if (from == to) ctx.fail("$p.to", "line '$id': from and to are the same point; a line has a length")
            } else {
                val frame = body["frame"]?.let { it as? String ?: ctx.mismatch("$p.frame", "frame id", it) } ?: ctx.abort(SkillDecodeError.MissingField(ctx.file, p, "frame"))
                if (frame !in frameIds && !com.astarworks.astera.core.application.skillengine.compose.BuiltinFrames.isBuiltin(frame)) ctx.abort(SkillDecodeError.UndefinedReference(ctx.file, "$p.frame", "frame '$frame' (not under frames:)"))
                from = FramePoint(frame, coeff(ctx, body["from"] ?: ctx.abort(SkillDecodeError.MissingField(ctx.file, p, "from")), "$p.from", Coeff.ZERO))
                to = FramePoint(frame, coeff(ctx, body["to"] ?: ctx.abort(SkillDecodeError.MissingField(ctx.file, p, "to")), "$p.to", Coeff.ZERO))
                if (from == to) ctx.fail("$p.to", "line '$id': from and to are the same point; a line has a length")
            }
            out += LineDecl(id, from, to)
        }
        return out
    }

    private val LINE_KEYS = setOf("frame", "from", "to")
    private val END_KEYS = setOf("frame", "pos")

    private fun axis(ctx: GrammarContext, id: String, p: String, name: String, raw: Any?): AxisSpec = when (raw) {
        null -> ctx.abort(SkillDecodeError.MissingField(ctx.file, p, name))
        is String -> {
            val cross = CROSS.matchEntire(raw)
            when {
                cross != null -> {
                    val (a, b) = cross.destructured
                    if (a == name || b == name) ctx.fail("$p.$name", "frame '$id': $name may not be a cross product of itself")
                    if (a == b) ctx.fail("$p.$name", "frame '$id': '$raw' is the zero vector")
                    AxisSpec.Cross(a, b)
                }
                raw.trim() in AxisSpec.NAMED -> AxisSpec.Named(raw.trim())
                else -> ctx.fail("$p.$name", "frame '$id': axis '$raw' is not a direction (${AxisSpec.NAMED.joinToString(", ")}), 'a x b', or [x, y, z]")
            }
        }
        is List<*> -> {
            if (raw.size != 3) ctx.mismatch("$p.$name", "[x, y, z]", raw)
            val n = raw.map { (it as? Number)?.toDouble() ?: ctx.mismatch("$p.$name", "[x, y, z] of numbers", raw) }
            val vec = Vec3(n[0], n[1], n[2])
            if (vec.lengthSquared() == 0.0) ctx.fail("$p.$name", "frame '$id': $name is the zero vector")
            AxisSpec.Literal(vec)
        }
        else -> ctx.mismatch("$p.$name", "direction name, 'a x b' or [x, y, z]", raw)
    }

    private fun checkCross(ctx: GrammarContext, id: String, p: String, axes: Map<String, AxisSpec>) {
        for ((name, spec) in axes) {
            if (spec !is AxisSpec.Cross) continue
            for (dep in listOf(spec.a, spec.b)) {
                if (axes[dep] is AxisSpec.Cross) ctx.fail("$p.$name", "frame '$id': $name = '${spec.a} x ${spec.b}' reads $dep, which is itself a cross product; only one axis may be derived")
            }
        }
    }

    private fun checkLiteralBasis(ctx: GrammarContext, id: String, p: String, v: AxisSpec, w: AxisSpec, s: AxisSpec) {
        // two axes naming the same direction are coplanar before any tick is played
        val named = listOf("v" to v, "w" to w, "s" to s).mapNotNull { (n, a) -> (a as? AxisSpec.Named)?.let { n to it.name } }
        for (i in named.indices) for (j in i + 1 until named.size) {
            if (named[i].second == named[j].second) ctx.fail("$p.${named[j].first}", "frame '$id': ${named[i].first} and ${named[j].first} are both '${named[j].second}'; v, w, s are coplanar and the frame spans no volume")
        }
        val lit = HashMap<String, Vec3>()
        for ((n, a) in listOf("v" to v, "w" to w, "s" to s)) if (a is AxisSpec.Literal) lit[n] = a.vector
        for ((n, a) in listOf("v" to v, "w" to w, "s" to s)) {
            if (a is AxisSpec.Cross) {
                val x = lit[a.a] ?: return
                val y = lit[a.b] ?: return
                val c = x.cross(y)
                if (c.lengthSquared() == 0.0) ctx.fail("$p.$n", "frame '$id': ${a.a} and ${a.b} are parallel; $n = '${a.a} x ${a.b}' is the zero vector")
                lit[n] = c
            }
        }
        val b = Basis3(lit["v"] ?: return, lit["w"] ?: return, lit["s"] ?: return)
        if (b.isDegenerate) ctx.fail("$p.s", "frame '$id': v, w, s are coplanar (determinant ${b.determinant}); the frame spans no volume")
    }

    /**
     * A key list. [track] names the track a key belongs to (null = one track): keys of one track must be in tick order
     * (the next starts where the previous ended). `rotate` keys are tracked per axis, so the list order is free to be
     * the chain order (each key turns about the frame's axis as the keys before it left it) while `v` keys and `s` keys
     * interleave in time; a reset key closes every track.
     */
    private fun <K> keys(
        ctx: GrammarContext, path: String, raw: Any?, allowed: Set<String>,
        track: (Map<String, Any?>) -> String? = { null },
        one: (Map<String, Any?>, String) -> K,
    ): List<K> {
        if (raw == null) return emptyList()
        val list = raw as? List<*> ?: ctx.mismatch(path, "list of keys", raw)
        val lastAt = HashMap<String?, Int>()
        var floor = -1
        return list.mapIndexed { i, e ->
            val kp = "$path[$i]"
            val m = e as? Map<*, *> ?: ctx.mismatch(kp, "map {${allowed.joinToString(", ")}}", e)
            @Suppress("UNCHECKED_CAST")
            m as Map<String, Any?>
            for (k in m.keys) if (k !in allowed) ctx.abort(SkillDecodeError.UnknownKey(ctx.file, kp, k, "expected one of " + allowed.joinToString(", ")))
            val at = tick(ctx, m["at"], "$kp.at")
            val tr = track(m)
            val last = maxOf(lastAt[tr] ?: -1, floor)
            if (at < last) ctx.fail("$kp.at", (if (tr == null) "keys" else "'$tr' keys") + " must be in tick order (at $at after $last)")
            if (tr == RESET) { floor = at; lastAt.clear() } else lastAt[tr] = at
            one(m, kp)
        }
    }

    private const val RESET = "reset"

    private fun tick(ctx: GrammarContext, raw: Any?, path: String): Int {
        val n = (raw as? Number)?.toDouble() ?: ctx.mismatch(path, "a whole tick (literal)", raw)
        if (n % 1.0 != 0.0 || n < 0.0) ctx.fail(path, "must be a whole tick >= 0, found $n")
        return n.toInt()
    }

    private fun ticks(ctx: GrammarContext, raw: Any?, path: String): Int {
        if (raw == null) return 0
        val n = (raw as? Number)?.toDouble() ?: ctx.mismatch(path, "ticks (literal)", raw)
        if (n % 1.0 != 0.0 || n < 0.0) ctx.fail(path, "must be a whole number of ticks >= 0, found $n")
        return n.toInt()
    }

    private fun num(ctx: GrammarContext, raw: Any?, path: String): Double = (raw as? Number)?.toDouble() ?: ctx.mismatch(path, "number (literal)", raw)

    private fun ease(ctx: GrammarContext, raw: Any?, path: String): Easing {
        if (raw == null) return Easing.LINEAR
        val s = raw as? String ?: ctx.mismatch(path, "ease name", raw)
        return Easing.fromKey(s) ?: ctx.fail(path, "'$s' is not an easing (linear, in_quad, out_cubic, in_out_sine, ...)")
    }

    /** `{v: 0.5, w: -0.1}`: literal coefficients, absent = [identity] per component. */
    fun coeff(ctx: GrammarContext, raw: Any?, path: String, identity: Coeff): Coeff {
        val m = raw as? Map<*, *> ?: ctx.mismatch(path, "{v, w, s}", raw)
        for (k in m.keys) if (k !in setOf("v", "w", "s")) ctx.abort(SkillDecodeError.UnknownKey(ctx.file, path, k.toString(), "expected v, w, s"))
        fun c(k: String, d: Double) = m[k]?.let { num(ctx, it, "$path.$k") } ?: d
        return Coeff(c("v", identity.v), c("w", identity.w), c("s", identity.s))
    }
}
