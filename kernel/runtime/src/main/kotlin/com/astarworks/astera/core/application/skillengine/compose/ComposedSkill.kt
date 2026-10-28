package com.astarworks.astera.core.application.skillengine.compose

/**
 * Composition metadata of one skill: `kind` is the strongest patch kind applied (`balance` > `skin` > `base` for none),
 * `touched` the sorted dotted paths the applied patch bodies name. Lives beside the tree, never inside it, so the
 * document decoder sees a plain `astera/v3` document.
 */
data class PatchMeta(val kind: String, val touched: List<String>) {
    companion object {
        val BASE: PatchMeta = PatchMeta("base", emptyList())
    }
}

/**
 * One composed document: the generic `astera/v3` tree (`apiVersion`, `kind`, `id`, then the body; a variant has id
 * `<base>@<variant>`) ready for `SkillDocumentDecoder.decode`. [sources] are the files (relative to the compose root)
 * that fed it. A `kind: Part` document composes the same way but is never patched.
 */
data class ComposedSkill(
    val id: String,
    val tree: Map<String, Any?>,
    val sources: List<String>,
    val patch: PatchMeta = PatchMeta.BASE,
) {
    val isPart: Boolean get() = tree["kind"] == "Part"

    /** Index key: the id for a skill, `part:<id>` for a part (the two id spaces are separate). */
    val key: String get() = if (isPart) "part:$id" else id
}
