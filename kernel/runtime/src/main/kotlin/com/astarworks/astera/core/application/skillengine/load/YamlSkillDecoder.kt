package com.astarworks.astera.core.application.skillengine.load

import com.astarworks.astera.core.application.skillengine.compose.ComposedSkill
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.model.FrameDecl
import com.astarworks.astera.core.domain.skillengine.model.GameplaySection
import com.astarworks.astera.core.domain.skillengine.model.ParamValue
import com.astarworks.astera.core.domain.skillengine.model.PresentationSection
import com.astarworks.astera.core.domain.skillengine.model.SkillDef
import com.astarworks.astera.core.domain.skillengine.model.SkillMeta
import com.astarworks.astera.core.domain.skillengine.model.TimelineEntry
import com.astarworks.astera.core.domain.skillengine.modifier.ExposedParam
import com.astarworks.astera.core.domain.skillengine.op.GameplayOp
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.op.PresentationOp
import com.astarworks.astera.core.domain.skillengine.op.SkillOp

/** Composed generic tree -> [SkillDef]. Fail-fast: the first violation is returned with its YAML-style path. */
public class YamlSkillDecoder(private val registry: OpDecoderRegistry) : SkillDecoder {
    override fun decode(composed: ComposedSkill): Result<SkillDef, SkillDecodeError> =
        try {
            Result.success(Run(composed, registry).skill())
        } catch (e: DecodeAbort) {
            Result.failure(e.error)
        }

    private class Run(val composed: ComposedSkill, registry: OpDecoderRegistry) {
        val skillId = composed.id
        val ctx = OpDecodeContext(skillId, registry)
        val rowIds = HashSet<String>()

        fun reader(section: OpSection, path: String, raw: Any?, index: Int? = null): DefaultOpReader {
            val map = raw as? Map<*, *> ?: ValueDecoding.mismatch(skillId, if (index == null) path else "$path[$index]", "map", raw)
            return DefaultOpReader(ctx, section, path, map)
        }

        fun skill(): SkillDef {
            checkPatchMeta()
            val top = DefaultOpReader(ctx, OpSection.GAMEPLAY, "", composed.tree)
            top.allow(SectionRules.SKILL_KEYS)
            top.consume("id")
            top.consume("_patch")
            val item = top.string("item")
            val importance = top.int("importance")
            val frames = decodeFrames(top.listOrNull("frames"))
            val gameplay = decodeGameplay(top.mapOrNull("gameplay"))
            val presentation = decodePresentation(top.mapOrNull("presentation"))
            val meta = decodeMeta(top.mapOrNull("meta"))
            return SkillDef(skillId, item, importance, frames, gameplay, presentation, meta)
        }

        /**
         * `meta: {name: {ja, en}, description: {ja, en}, cooldown_ticks, resource_type, resource_cost, ego_cost,
         * reload_ticks, ready_cue, tags}`. Unknown keys are refused.
         */
        fun decodeMeta(raw: Map<*, *>?): SkillMeta {
            if (raw == null) return SkillMeta()
            fun bad(key: String, message: String): Nothing =
                throw DecodeAbort(SkillDecodeError.Invalid(skillId, "meta.$key", message))
            val allowed = setOf(
                "name", "description", "cooldown_ticks", "resource_type", "resource_cost", "ego_cost",
                "reload_ticks", "ready_cue", "tags",
            )
            raw.keys.firstOrNull { it.toString() !in allowed }?.let { bad(it.toString(), "unknown meta key (allowed: ${allowed.joinToString()})") }
            fun text(key: String): Map<String, String> {
                val v = raw[key] ?: return emptyMap()
                val m = v as? Map<*, *> ?: bad(key, "expected a {ja, en} map")
                return m.entries.associate { (k, t) -> k.toString() to (t?.toString() ?: bad(key, "empty text for '$k'")) }
            }
            fun nat(key: String): Int? {
                val v = raw[key] ?: return null
                val n = (v as? Number)?.toInt() ?: bad(key, "expected an integer")
                if (n < 0) bad(key, "must be >= 0")
                return n
            }
            val resource = raw["resource_type"]?.toString()
            if (resource != null && resource !in setOf("ammo", "energy")) bad("resource_type", "expected ammo or energy")
            return SkillMeta(
                name = text("name"),
                description = text("description"),
                cooldownTicks = nat("cooldown_ticks") ?: 0,
                resourceType = resource,
                resourceCost = nat("resource_cost"),
                egoCost = nat("ego_cost") ?: 0,
                reloadTicks = nat("reload_ticks") ?: 0,
                readyCue = raw["ready_cue"] as? Boolean,
                tags = (raw["tags"] as? List<*>)?.map { it.toString() } ?: emptyList(),
            )
        }

