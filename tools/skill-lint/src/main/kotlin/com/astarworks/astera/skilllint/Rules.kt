package com.astarworks.astera.skilllint

import com.astarworks.astera.core.application.skillengine.compose.ComposedSkill
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.frame.FrameKind
import com.astarworks.astera.core.domain.skillengine.model.ParamValue
import com.astarworks.astera.core.domain.skillengine.model.LocationSpec
import com.astarworks.astera.core.domain.skillengine.model.SkillDef
import com.astarworks.astera.core.domain.skillengine.model.SkillEvents
import com.astarworks.astera.core.domain.skillengine.model.TargetRef
import com.astarworks.astera.core.domain.skillengine.model.TimelineAt
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.motion.MotionSpec
import com.astarworks.astera.core.domain.skillengine.op.ControlOp
import com.astarworks.astera.core.domain.skillengine.op.DamageOp
import com.astarworks.astera.core.domain.skillengine.op.FrameOp
import com.astarworks.astera.core.domain.skillengine.op.FxOp
import com.astarworks.astera.core.domain.skillengine.op.GameplayOp
import com.astarworks.astera.core.domain.skillengine.op.HitOp
import com.astarworks.astera.core.domain.skillengine.op.MovementOp
import com.astarworks.astera.core.domain.skillengine.op.SpawnOp
import com.astarworks.astera.core.domain.skillengine.op.StatusOp
import com.astarworks.astera.core.domain.skillengine.op.VisualOp

/** What a per-skill rule sees: the typed model and the composed generic tree it was decoded from. */
class SkillCtx(val def: SkillDef, val tree: Map<String, Any?>) {
    val gameplay: List<OpSite<GameplayOp>> by lazy { ModelWalk.gameplayOps(def) }
    val presentation by lazy { ModelWalk.presentationOps(def.presentation) }

    fun fail(rule: String, path: String, message: String) = LintFinding(rule, Severity.FAIL, def.id, path, message)
    fun warn(rule: String, path: String, message: String) = LintFinding(rule, Severity.WARN, def.id, path, message)
}

/** One structural check on one decoded skill. [id] is the stable rule name printed in reports. */
fun interface SkillRule {
    fun check(ctx: SkillCtx): List<LintFinding>
}

object SkillRules {
    /** Rule id -> rule, in report order. */
    val all: Map<String, SkillRule> = linkedMapOf(
        "abs-coordinates" to SkillRule(::absoluteCoordinates),
        "effect-outside-hit" to SkillRule(::effectOutsideHit),
        "hit-without-effect" to SkillRule(::hitWithoutEffect),
        "projectile-fate" to SkillRule(::projectileFate),
        "pose-density" to SkillRule(::poseDensity),
        "one-item-per-hand" to SkillRule(::oneItemPerHand),
        "sound-position" to SkillRule(::soundPosition),
        "param-duplicate" to SkillRule(::paramDuplicate),
        "budget" to SkillRule(::budget),
        "hit-visibility" to SkillRule(::hitVisibility),
        "event-raised" to SkillRule(::eventRaised),
    )

    // ---- 1. no absolute coordinates -------------------------------------------------------------------------

    private val VEC_CALL = Regex("""vec3\(\s*-?[0-9.]+\s*,\s*-?[0-9.]+\s*,\s*-?[0-9.]+\s*\)""")

