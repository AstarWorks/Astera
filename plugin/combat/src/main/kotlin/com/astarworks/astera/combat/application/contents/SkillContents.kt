package com.astarworks.astera.combat.application.contents

import com.astarworks.astera.combat.application.config.SkillYamlConfig
import com.astarworks.astera.combat.application.port.outbound.ICelEvaluator
import com.astarworks.astera.combat.application.port.outbound.ISkillRegistry
import com.astarworks.astera.combat.application.service.StubCelEvaluator
import com.astarworks.astera.combat.domain.skill.BakedSkill
import com.astarworks.astera.combat.domain.skill.SkillCost
import com.astarworks.astera.combat.domain.skill.SkillSpec
import com.astarworks.astera.combat.domain.skill.expression.CelSkillScript
import com.astarworks.astera.combat.domain.skill.expression.CompiledCelScript
import com.astarworks.astera.core.application.contents.YamlDynamicContents
import com.astarworks.astera.core.domain.skill.SkillId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Skill-side `Contents<SkillId, SkillSpec>` (Wave _infra-2b, ADR-0065).
 *
 * Replaces the legacy `MutableSkillRegistry` + `SkillLoaderService` pair with
 * a single instance that owns an atomic-swap store and participates in the
 * cross-plugin reload registry.
 *
 * Inherits:
 *
 *   - `find(id) / all()` from [com.astarworks.astera.core.application.contents.Contents]
 *     (the `ISkillRegistry` port surface that every skill use case consumes)
 *   - `bootstrap(dir)` for `onEnable` initial load (best-effort)
 *   - `reload(dir)` for `/astera reload skills` (all-or-nothing, atomic swap)
 *   - `register(id, spec) / clear()` for tests and programmatic registration
 *
 * Cross-field invariants on the YAML (archetype↔params variant matching) live
 * in [SkillYamlConfig.toSpec] — if a YAML file violates them, `toSpec` throws
 * and `ContentLoader` counts a failed load without breaking the batch.
 *
 * **Wave _items-1 (ADR-0073)**: each skill YAML is self-contained — display
 * name / description ride on the [SkillSpec] as `LocalizedText` (a per-locale
 * map). No sibling write-back into `content/languages/<locale>.yaml` is performed on
 * reload, so removing or renaming a skill never leaves orphan i18n keys.
 */
public class SkillContents(
    private val evaluator: ICelEvaluator = StubCelEvaluator(),
    private val vfx: com.astarworks.astera.core.application.port.outbound.IVfxRegistry? = null,
    private val particles: com.astarworks.astera.core.application.port.outbound.IParticleRegistry? = null,
    private val costWarningOps: Long = System.getProperty("astera.skills.costWarningOps", "100000").toLong(),
) : YamlDynamicContents<SkillId, SkillSpec, SkillYamlConfig>(
    categoryName = "skills",
    deserializer = SkillYamlConfig.serializer(),
), ISkillRegistry {

    /**
     * Convert each YAML DTO to its `(SkillId, SkillSpec)` entry. Delegates to
     * [SkillYamlConfig.toSpec] so any future schema evolution stays
     * single-sourced in the DTO.
     */
    override fun toEntry(config: SkillYamlConfig): Pair<SkillId, SkillSpec> {
        val programs = linkedMapOf<String, CompiledCelScript>()
        fun collect(node: JsonElement) {
            when (node) {
                is JsonObject -> {
                    val source = (node["script"] as? JsonPrimitive)?.content
                    if (source != null) programs.getOrPut(source) {
                        evaluator.compile(CelSkillScript(source))
                    }
                    node.values.forEach(::collect)
                }
                is JsonArray -> node.forEach(::collect)
                else -> Unit
            }
        }
        collect(Json.encodeToJsonElement(SkillYamlConfig.serializer(), config))
        val raw = config.toSpec()
        val estimate = SkillCost.estimate(raw.phases, raw.params,
            vfx = { SkillCostReport.vfxCost(vfx, it) },
            particle = { id, count -> SkillCostReport.particleCost(particles, id, count) },
        )
        if (estimate > costWarningOps) LoggerFactory.getLogger(javaClass).warn(
            "Skill {} estimated lifetime ops {} exceed warning threshold {}", raw.id,
            if (estimate == Long.MAX_VALUE) "unbounded" else estimate.toString(), costWarningOps,
        )
        val spec = raw.copy(baked = BakedSkill(raw.baked.hitShape, programs.toMap(), estimate))
        return spec.id to spec
    }

    /**
     * Convenience overload: register by spec alone (the spec owns its id).
     * Used by test harnesses and admin commands that build a `SkillSpec`
     * directly in memory.
     */
    public fun register(spec: SkillSpec) {
        register(spec.id, spec)
    }
}