        /** A skin may only touch `presentation.*` (the composer records every touched leaf in `_patch.touched`). */
        fun checkPatchMeta() {
            val meta = composed.tree["_patch"] ?: return
            val m = meta as? Map<*, *> ?: ValueDecoding.mismatch(skillId, "_patch", "map", meta)
            if (m["kind"] != "skin") return
            val touched = (m["touched"] as? List<*>).orEmpty().map { it.toString() }
            val bad = touched.firstOrNull { it != "presentation" && !it.startsWith("presentation.") && !it.startsWith("presentation[") }
            if (bad != null) {
                throw DecodeAbort(SkillDecodeError.Invalid(skillId, bad, "a skin patch may only touch presentation.*, but touches '$bad'"))
            }
        }

        fun decodeFrames(list: List<*>?): List<FrameDecl> {
            if (list == null) return emptyList()
            val seen = HashSet<String>()
            return list.mapIndexed { i, raw ->
                val r = reader(OpSection.GAMEPLAY, "frames", raw, i)
                val id = r.string("id") ?: throw DecodeAbort(SkillDecodeError.MissingField(skillId, "frames[$i]", "id"))
                val path = "frames[$id]"
                val entry = DefaultOpReader(ctx, OpSection.GAMEPLAY, path, raw as Map<*, *>)
                entry.allow(SectionRules.FRAME_KEYS)
                if (!seen.add(id) || id in SectionRules.BUILTIN_FRAMES) {
                    throw DecodeAbort(SkillDecodeError.DuplicateId(skillId, path, id))
                }
                val kindName = entry.string("kind") ?: entry.missing("kind")
                val kind = SectionRules.DECLARABLE_KINDS[kindName]
                    ?: ValueDecoding.mismatch(skillId, "$path.kind", SectionRules.DECLARABLE_KINDS.keys.joinToString("|"), kindName)
                val parent = entry.point("parent") ?: entry.missing("parent")
                val decl = FrameDecl(
                    id = id,
                    kind = kind,
                    parent = parent,
                    model = entry.string("model"),
                    part = entry.string("part"),
                    offset = entry.literalVec3("offset"),
                    inactive = entry.bool("inactive") ?: false,
                )
                entry.consume("id")
                entry.failOnUnconsumed()
                decl
            }
        }

        fun decodeParams(map: Map<*, *>?, path: String): Map<String, ParamValue> {
            if (map == null) return emptyMap()
            val out = LinkedHashMap<String, ParamValue>()
            for ((k, v) in map) {
                val name = k.toString()
                out[name] = when (v) {
                    is Number -> ParamValue.Num(v.toDouble())
                    is Boolean -> ParamValue.Flag(v)
                    is String -> ParamValue.Text(v)
                    else -> ValueDecoding.mismatch(skillId, "$path.$name", "number, boolean or string", v)
                }
            }
            return out
        }

        fun decodeExpose(list: List<*>?): List<ExposedParam> {
            if (list == null) return emptyList()
            val seen = HashSet<String>()
            return list.mapIndexed { i, raw ->
                val probe = (raw as? Map<*, *>) ?: ValueDecoding.mismatch(skillId, "gameplay.expose[$i]", "map", raw)
                val id = probe["id"] as? String ?: throw DecodeAbort(SkillDecodeError.MissingField(skillId, "gameplay.expose[$i]", "id"))
                val path = "gameplay.expose[$id]"
                if (!seen.add(id)) throw DecodeAbort(SkillDecodeError.DuplicateId(skillId, path, id))
                val r = DefaultOpReader(ctx, OpSection.GAMEPLAY, path, probe)
                r.consume("id")
                val param = r.string("param") ?: r.missing("param")
                val min = r.literalDouble("min")
                val max = r.literalDouble("max")
                r.failOnUnconsumed()
                ExposedParam(id, param, min, max)
            }
        }