    private fun absoluteCoordinates(c: SkillCtx): List<LintFinding> {
        val out = ArrayList<LintFinding>()
        for (f in c.def.frames) {
            val o = f.offset
            if (f.parent.frame == "world" && f.kind != FrameKind.BODY && o != null && (o.x != 0.0 || o.y != 0.0 || o.z != 0.0)) {
                out += c.fail("abs-coordinates", "frames[${f.id}].offset", "frame '${f.id}' sits at the world coordinate ${o.x},${o.y},${o.z}; attach it to a point of another frame")
            }
        }
        for (s in c.gameplay) {
            ModelWalk.visitFields(s.op, s.path) { node, p ->
                if (node is LocationSpec.AtValue) {
                    val v = node.value
                    when {
                        v is Value.Literal<*> && v.value is Vec3 ->
                            out += c.fail("abs-coordinates", p, "literal world coordinate ${v.value}; use a frame point (at: <frame>.<point>) or an expression relative to one")
                        v is Value.Expr && VEC_CALL.containsMatchIn(v.source) ->
                            out += c.warn("abs-coordinates", p, "expression builds a constant world vector: '${v.source}'")
                        else -> Unit
                    }
                }
            }
        }
        return out
    }

    // ---- 2. presentation never moves frames -----------------------------------------------------------------

    /** Op names that move frames, hit, damage or raise events: forbidden in `presentation`. */
    private val GAMEPLAY_ONLY_OPS = setOf(
        "pose", "spin", "fly", "path", "follow", "stick", "reel", "reparent", "place", "hit", "projectile", "aoe",
        "summon", "summon_lightning", "damage", "heal", "resurrect", "explode", "status", "remove_status", "cleanse",
        "ignite", "silence", "modify_attribute", "restore_resource", "impulse", "knockback", "knockup", "set_velocity",
        "propel", "teleport", "leap", "dash_strike", "set_block", "remove_block", "break_area", "place_shape",
        "freeze", "schedule", "event", "cast_skill", "if", "loop", "choose", "weighted_branch", "for_each", "counter",
        "set_cooldown", "give_item", "consume_item", "window",
    )

    // ---- 3. damage/status/impulse only from a contact; every hit has contact-derived effects -----------------

    private val TargetRef.isEnemySide: Boolean get() = this !is TargetRef.Caster && this !is TargetRef.Allies

    private fun isContactEffect(op: GameplayOp): Boolean = when (op) {
        is DamageOp.Damage -> op.target.isEnemySide
        is StatusOp.ApplyStatus -> op.target.isEnemySide
        is StatusOp.Ignite -> op.target.isEnemySide
        is StatusOp.Silence -> op.target.isEnemySide
        is MovementOp.Impulse -> op.target.isEnemySide
        is MovementOp.Knockback -> op.target.isEnemySide
        is MovementOp.Knockup -> op.target.isEnemySide
        is MovementOp.Propel -> op.target.isEnemySide
        is MovementOp.SetVelocity -> op.target.isEnemySide
        else -> false
    }

    private fun isImpactArea(site: OpSite<GameplayOp>): Boolean {
        val target = when (val op = site.op) {
            is DamageOp.Damage -> op.target
            is StatusOp.ApplyStatus -> op.target
            else -> return false
        }
        return target is TargetRef.Enemies && site.event in setOf("block", "expire", "end")
    }

    private fun effectOutsideHit(c: SkillCtx): List<LintFinding> =
        // an area query around the impact point is a legitimate effect of an impact event (grenade bursting on a block)
        c.gameplay.filter { !it.hitScope && isContactEffect(it.op) && !isImpactArea(it) }.map {
            c.fail("effect-outside-hit", it.path, "'${it.op.id}' damages/affects an enemy outside a hit handler; only gameplay.on.hit (a contact) may do that")
        }

    private fun mentionsOtherSource(op: GameplayOp, hitId: String): Boolean {
        val src = (op.whenExpr as? Value.Expr)?.source ?: return false
        return src.contains("event.source") && !src.contains("\"$hitId\"") && !src.contains("'$hitId'")
    }

