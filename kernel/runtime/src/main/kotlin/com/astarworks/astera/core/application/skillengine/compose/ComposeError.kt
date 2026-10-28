package com.astarworks.astera.core.application.skillengine.compose

/**
 * Every composition failure carries the file (relative to the compose root, `/` separated), the YAML path inside
 * that file / tree (`a.b[id].c`, empty for the whole document) and a human-readable reason.
 */
sealed interface ComposeError {
    val file: String
    val path: String
    val reason: String

    /** The file is not parseable YAML (syntax error, duplicate key, unreadable). */
    data class ParseError(override val file: String, override val path: String, override val reason: String) : ComposeError

    /** An `astera/v3` document is structurally invalid (unknown kind, missing id / target / patchKind, bad id). */
    data class InvalidDocument(override val file: String, override val path: String, override val reason: String) : ComposeError

    /** Two `kind: Skill` documents with the same id live in different files / directories. */
    data class DuplicateSkill(override val file: String, override val path: String, override val reason: String) : ComposeError

    /** Failures raised while applying a `kind: Patch` document. */
    sealed interface PatchError : ComposeError {
        /** A skin patch touched something other than the fields of a presentation op. [path] is the first offending leaf path. */
        data class ForbiddenPath(override val file: String, override val path: String) : PatchError {
            override val reason: String get() = "skin patches may only touch presentation ops (machine.states.*.{enter,do,exit}[<id>]), but touch '$path'"
        }

        /** The patch `target` has no `kind: Skill` document. */
        data class TargetNotFound(override val file: String, override val path: String, override val reason: String) : PatchError

        /** A list of maps is merged by `id`, but an entry has none. */
        data class MissingId(override val file: String, override val path: String, override val reason: String) : PatchError
    }
}
