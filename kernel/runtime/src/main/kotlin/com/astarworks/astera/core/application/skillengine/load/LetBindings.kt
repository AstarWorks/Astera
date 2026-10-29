package com.astarworks.astera.core.application.skillengine.load

import com.astarworks.astera.core.application.skillengine.expr.cel.CelFunctions
import com.astarworks.astera.core.application.skillengine.expr.cel.ExprClassifier

/** A `let:` problem: [path] is relative to the document, the caller prefixes the file. */
internal class LetError(val path: String, override val message: String) : Exception(message)

/**
 * `let:` local bindings (ADR-0303 rev4.10, C2). A map of the document tree (the root, a part, a state, an op) may carry
 * `let: {name: "= expr" | literal | ${param}}`; the pass removes it and replaces every use of `name` in the `= ` strings
 * below that map by the bound text in parentheses. Expressions are pure, so textual substitution is exact. It runs on
 * the raw tree before any decoder, so lets have no runtime existence and the expansion dump shows the substituted text.
 *
 * A `let` may read earlier entries of its own map (declaration order) and any outer map; it must not shadow a param,
 * a builtin, a function, an id / anchor / frame name or an outer `let`. A timing field whose substituted expression
 * would be decided at run time is reported against the let that caused it (C5).
 */
internal object LetBindings {
    private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val PARAM_REF = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)}""")
    private val TIMING_KEYS = setOf("at", "for", "every", "times", "lasts", "after")
    private val SKIPPED = setOf("params", "meta")

    private val BUILTINS: Set<String> = setOf(
        "caster", "target", "victim", "contact", "world", "enemy", "event", "exit", "parent", "cast", "state",
        "t", "lt", "i", "n", "PI", "pi", "true", "false", "null", "in",
    )

    private class Bound(val text: String, val source: String)

    @Suppress("UNCHECKED_CAST")
    fun resolve(tree: Map<String, Any?>): Map<String, Any?> {
        if (!hasLet(tree)) return tree
        val reserved = HashSet<String>()
        (tree["params"] as? Map<*, *>)?.keys?.forEach { reserved += it.toString() }
        (tree["frames_in"] as? Map<*, *>)?.keys?.forEach { reserved += it.toString() }
        collectIds(tree, reserved)
        return walk(tree, "", emptyMap(), reserved) as Map<String, Any?>
    }

    private fun hasLet(v: Any?): Boolean = when (v) {
        is Map<*, *> -> "let" in v || v.values.any { hasLet(it) }
        is List<*> -> v.any { hasLet(it) }
        else -> false
    }

    private fun collectIds(v: Any?, out: MutableSet<String>) {
        when (v) {
            is Map<*, *> -> {
                (v["id"] as? String)?.let { out += it }
                v.values.forEach { collectIds(it, out) }
            }
            is List<*> -> v.forEach { collectIds(it, out) }
        }
    }

    private fun walk(v: Any?, path: String, scope: Map<String, Bound>, reserved: Set<String>): Any? = when (v) {
        is Map<*, *> -> walkMap(v, path, scope, reserved)
        is List<*> -> v.mapIndexed { i, x -> walk(x, "$path[$i]", scope, reserved) }
        is String -> if (v.startsWith("= ")) substitute(v, scope, null) else v
        else -> v
    }

    private fun walkMap(m: Map<*, *>, path: String, outer: Map<String, Bound>, reserved: Set<String>): Map<String, Any?> {
        var scope = outer
        val letRaw = m["let"]
        if (letRaw != null) {
            val letPath = if (path.isEmpty()) "let" else "$path.let"
            val entries = letRaw as? Map<*, *> ?: throw LetError(letPath, "let must be a map of name: value, was ${letRaw::class.simpleName}")
            val next = LinkedHashMap(outer)
            for ((k, raw) in entries) {
                val name = k.toString()
                val p = "$letPath.$name"
                if (!NAME.matches(name)) throw LetError(p, "let name '$name' must be a plain identifier ([A-Za-z_][A-Za-z0-9_]*)")
                when {
                    name == "i" -> throw LetError(p, "'i' is a builtin copy index and cannot be rebound")
                    name == "n" -> throw LetError(p, "'n' is a builtin copy count and cannot be rebound")
                    name in BUILTINS -> throw LetError(p, "'$name' is a builtin and cannot be rebound by a let")
                    name in CelFunctions.functionNames() -> throw LetError(p, "'$name' is an expression function and cannot be rebound by a let")
                    name in reserved -> throw LetError(p, "'$name' is already a param, id, anchor or frame of this document and cannot be rebound by a let")
                    name in next -> throw LetError(p, "'$name' is already bound by an outer or earlier let")
                }
                next[name] = bind(name, raw, p, next)
            }
            scope = next
        }
        val out = LinkedHashMap<String, Any?>(m.size)
        for ((k, x) in m) {
            val key = k.toString()
            if (key == "let") continue
            val p = if (path.isEmpty()) key else "$path.$key"
            out[key] = when {
                key in SKIPPED -> x
                x is String && x.startsWith("= ") -> substitute(x, scope, if (key in TIMING_KEYS) Timing(m, key, p) else null)
                else -> walk(x, p, scope, reserved)
            }
        }
        return out
    }

    private fun bind(name: String, raw: Any?, path: String, scope: Map<String, Bound>): Bound = when {
        raw is Boolean || raw is Number -> Bound(raw.toString(), raw.toString())
        raw is String && PARAM_REF.matchEntire(raw) != null -> PARAM_REF.matchEntire(raw)!!.groupValues[1].let { Bound(it, raw) }
        raw is String && raw.startsWith("= ") -> {
            val body = substitute(raw, scope, null).removePrefix("= ").trim()
            if (body.isEmpty()) throw LetError(path, "let '$name' has an empty expression")
            Bound("($body)", raw)
        }
        else -> throw LetError(path, "let '$name' must be a number, a bool, a \${param} or an '= expr' string, was '$raw'")
    }

    private class Timing(val op: Map<*, *>, val key: String, val path: String)

    /** Token-wise: identifier boundaries, never after a `.`, never before `(`, never inside a quoted string or `${..}`. */
    private fun substitute(source: String, scope: Map<String, Bound>, timing: Timing?): String {
        if (scope.isEmpty()) return source
        val sb = StringBuilder(source.length + 16)
        var i = 0
        while (i < source.length) {
            val c = source[i]
            if (c == '\'' || c == '"') {
                var j = i + 1
                while (j < source.length && source[j] != c) j += if (source[j] == '\\') 2 else 1
                val end = minOf(j + 1, source.length)
                sb.append(source, i, end)
                i = end
            } else if (c == '$' && i + 1 < source.length && source[i + 1] == '{') {
                val end = source.indexOf('}', i).let { if (it < 0) source.length else it + 1 }
                sb.append(source, i, end)
                i = end
            } else if (c.isLetter() || c == '_') {
                var j = i + 1
                while (j < source.length && (source[j].isLetterOrDigit() || source[j] == '_')) j++
                val word = source.substring(i, j)
                var k = j
                while (k < source.length && source[k] == ' ') k++
                val afterDot = i > 0 && source[i - 1] == '.'
                val isCall = k < source.length && source[k] == '('
                val bound = if (afterDot || isCall) null else scope[word]
                if (bound != null) {
                    if (timing != null) requireStatic(word, bound, timing, source)
                    sb.append(bound.text)
                } else {
                    sb.append(word)
                }
                i = j
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun requireStatic(name: String, bound: Bound, timing: Timing, usedIn: String) {
        val a = ExprClassifier.analyse(bound.text)
        val why = when {
            a.usesLive -> "only known at run time"
            a.usesRuntimePosition -> "only known at run time"
            a.usesRand -> "random, so it is decided at run time"
            a.usesTime -> "a function of time, so it is decided at run time"
            else -> return
        }
        val opName = timing.op["op"] as? String ?: "op"
        val opId = timing.op["id"] as? String
        val subject = if (opId != null) "$opName '$opId'" else opName
        val shown = bound.source.removePrefix("= ").trim().let { if (bound.source.startsWith("= ")) "($it)" else it }
        throw LetError(
            timing.path,
            "$subject ${timing.key}: '$usedIn' uses let '$name' = $shown, which is $why (C5)",
        )
    }
}
