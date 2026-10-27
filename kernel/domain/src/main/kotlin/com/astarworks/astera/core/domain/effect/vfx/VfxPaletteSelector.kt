package com.astarworks.astera.core.domain.effect.vfx

/**
 * Picks the stage-palette variant of a vfx at play time. A vfx file with a `palette:` block registers `id` (the first
 * variant) plus `id@<variant>` per variant; the stage's `vfx_palette` tag names the variant.
 */
public object VfxPaletteSelector {
    /** `base@<stageTag>` when that variant is registered, otherwise [base] (no tag, or a tag this vfx has no variant for). */
    public fun select(base: VfxId, stageTag: String?, exists: (VfxId) -> Boolean): VfxId {
        val tag = stageTag?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return base
        if (base.value.contains('@') || !tag.matches(TAG)) return base
        val variant = VfxId("${base.value}@$tag")
        return if (exists(variant)) variant else base
    }

    private val TAG = Regex("^[a-z][a-z0-9-]*$")
}
