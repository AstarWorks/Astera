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

    /** An `astera/v3` document is structurally invalid (unknown kind, missing id / target / patchKind / ops, bad id). */
    data class InvalidDocument(override val file: String, override val path: String, override val reason: String) : ComposeError

    /** Two `kind: Skill` documents with the same id live in different files / directories. */
    data class DuplicateSkill(override val file: String, override val path: String, override val reason: String) : ComposeError

    /** A tier document (`extends:` + `with:`, ADR-0303 9.3) breaks a derivation rule; [reason] is the exact load-error text. */
    data class Extends(override val file: String, override val path: String, override val reason: String) : ComposeError

    /** Failures raised while applying a `kind: Patch` document. */
    sealed interface PatchError : ComposeError {
        /** An op of a `kind: Patch` changes something its `patchKind` does not allow (check 13). [path] is the first offending path as written. */
        data class PatchKindViolation(override val file: String, override val path: String, val patchKind: String, val why: String) : PatchError {
            override val reason: String get() = "PatchKindViolation: a $patchKind patch cannot change '$path': $why"
        }

        /** The patch `target` has no `kind: Skill` (or, for `part:<id>`, `kind: Part`) document. */
        data class TargetNotFound(override val file: String, override val path: String, override val reason: String) : PatchError

        /** A list of maps is merged by `id`, but an entry has none (merging split documents of one skill). */
        data class MissingId(override val file: String, override val path: String, override val reason: String) : PatchError

        /** A patch op's `path` does not resolve in the composed tree (R35). [path] is the op's path as written. */
        data class UnresolvedPath(override val file: String, override val path: String, override val reason: String) : PatchError
    }
}
