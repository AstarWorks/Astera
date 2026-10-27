package com.astarworks.astera.core.domain.skillengine.model

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.frame.FrameKind
import com.astarworks.astera.core.domain.skillengine.modifier.ExposedParam
import com.astarworks.astera.core.domain.skillengine.op.GameplayOp
import com.astarworks.astera.core.domain.skillengine.op.PresentationOp
import com.astarworks.astera.core.domain.skillengine.op.SkillOp

/** Well-known event names (authors may define more). */
public object SkillEvents {
    public const val START: String = "start"
    public const val RELEASE: String = "release"
    public const val HIT: String = "hit"
    public const val BLOCK: String = "block"
    public const val STICK: String = "stick"
    public const val REEL: String = "reel"
    public const val ARRIVE: String = "arrive"
    public const val EXPIRE: String = "expire"
    public const val END: String = "end"
}

/** `frames:` entry, shared by both sections. [parent] is a point reference (e.g. `caster.right_hand`). */
public data class FrameDecl(
    val id: String,
    val kind: FrameKind,
    val parent: PointRef,
    /** Item/model whose point table (`content/frames/<model>.yaml`, F-3) defines this frame's points. */
    val model: String? = null,
    val part: String? = null,
    val offset: Vec3? = null,
    /** Declared but not attached until a reparent op moves things under it. */
    val inactive: Boolean = false,
)

/** `at:` of a timeline row: absolute tick since cast start, or an event plus an offset. */
public sealed interface TimelineAt {
    public data class Tick(val tick: Int) : TimelineAt
    public data class AfterEvent(val event: String, val offsetTicks: Int = 0) : TimelineAt
}

public data class TimelineEntry<out O : SkillOp>(
    val at: TimelineAt,
    val ops: List<O>,
    val everyTicks: Int? = null,
    val untilEvent: String? = null,
    val whenExpr: Value<Boolean>? = null,
)

public data class GameplaySection(
    val params: Map<String, ParamValue> = emptyMap(),
    val expose: List<ExposedParam> = emptyList(),
    val timeline: List<TimelineEntry<GameplayOp>> = emptyList(),
    /** event name -> ordered op list (each op has a unique id). */
    val on: Map<String, List<GameplayOp>> = emptyMap(),
)

public data class PresentationSection(
    val params: Map<String, ParamValue> = emptyMap(),
    /** `content/animations/` key set; bone-local rotation keys only. */
    val bodyAnim: String? = null,
    val timeline: List<TimelineEntry<PresentationOp>> = emptyList(),
    val on: Map<String, List<PresentationOp>> = emptyMap(),
)

/** The typed skill (one cast definition, base or `id@variant`). Immutable once decoded. */
public data class SkillDef(
    val id: String,
    val item: String?,
    val importance: Int?,
    val frames: List<FrameDecl>,
    val gameplay: GameplaySection,
    val presentation: PresentationSection,
) {
    /** Base id without `@variant`. */
    val baseId: String get() = id.substringBefore('@')
}
