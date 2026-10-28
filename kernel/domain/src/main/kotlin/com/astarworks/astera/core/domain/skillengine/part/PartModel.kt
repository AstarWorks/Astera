package com.astarworks.astera.core.domain.skillengine.part

/** An op as it appears in YAML: a generic map. Part expansion and lowering are tree-to-tree, typed decoding stays where it is. */
public typealias OpMap = Map<String, Any?>

/** Parameter types of a Part (ADR-0303 section 9). Any argument may also be a CEL string `= ...`, judged in the caller. */
public enum class ParamType(public val key: String) {
    TICKS("ticks"),
    BLOCKS("blocks"),
    NUMBER("number"),
    BOOL("bool"),
    STRING("string"),
    ;

    public companion object {
        public fun of(key: String): ParamType? = entries.firstOrNull { it.key == key }
    }
}

/** A typed parameter: either [required] (no default) or carrying an explicit identity [default] (ADR-0303 2.0b). */
public data class ParamSpec(val name: String, val type: ParamType, val required: Boolean, val default: Any?)

/** `use: <part>` with `with:` arguments and `frames:` bindings (frame name in the part -> frame id in the caller). */
public data class PartCall(val part: String, val args: Map<String, Any?>, val frames: Map<String, String>)

/**
 * One transition. Exactly one of [event] / [afterTicks] is set: an event transition waits for [event], a timer
 * transition fires [afterTicks] after entering the state (0 = immediately, in the same tick).
 * [effects] run between the exit of the source and the entry of [to]; `{emit: name}` entries raise an event.
 */
public data class MachineTransition(
    val event: String?,
    val afterTicks: Any?,
    val whenExpr: String?,
    val to: String,
    val effects: List<OpMap>,
)

/** [forTicks] is a number or a CEL string; with [then] it is the implicit timer transition. */
public data class MachineState(
    val id: String,
    val forTicks: Any?,
    val then: String?,
    val final: Boolean,
    val enter: List<OpMap>,
    val doOps: List<OpMap>,
    val exit: List<OpMap>,
    val on: List<MachineTransition>,
    val use: PartCall?,
)

/** A caller transition that could not be folded into the flat state graph (needs the runtime Transition op, WP-D D4). */
public data class EventLink(val callPath: String, val event: String, val transition: MachineTransition, val reason: String)

public data class Machine(
    val start: String,
    val states: Map<String, MachineState>,
    val links: List<EventLink> = emptyList(),
)

public data class Part(
    val id: String,
    val params: Map<String, ParamSpec>,
    val framesIn: Map<String, String>,
    val framesOut: Map<String, String>,
    val emits: List<String>,
    val machine: Machine,
)

/** Why a part could not be parsed, expanded or lowered. [path] is a dotted location in the YAML or the state path. */
public sealed class PartError(public val path: String, public val message: String) {
    public class Invalid(path: String, message: String) : PartError(path, message)
    public class UnknownPart(path: String, public val part: String) : PartError(path, "unknown part '$part'")
    public class Cycle(path: String, public val chain: List<String>) : PartError(path, "part call cycle: ${chain.joinToString(" -> ")}")
    public class TooDeep(path: String, public val limit: Int) : PartError(path, "part nesting deeper than $limit")
    public class Lint(path: String, message: String) : PartError(path, message)

    override fun toString(): String = "$path: $message"
}