        fun <O : SkillOp> decodeTimeline(list: List<*>?, section: OpSection, base: String, cast: (SkillOp) -> O): List<TimelineEntry<O>> {
            if (list == null) return emptyList()
            return list.mapIndexed { i, raw ->
                val probe = (raw as? Map<*, *>) ?: ValueDecoding.mismatch(skillId, "$base.timeline[$i]", "map", raw)
                val id = probe["id"] as? String ?: throw DecodeAbort(SkillDecodeError.MissingField(skillId, "$base.timeline[$i]", "id"))
                val path = "$base.timeline[$id]"
                if (!rowIds.add("$base/$id")) throw DecodeAbort(SkillDecodeError.DuplicateId(skillId, path, id))
                val r = DefaultOpReader(ctx, section, path, probe)
                r.allow(SectionRules.ROW_KEYS)
                r.consume("id")
                val atRaw = r.raw("at") ?: r.missing("at")
                val at = SectionRules.parseAt(atRaw)
                    ?: ValueDecoding.mismatch(skillId, "$path.at", "tick or '<event>[+-N]'", atRaw)
                val every = r.int("every")
                val until = r.string("until")
                val whenExpr = r.boolValue("when")
                val ops = r.ops("do").map(cast)
                TimelineEntry(at, ops, every, until, whenExpr)
            }
        }

        fun <O : SkillOp> decodeOn(map: Map<*, *>?, section: OpSection, base: String, cast: (SkillOp) -> O): Map<String, List<O>> {
            if (map == null) return emptyMap()
            val out = LinkedHashMap<String, List<O>>()
            for ((k, v) in map) {
                val event = k.toString()
                out[event] = ctx.decodeOps(section, v, "$base.on.$event").map(cast)
            }
            return out
        }

        fun decodeGameplay(map: Map<*, *>?): GameplaySection {
            if (map == null) return GameplaySection()
            val r = DefaultOpReader(ctx, OpSection.GAMEPLAY, "gameplay", map)
            r.allow(SectionRules.GAMEPLAY_KEYS)
            val params = decodeParams(r.mapOrNull("params"), "gameplay.params")
            val expose = decodeExpose(r.listOrNull("expose"))
            val timeline = decodeTimeline(r.listOrNull("timeline"), OpSection.GAMEPLAY, "gameplay", ::asGameplay)
            val on = decodeOn(r.mapOrNull("on"), OpSection.GAMEPLAY, "gameplay", ::asGameplay)
            return GameplaySection(params, expose, timeline, on)
        }

        fun decodePresentation(map: Map<*, *>?): PresentationSection {
            if (map == null) return PresentationSection()
            val r = DefaultOpReader(ctx, OpSection.PRESENTATION, "presentation", map)
            r.allow(SectionRules.PRESENTATION_KEYS)
            val params = decodeParams(r.mapOrNull("params"), "presentation.params")
            val bodyAnim = r.string("body_anim")
            val timeline = decodeTimeline(r.listOrNull("timeline"), OpSection.PRESENTATION, "presentation", ::asPresentation)
            val on = decodeOn(r.mapOrNull("on"), OpSection.PRESENTATION, "presentation", ::asPresentation)
            return PresentationSection(params, bodyAnim, timeline, on)
        }

        fun asGameplay(op: SkillOp): GameplayOp =
            op as? GameplayOp ?: throw DecodeAbort(SkillDecodeError.UnknownOp(skillId, op.id, op::class.simpleName.orEmpty(), "gameplay"))

        fun asPresentation(op: SkillOp): PresentationOp =
            op as? PresentationOp ?: throw DecodeAbort(SkillDecodeError.UnknownOp(skillId, op.id, op::class.simpleName.orEmpty(), "presentation"))
    }
}
