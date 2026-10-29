package com.astarworks.astera.core.application.skillengine.load

import com.astarworks.astera.core.domain.skillengine.expr.ExprError

public sealed interface SkillDecodeError {
    public val skillId: String
    public val path: String
    public val message: String

    public data class MissingField(override val skillId: String, override val path: String, val name: String) : SkillDecodeError {
        override val message: String get() = "missing '$name'"
    }

    public data class UnknownKey(override val skillId: String, override val path: String, val key: String) : SkillDecodeError {
        override val message: String get() = "unknown key '$key'"
    }

    public data class TypeMismatch(override val skillId: String, override val path: String, val expected: String, val actual: String) : SkillDecodeError {
        override val message: String get() = "expected $expected but found $actual"
    }

    public data class UnknownOp(override val skillId: String, override val path: String, val op: String, val section: String) : SkillDecodeError {
        override val message: String get() = "op '$op' is not allowed in section '$section'"
    }

    /** R36: a state with `states:` children declares no `start: <child>`. [state] is its flat path. */
    public data class NestedStartMissing(override val skillId: String, val state: String) : SkillDecodeError {
        override val path: String get() = "machine.states.$state.start"
        override val message: String get() = "state '$state' has child states and must declare start: <child>"
    }

    /** R36: `machine.start` names a child state (`p/c`); it must name a top-level state. */
    public data class StartNamesChild(override val skillId: String, val start: String) : SkillDecodeError {
        override val path: String get() = "machine.start"
        override val message: String get() = "machine.start '$start' names a child state; it must name a top-level state"
    }

    public data class DuplicateId(override val skillId: String, override val path: String, val id: String) : SkillDecodeError {
        override val message: String get() = "duplicate id '$id'"
    }

    public data class UndefinedReference(override val skillId: String, override val path: String, val ref: String) : SkillDecodeError {
        override val message: String get() = "undefined frame/point/param '$ref'"
    }

    /** Any other rule violation (`fail(message)` from an op decoder, skin patch touching gameplay, bad `at:`, ...). */
    public data class Invalid(override val skillId: String, override val path: String, override val message: String) : SkillDecodeError

    public data class BadExpr(override val skillId: String, override val path: String, val error: ExprError) : SkillDecodeError {
        override val message: String get() = error.message
    }

    // ---- v3 static-check errors (ADR-0303 rev4 10.4, 11.4). [skillId] trails with a default so the call shape stays the frozen one. ----

    /** Check 9: `rand`, `t` or an L value without a bound in a timing field. */
    public data class RuntimeTiming(override val path: String, val timingField: String, val why: String, override val skillId: String = "") : SkillDecodeError {
        override val message: String get() = "timing field '$timingField' is decided at run time: $why"
    }

    /** 2.0 / check 11: an `every` without `for`, `times` or `until`. */
    public data class UnboundedRepeat(override val path: String, override val skillId: String = "") : SkillDecodeError {
        override val message: String get() = "unbounded repeat"
    }

    /** Checks 2 / 8: an op fires or lives past the end of its state. */
    public data class StateOverrun(val state: String, val op: String, val lastTick: Int, val forTicks: Int, override val skillId: String = "") : SkillDecodeError {
        override val path: String get() = state
        override val message: String get() = "op '$op' reaches tick $lastTick but state '$state' lasts $forTicks"
    }

    /** 3.8: `cond:<name>` / `cond.<name>` without a `conditions:` entry. */
    public data class UnknownCondition(override val path: String, val name: String, override val skillId: String = "") : SkillDecodeError {
        override val message: String get() = "unknown condition '$name'"
    }

    /** Check 10. */
    public data class LiveDisplayCap(val peak: Int, val cap: Int, val tick: Int, override val skillId: String = "") : SkillDecodeError {
        override val path: String get() = "tick $tick"
        override val message: String get() = "$peak displays alive at tick $tick exceed max_live_displays $cap"
    }

    /** Check 11. */
    public data class InstanceCap(val op: String, val count: Int, val cap: Int, override val skillId: String = "") : SkillDecodeError {
        override val path: String get() = op
        override val message: String get() = "op '$op' has $count instances, cap is $cap"
    }

    /** Check 11. */
    public data class GroupDepth(override val path: String, val depth: Int, val cap: Int, override val skillId: String = "") : SkillDecodeError {
        override val message: String get() = "group depth $depth exceeds $cap"
    }

    /** Check 12. */
    public data class TableClosure(val event: String, val why: String, override val skillId: String = "") : SkillDecodeError {
        override val path: String get() = event
        override val message: String get() = "event '$event': $why"
    }

    /** Check 12 (10.2a): a chain of same-tick event handlers deeper than the cap. */
    public data class SameTickDepth(val event: String, val depth: Int, val cap: Int, override val skillId: String = "") : SkillDecodeError {
        override val path: String get() = event
        override val message: String get() = "event '$event': same-tick depth $depth exceeds $cap"
    }

    /** 10.4 checks 1-7 (one subtype, numbered). */
    public data class MachineCheck(val check: Int, val detail: String, override val skillId: String = "") : SkillDecodeError {
        override val path: String get() = "check $check"
        override val message: String get() = detail
    }
}
