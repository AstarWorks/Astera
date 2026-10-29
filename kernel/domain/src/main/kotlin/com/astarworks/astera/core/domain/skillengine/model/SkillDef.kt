package com.astarworks.astera.core.domain.skillengine.model

import com.astarworks.astera.core.domain.skillengine.part.Machine
import com.astarworks.astera.core.domain.skillengine.part.Part
import com.astarworks.astera.core.domain.skillengine.part.ParamSpec

/** Well-known event names (authors may define more). */
public object SkillEvents {
    public const val START: String = "start"
    public const val INPUT_USE: String = "input.use"
    public const val INPUT_RELEASE: String = "input.release"
    public const val HIT: String = "hit"
    public const val BLOCK: String = "block"
    public const val STICK: String = "stick"
    public const val REEL: String = "reel"
    public const val ARRIVE: String = "arrive"
    public const val EXPIRE: String = "expire"
    public const val END: String = "end"
}

/**
 * Skill header: what the cast gate (cooldown, resource, E.G.O, reload) and the UI need, which the timeline cannot
 * express. [name] / [description] are inline `{ja, en}` maps (the same i18n shape as items).
 */
public data class SkillMeta(
    val name: Map<String, String> = emptyMap(),
    val description: Map<String, String> = emptyMap(),
    val cooldownTicks: Int = 0,
    /** `ammo` / `energy`; null = the source item's resource type. */
    val resourceType: String? = null,
    /** null = the source item's per-use cost. */
    val resourceCost: Int? = null,
    val egoCost: Int = 0,
    val reloadTicks: Int = 0,
    /** Sound when the cooldown ends; null = on for cooldowns of 3 s or more. */
    val readyCue: Boolean? = null,
    val tags: List<String> = emptyList(),
)

/** The typed skill (one cast definition, base or `id@variant`). Immutable once decoded. */
public data class SkillDef(
    val id: String,
    val item: String?,
    val importance: Int?,
    val meta: SkillMeta = SkillMeta(),
    val params: Map<String, ParamSpec> = emptyMap(),
    val framesIn: Map<String, String> = emptyMap(),
    val emits: List<String> = emptyList(),
    val anchors: List<AnchorDecl> = emptyList(),
    /** Named conditions (3.8), CEL source. */
    val conditions: Map<String, String> = emptyMap(),
    /** Skill-local fragments (`fragments:`): flat op lists called by `use: fragment:<name>`; part ids are `<skill>#<name>`. */
    val fragments: Map<String, Part> = emptyMap(),
    val machine: Machine = Machine(start = "", states = emptyMap()),
) {
    /** Base id without `@variant`. */
    val baseId: String get() = id.substringBefore('@')
}

/** `anchors:` entry (ADR-0303 1): a named point on the caster or a frame, with an optional offset (a number, `${param}` or `= CEL`) and a mandatory [mode]. */
public data class AnchorDecl(
    val id: String,
    val anchor: PointRef,
    val offset: Map<String, Value<Double>>,
    val mode: AnchorMode,
)

/** `follow` re-reads the parent at every send (the identity); `snapshot` copies the pose once at activation. */
public enum class AnchorMode { FOLLOW, SNAPSHOT }

/** `importance` of a skill (v3 header). */
@JvmInline
public value class Importance(public val value: Int)
