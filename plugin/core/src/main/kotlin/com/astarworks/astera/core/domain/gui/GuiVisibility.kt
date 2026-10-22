package com.astarworks.astera.core.domain.gui

/**
 * Per-viewer visibility conditions for GUI slots and lobby-kit entries (ADR-0240).
 *
 * A condition is a plain string so it can live in `content/gui/<name>.yaml` and
 * `lobby-kit.yaml` (`visible_if: permission:astera.admin.panel`). Grammar:
 *
 * - `permission:<node>` - the viewer holds the permission node, or is a server operator.
 *
 * `null` means "always visible". Unknown grammar is rejected at construction time so a typo
 * can never silently expose an admin tile; evaluation of anything unrecognised is `false`.
 */
public object GuiVisibility {

    private const val PERMISSION_PREFIX: String = "permission:"

    /** Throws [IllegalArgumentException] unless [condition] is `null` or well-formed. */
    public fun validate(condition: String?) {
        if (condition == null) return
        require(permissionNode(condition) != null) {
            "unsupported visible_if '$condition' (supported: permission:<node>)"
        }
    }

    /** The permission node of a `permission:<node>` [condition], or `null` for any other shape. */
    public fun permissionNode(condition: String): String? =
        condition.takeIf { it.startsWith(PERMISSION_PREFIX) }
            ?.removePrefix(PERMISSION_PREFIX)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /** Whether a viewer sees something guarded by [condition]; [hasPermission] answers `permission:` nodes. */
    public fun isVisible(condition: String?, hasPermission: (node: String) -> Boolean): Boolean {
        if (condition == null) return true
        val node = permissionNode(condition) ?: return false
        return hasPermission(node)
    }

    /** [spec] without the [GuiSlot.Static] slots whose `visibleIf` the viewer does not satisfy. */
    public fun filter(spec: GuiSpec, hasPermission: (node: String) -> Boolean): GuiSpec {
        val hidden = spec.slots.filter { (_, slot) ->
            slot is GuiSlot.Static && !isVisible(slot.visibleIf, hasPermission)
        }.keys
        if (hidden.isEmpty()) return spec
        var kept = spec.slots
        for (index in hidden) kept = kept.remove(index)
        return spec.copy(slots = kept)
    }
}