    private fun hitWithoutEffect(c: SkillCtx): List<LintFinding> {
        val out = ArrayList<LintFinding>()
        val hitHandlers = c.gameplay.filter { it.event == "hit" && it.hitScope }
        for (s in c.gameplay) {
            when (val op = s.op) {
                is HitOp.Hit -> {
                    val served = hitHandlers.any { isContactEffect(it.op) && !mentionsOtherSource(it.op, op.id) }
                    if (!served) out += c.fail("hit-without-effect", s.path, "hit '${op.id}' has no damage/status/impulse in gameplay.on.hit for event.source == '${op.id}'")
                }
                is SpawnOp.Aoe -> if (c.gameplay.none { it.path.startsWith("${s.path}.on_hit") && isContactEffect(it.op) }) {
                    out += c.fail("hit-without-effect", s.path, "aoe '${op.id}' has no contact-derived effect in on_hit")
                }
                else -> Unit
            }
        }
        val anyHitSource = c.gameplay.any { it.op is HitOp.Hit || it.op is SpawnOp.Projectile || it.op is SpawnOp.Aoe || it.op is MovementOp.DashStrike }
        if (!anyHitSource) {
            hitHandlers.firstOrNull()?.let {
                out += c.warn("hit-without-effect", it.path, "gameplay.on.hit exists but no hit volume / projectile / aoe / dash can raise it")
            }
        }
        return out
    }

    // ---- 4. a flying frame must end: reparent / stick / reel ------------------------------------------------

    private fun projectileFate(c: SkillCtx): List<LintFinding> {
        val out = ArrayList<LintFinding>()
        for (s in c.gameplay) {
            val fly = (s.op as? FrameOp.Motion)?.takeIf { it.spec is MotionSpec.Fly } ?: continue
            val frame = fly.frame
            val settled = c.gameplay.any { o ->
                o.event != null && o.event != s.event && when (val op = o.op) {
                    is FrameOp.Reparent -> frame in op.frames
                    is FrameOp.Motion -> op.frame == frame && (op.spec is MotionSpec.Reel || op.spec is MotionSpec.Stick)
                    else -> false
                }
            }
            if (!settled) out += c.fail("projectile-fate", s.path, "frame '$frame' flies but no other handler (hit / block / expire / reel) reparents, sticks or reels it: it would fly and vanish")
        }
        return out
    }

    // ---- 5. no baked per-tick keys ---------------------------------------------------------------------------

    private fun poseDensity(c: SkillCtx): List<LintFinding> {
        val out = ArrayList<LintFinding>()
        val rowTicks = HashMap<String, MutableList<Int>>()
        for (s in c.gameplay) {
            val m = s.op as? FrameOp.Motion ?: continue
            val pose = m.spec as? MotionSpec.Pose ?: continue
            val ts = pose.keys.map { it.t }
            if (ts.zipWithNext().any { (a, b) -> b <= a }) out += c.fail("pose-density", s.path, "pose keys of '${m.frame}' must have strictly increasing t, got $ts")
            if (ts.size >= 3) {
                val gap = (ts.last() - ts.first()).toDouble() / (ts.size - 1)
                if (gap < LintLimits.MIN_TICKS_PER_POSE_KEY) {
                    out += c.fail("pose-density", s.path, "${ts.size} pose keys over ${ts.last() - ts.first()} ticks (avg gap ${"%.2f".format(gap)} < ${LintLimits.MIN_TICKS_PER_POSE_KEY}); that is baked animation, use fewer keys and an ease")
                }
            }
            val entry = s.entry
            if (entry != null) {
                if ((entry.everyTicks ?: 2) <= 1) {
                    out += c.fail("pose-density", s.path, "pose of '${m.frame}' repeats every tick; per-tick poses are baked coordinates")
                }
                ModelWalk.absoluteTick(entry)?.let { rowTicks.getOrPut(m.frame) { ArrayList() }.add(it) }
            }
        }
        for ((frame, ticks) in rowTicks) {
            val ts = ticks.sorted().distinct()
            if (ts.size >= 3 && (ts.last() - ts.first()).toDouble() / (ts.size - 1) < LintLimits.MIN_TICKS_PER_POSE_KEY) {
                out += c.fail("pose-density", "gameplay.timeline", "pose on '$frame' is set in ${ts.size} timeline rows within ${ts.last() - ts.first()} ticks: baked keys")
            }
        }
        c.gameplay.filter { it.op is ControlOp.Loop }.forEach { s ->
            val loop = s.op as ControlOp.Loop
            if (loop.everyTicks <= 1 && c.gameplay.any { it.path.startsWith("${s.path}.body") && (it.op as? FrameOp.Motion)?.spec is MotionSpec.Pose }) {
                out += c.fail("pose-density", s.path, "loop sets a pose every tick")
            }
        }
        return out
    }

