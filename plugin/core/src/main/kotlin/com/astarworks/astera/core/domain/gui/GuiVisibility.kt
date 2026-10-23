package com.astarworks.astera.core.domain.gui

/**
 * Per-viewer visibility conditions for GUI slots and lobby-kit entries (ADR-0240).
 *
 * A condition is a plain string so it can live in `content/gui/<name>.yaml` and
 * `lobby-kit.yaml` (`visible_if: permission:astera.admin.panel`). Grammar:
 *
 * - `permission:<node>` - the viewer holds the permission node, or is a server operator.
 * - `state:queued` / `state:idle` - the viewer is (not) waiting in a match queue (ADR-0248); lets the
 *   lobby show "Leave queue" instead of "Play" without a second code path.
 *
 * `null` means "always visible". Unknown grammar is rejected at construction time so a typo
 * can never silently expose an admin tile; evaluation of anything unrecognised is `false`.
 */
public object GuiVisibility {

    private const val PERMISSION_PREFIX: String = "permission:"
    private const val STATE_PREFIX: String = "state:"

    /** The viewer states a `state:<name>` condition may ask about. */
    public val STATES: Set<String> = setOf("queued", "idle")

    /** Throws [IllegalArgumentException] unless [condition] is `null` or well-formed. */
    public fun validate(condition: String?) {
        if (condition == null) return
        require(permissionNode(condition) != null || stateName(condition) != null) {
            "unsupported visible_if '$condition' (supported: permission:<node>, state:queued, state:idle)"
        }
    }

    /** The permission node of a `permission:<node>` [condition], or `null` for any other shape. */
    public fun permissionNode(condition: String): String? =
        condition.takeIf { it.startsWith(PERMISSION_PREFIX) }
            ?.removePrefix(PERMISSION_PREFIX)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /** The state of a `state:<name>` [condition], or `null` for any other shape / an unknown state. */
    public fun stateName(condition: String): String? =
        condition.takeIf { it.startsWith(STATE_PREFIX) }
            ?.removePrefix(STATE_PREFIX)
            ?.trim()
            ?.takeIf { it in STATES }

    /**
     * Whether a viewer sees something guarded by [condition]; [hasPermission] answers `permission:` nodes and
     * [hasState] answers `state:` conditions (default: no state holds, so such slots fail closed).
     */
    public fun isVisible(
        condition: String?,
        hasPermission: (node: String) -> Boolean,
        hasState: (state: String) -> Boolean = { false },
    ): Boolean {
        if (condition == null) return true
        permissionNode(condition)?.let { return hasPermission(it) }
        stateName(condition)?.let { return hasState(it) }
        return false
    }

    /** [spec] without the [GuiSlot.Static] slots whose `visibleIf` the viewer does not satisfy. */
    public fun filter(
        spec: GuiSpec,
        hasPermission: (node: String) -> Boolean,
        hasState: (state: String) -> Boolean = { false },
    ): GuiSpec {
        val hidden = spec.slots.filter { (_, slot) ->
            slot is GuiSlot.Static && !isVisible(slot.visibleIf, hasPermission, hasState)
        }.keys
        if (hidden.isEmpty()) return spec
        var kept = spec.slots
        for (index in hidden) kept = kept.remove(index)
        return spec.copy(slots = kept)
    }
}
