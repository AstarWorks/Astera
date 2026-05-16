package com.astarworks.astera.application.service

import java.nio.file.Path

/**
 * Pre-processes content text to substitute `"@ref:<path>"` strings with the
 * serialized contents of the referenced file (ADR-0016 §"Part 4").
 *
 * # Trade-off
 *
 * The "real" implementation has to operate at the text level *before*
 * deserialization, because the host parser (YAML / TOML / JSON) does not yet
 * know that a `@ref:` string is special. There are two implementation
 * postures:
 *
 * 1. **Whole-node substitution** (simple): replace `"@ref:path"` only when it
 *    appears as an *entire* value position. Read the referenced file, parse
 *    it into a generic intermediate, then re-serialize inline using the host
 *    format. Limitation: cannot substitute inside a larger string literal,
 *    cannot work inside a TOML inline-table value without a TOML emitter, etc.
 * 2. **Full intermediate-model substitution** (rich): parse the host text into
 *    a generic tree (e.g. `JsonElement` / `YamlNode`), recursively replace
 *    `@ref:` leaves, re-serialize. More correct, more code, three-format care.
 *
 * Posture 2 is the eventual Phase 4 implementation when the UGC marketplace
 * needs sharable effects / particles / sounds across thousands of weapons.
 *
 * # This release
 *
 * Phase 2 mid ships only the **disabled** variant — see [disabled]. The API
 * surface (a `resolve(text, fileDir)` function and a `ContentLoader` default
 * parameter that wires it up) is in place so Phase 4 can swap in the real
 * implementation without touching call sites or tests.
 *
 * @see disabled
 */
public class ContentRefResolver private constructor(
    private val impl: (text: String, fileDir: Path) -> String,
) {
    /**
     * Resolves any `@ref:` references in [text]. [fileDir] is the directory
     * of the file [text] was read from — used as the resolution base for
     * relative ref paths.
     *
     * The disabled resolver (default) returns [text] unchanged.
     */
    public fun resolve(text: String, fileDir: Path): String = impl(text, fileDir)

    public companion object {
        /**
         * The no-op resolver: returns the input text unchanged.
         *
         * Phase 2 mid ships this as the default for `ContentLoader`. Phase 4
         * will replace this with the real cross-file ref resolver as part of
         * the UGC marketplace work (see ADR-0016 §"Part 4").
         */
        public fun disabled(): ContentRefResolver = ContentRefResolver { text, _ -> text }
    }
}

// TODO(phase-4): implement the real ContentRefResolver per ADR-0016 §"Part 4".
//   Parse host-format text into a generic tree, recursively substitute any
//   "@ref:<path>" leaves with the parsed contents of the referenced file
//   (via the same IContentParser dispatch ContentLoader uses), then
//   re-serialize. Add ContentRefResolverTest covering cycle detection,
//   missing-file behaviour, and nested @ref chains.