    // ---- 6. one item per hand ---------------------------------------------------------------------------------

    private fun oneItemPerHand(c: SkillCtx): List<LintFinding> =
        c.def.frames.filter { it.kind == FrameKind.ITEM && !it.inactive }
            .groupBy { it.parent.toString() }
            .filterValues { it.size > 1 }
            .map { (parent, frames) ->
                c.fail("one-item-per-hand", "frames", "items ${frames.joinToString { it.id }} are all held at '$parent' at the same time; re-parent all but one before they show")
            }

    // ---- 7. sounds ---------------------------------------------------------------------------------------------

    private fun soundPosition(c: SkillCtx): List<LintFinding> =
        c.presentation.mapNotNull { s ->
            val snd = s.op as? FxOp.Sound ?: return@mapNotNull null
            if (s.event == "hit" && snd.at.frame != "contact") {
                c.warn("sound-position", s.path, "hit sound '${snd.sound}' is not at contact.point (at ${snd.at})")
            } else null
        }

    // ---- 8. presentation must not duplicate gameplay constants -------------------------------------------------

    private fun trivial(v: Double) = v == 0.0 || v == 1.0 || v == -1.0 || v == 0.5 || v == 2.0

    private fun paramDuplicate(c: SkillCtx): List<LintFinding> {
        val params = c.def.gameplay.params.mapNotNull { (k, v) -> (v as? ParamValue.Num)?.takeIf { !trivial(it.value) }?.let { it.value to k } }
            .groupBy({ it.first }, { it.second })
        if (params.isEmpty()) return emptyList()
        val out = ArrayList<LintFinding>()
        for (s in c.presentation) {
            ModelWalk.visitFields(s.op, s.path) { node, p ->
                val lit = (node as? Value.Literal<*>)?.value as? Double ?: return@visitFields
                params[lit]?.let { names ->
                    out += c.warn("param-duplicate", p, "literal $lit equals gameplay param ${names.joinToString { "'$it'" }}; reference it through an expression so the two cannot drift")
                }
            }
        }
        return out
    }

    // ---- 9. budgets --------------------------------------------------------------------------------------------

    private fun budget(c: SkillCtx): List<LintFinding> {
        val out = ArrayList<LintFinding>()
        val displays = c.presentation.count { val o = it.op; o is VisualOp.Mesh || o is VisualOp.Plate || o is VisualOp.Line || o is VisualOp.Trail }
        if (displays > LintLimits.MAX_DISPLAYS) out += c.warn("budget", "presentation", "$displays display ops declared (budget ${LintLimits.MAX_DISPLAYS} simultaneous)")
        for (s in c.presentation) {
            val emit = s.op as? FxOp.Emit ?: continue
            val n = (emit.count as? Value.Literal<*>)?.value as? Double ?: continue
            if (n > LintLimits.MAX_PARTICLES_PER_TICK) out += c.fail("budget", s.path, "emits $n particles at once (budget ${LintLimits.MAX_PARTICLES_PER_TICK})")
        }
        return out
    }

    // ---- 10. a hit volume must be visible (no invisible skin gives an advantage) ---------------------------------

