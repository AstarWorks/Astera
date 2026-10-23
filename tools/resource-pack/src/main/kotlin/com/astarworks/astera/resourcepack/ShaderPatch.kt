package com.astarworks.astera.resourcepack

/**
 * Our own additions to a vanilla core shader (ADR-0250), applied at pack build time. The patch file holds only
 * what Astera adds plus one-line anchors; Mojang's source is never committed.
 *
 * ```
 * # comment lines before the first directive are ignored
 * @@ before: <anchor>      insert the body before the anchor line
 * @@ after: <anchor>       insert the body after the anchor line
 * @@ replace: <anchor>     replace the anchor line with the body
 * ```
 *
 * An anchor is one whole vanilla line, compared trimmed, and must match exactly once; zero or several matches
 * fail the build (the shader changed upstream: re-base the patch). A body runs to the next `@@` line.
 */
internal object ShaderPatch {

    private enum class Mode { BEFORE, AFTER, REPLACE }

    private class Edit(val mode: Mode, val anchor: String, val body: MutableList<String> = mutableListOf())

    fun apply(name: String, vanilla: String, patch: String): String {
        val edits = parse(name, patch)
        check(edits.isNotEmpty()) { "shader patch $name has no @@ directive" }
        val lines = vanilla.split('\n')
        // line index -> edit; resolve every anchor against the pristine vanilla text first.
        val byLine = HashMap<Int, MutableList<Edit>>()
        for (edit in edits) {
            val hits = lines.indices.filter { lines[it].trim() == edit.anchor }
            check(hits.size == 1) {
                "shader patch $name: anchor '${edit.anchor}' matches ${hits.size} vanilla lines (need exactly 1); " +
                    "the vanilla shader changed, re-base content/shader-patches"
            }
            byLine.getOrPut(hits.single()) { mutableListOf() }.add(edit)
        }
        val out = ArrayList<String>(lines.size + 128)
        for ((i, line) in lines.withIndex()) {
            val here = byLine[i].orEmpty()
            check(here.count { it.mode == Mode.REPLACE } <= 1) { "shader patch $name: two replaces on '${line.trim()}'" }
            for (e in here.filter { it.mode == Mode.BEFORE }) out += e.body
            val replace = here.firstOrNull { it.mode == Mode.REPLACE }
            if (replace != null) out += replace.body else out += line
            for (e in here.filter { it.mode == Mode.AFTER }) out += e.body
        }
        return out.joinToString("\n")
    }

    private fun parse(name: String, patch: String): List<Edit> {
        val edits = mutableListOf<Edit>()
        var current: Edit? = null
        val raw = patch.removeSuffix("\n").split('\n')
        for (line in raw) {
            if (line.startsWith("@@ ")) {
                val m = DIRECTIVE.matchEntire(line)
                    ?: error("shader patch $name: bad directive '$line' (want '@@ before|after|replace: <anchor>')")
                current = Edit(Mode.valueOf(m.groupValues[1].uppercase()), m.groupValues[2].trim()).also { edits += it }
            } else {
                current?.body?.add(line)
            }
        }
        return edits
    }

    private val DIRECTIVE = Regex("""@@ (before|after|replace): (.+)""")
}
