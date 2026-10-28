package com.astarworks.astera.core.application.skillengine.load

import com.astarworks.astera.core.domain.skillengine.frame.FrameKind
import com.astarworks.astera.core.domain.skillengine.model.TimelineAt

/** Structural rules of the frozen grammar: which keys each level accepts. */
internal object SectionRules {
    val SKILL_KEYS = setOf("id", "item", "importance", "meta", "frames", "gameplay", "presentation", "_patch")
    val FRAME_KEYS = setOf("id", "kind", "parent", "model", "part", "offset", "inactive", "orient", "scale", "passenger", "teleport_ticks", "moving_teleport_ticks")
    val GAMEPLAY_KEYS = setOf("params", "expose", "timeline", "on")
    val PRESENTATION_KEYS = setOf("params", "body_anim", "timeline", "on")
    val EXPOSE_KEYS = setOf("id", "param", "min", "max")
    val ROW_KEYS = setOf("id", "at", "do", "every", "for", "until", "when")

    /** Frame kinds an author may declare; world/caster/enemy/contact are built in. */
    val DECLARABLE_KINDS: Map<String, FrameKind> = mapOf(
        "item" to FrameKind.ITEM,
        "part" to FrameKind.PART,
        "body" to FrameKind.BODY,
        "point" to FrameKind.POINT,
        "bone" to FrameKind.BONE,
    )

    /** Frames that exist without a `frames:` entry, plus the per-op pseudo frames `self` (hit volumes) and `line`. */
    val BUILTIN_FRAMES = setOf("world", "caster", "aim", "enemy", "contact", "self", "line")

    private val AT_EVENT = Regex("""^([A-Za-z_][A-Za-z0-9_]*)\s*(?:([+-])\s*(\d+))?$""")

    /** `at: 4` or `at: "release+2"`. Returns null when malformed. */
    fun parseAt(raw: Any?): TimelineAt? = when (raw) {
        is Int -> raw.takeIf { it >= 0 }?.let { TimelineAt.Tick(it) }
        is Long -> raw.takeIf { it in 0..Int.MAX_VALUE }?.let { TimelineAt.Tick(it.toInt()) }
        is String -> AT_EVENT.matchEntire(raw.trim())?.let { m ->
            val sign = if (m.groupValues[2] == "-") -1 else 1
            val n = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt() ?: 0
            TimelineAt.AfterEvent(m.groupValues[1], sign * n)
        }
        else -> null
    }
}