    private fun hitVisibility(c: SkillCtx): List<LintFinding> {
        val out = ArrayList<LintFinding>()
        val parent = c.def.frames.associate { it.id to it.parent.frame }
        fun ancestors(f: String): Set<String> = generateSequence(f) { parent[it] }.take(32).toSet()
        val covered = HashSet<String>()
        for (s in c.presentation) {
            when (val op = s.op) {
                is VisualOp.Mesh -> covered += op.frame
                is VisualOp.Plate -> if (((op.alpha as? Value.Literal<*>)?.value as? Double ?: 1.0) >= LintLimits.MIN_VISIBLE_ALPHA) covered += op.parent
                is VisualOp.Line -> { covered += op.from.frame; covered += op.to.frame }
                else -> Unit
            }
        }
        fun isCovered(frame: String): Boolean {
            val up = ancestors(frame)
            return covered.any { it in up || frame in ancestors(it) }
        }
        for (s in c.gameplay) {
            when (val op = s.op) {
                is HitOp.Hit -> op.frames.filterNot(::isCovered).forEach {
                    out += c.warn("hit-visibility", s.path, "hit '${op.id}' on frame '$it' has no mesh/plate/line making it visible; invisible hit volumes are pay-to-win with a transparent skin")
                }
                is SpawnOp.Projectile -> if (!isCovered(op.body)) {
                    out += c.warn("hit-visibility", s.path, "projectile body '${op.body}' has no mesh/plate/line in presentation")
                }
                else -> Unit
            }
        }
        return out
    }

    // ---- 11. handlers must be reachable ---------------------------------------------------------------------------

    private fun eventRaised(c: SkillCtx): List<LintFinding> {
        val raised = HashSet<String>(
            listOf(SkillEvents.START, SkillEvents.RELEASE, SkillEvents.HIT, SkillEvents.BLOCK, SkillEvents.STICK,
                SkillEvents.REEL, SkillEvents.ARRIVE, SkillEvents.EXPIRE, SkillEvents.END),
        )
        // reel is raised by the schedule op in coin 2; arrive / expire / hit / block / stick come from the runtime.
        for (s in c.gameplay) {
            when (val op = s.op) {
                is ControlOp.RaiseEvent -> raised += op.event
                is ControlOp.Schedule -> raised += op.event
                else -> Unit
            }
            (s.entry?.at as? TimelineAt.AfterEvent)?.let { raised += it.event }
        }
        c.def.gameplay.timeline.forEach { (it.at as? TimelineAt.AfterEvent)?.let { a -> raised += a.event } }
        val out = ArrayList<LintFinding>()
        for (e in c.def.gameplay.on.keys) if (e !in raised) out += c.warn("event-raised", "gameplay.on.$e", "no op raises event '$e'")
        for (e in c.def.presentation.on.keys) {
            if (e !in raised) out += c.warn("event-raised", "presentation.on.$e", "no gameplay op raises event '$e', so this handler never runs")
        }
        return out
    }
}

/** Rules that compare skills with each other (variants vs. their base). */
object ProjectRules {
    @Suppress("UNCHECKED_CAST")
    fun check(composed: Map<String, ComposedSkill>): List<LintFinding> {
        val out = ArrayList<LintFinding>()
        for ((id, skill) in composed) {
            if ('@' !in id) continue
            val base = composed[id.substringBefore('@')] ?: continue
            val patch = skill.tree["_patch"] as? Map<String, Any?>
            val kind = patch?.get("kind") as? String
            val sameGameplay = skill.tree["gameplay"] == base.tree["gameplay"] && skill.tree["frames"] == base.tree["frames"]
            when {
                kind == "balance" -> out += LintFinding("balance-patch", Severity.WARN, id, "gameplay", "balance patch (dev only): gameplay differs from '${base.id}'")
                !sameGameplay -> out += LintFinding(
                    "skin-changes-gameplay", Severity.FAIL, id, "gameplay",
                    "variant '$id' changes frames/gameplay of '${base.id}' but is not a balance patch; a skin must leave contacts identical",
                )
            }
        }
        return out
    }
}
