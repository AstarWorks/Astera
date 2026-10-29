package com.astarworks.astera.itemart

import kotlin.math.*

/** Sandboxed arithmetic for procedural SDFs. Parsed once, never invokes a script engine or JVM code. */
internal object FieldExpression {
    val BUILT_INS = listOf("x", "y", "t", "i", "n", "size", "px", "py", "pi")
    private val bayer = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
    fun bayer(x: Int, y: Int): Double = (bayer[(y and 3) * 4 + (x and 3)] + 0.5) / 16
    fun segment(x: Double, y: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax; val dy = by - ay
        val u = (((x - ax) * dx + (y - ay) * dy) / max(1e-9, dx * dx + dy * dy)).coerceIn(0.0, 1.0)
        return hypot(x - ax - dx * u, y - ay - dy * u)
    }
    fun compile(source: String, symbols: List<String>): (DoubleArray) -> Double = Parser(source, symbols).parse()

    private class Parser(val source: String, val symbols: List<String>) {
        val tokens = Regex("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?|[A-Za-z_][A-Za-z_0-9]*|<=|>=|==|!=|&&|\\|\\||[+*/%^(),<>!-]")
            .findAll(source).toList().also { matches ->
                var end = 0
                for (m in matches) { require(source.substring(end, m.range.first).isBlank()) { "invalid expression: $source" }; end = m.range.last + 1 }
                require(source.substring(end).isBlank()) { "invalid expression: $source" }
            }.map { it.value }
        var pos = 0
        fun peek() = tokens.getOrNull(pos)
        fun take(): String = tokens.getOrNull(pos++) ?: error("unexpected end of expression: $source")
        fun expect(s: String) { require(take() == s) { "expected '$s': $source" } }
        fun parse(): (DoubleArray) -> Double { val out = expr(0); require(pos == tokens.size) { "extra tokens: $source" }; return out }
        fun expr(level: Int): (DoubleArray) -> Double {
            var left = atom()
            while (true) {
                val op = peek() ?: break
                val prec = when (op) { "||" -> 1; "&&" -> 2; "==", "!=", "<", ">", "<=", ">=" -> 3; "+", "-" -> 4; "*", "/", "%" -> 5; "^" -> 6; else -> -1 }
                if (prec < level) break
                take()
                val a = left; val b = expr(prec + if (op == "^") 0 else 1)
                left = { v -> when (op) {
                    "+" -> a(v) + b(v); "-" -> a(v) - b(v); "*" -> a(v) * b(v); "/" -> a(v) / b(v); "%" -> a(v) % b(v); "^" -> a(v).pow(b(v))
                    "<" -> bit(a(v) < b(v)); ">" -> bit(a(v) > b(v)); "<=" -> bit(a(v) <= b(v)); ">=" -> bit(a(v) >= b(v)); "==" -> bit(a(v) == b(v)); "!=" -> bit(a(v) != b(v))
                    "&&" -> bit(a(v) != 0.0 && b(v) != 0.0); else -> bit(a(v) != 0.0 || b(v) != 0.0)
                } }
            }
            return left
        }
        fun atom(): (DoubleArray) -> Double {
            val token = take()
            if (token == "-") { val a = expr(6); return { -a(it) } }
            if (token == "+") return expr(6)
            if (token == "!") { val a = expr(6); return { bit(a(it) == 0.0) } }
            if (token == "(") { val a = expr(0); expect(")"); return a }
            token.toDoubleOrNull()?.let { n -> return { n } }
            if (peek() != "(") {
                val index = symbols.indexOf(token)
                require(index >= 0) { "unknown variable '$token': $source" }
                return { it[index] }
            }
            take()
            val args = mutableListOf<(DoubleArray) -> Double>()
            if (peek() != ")") { args += expr(0); while (peek() == ",") { take(); args += expr(0) } }
            expect(")")
            val arity = when (token) {
                "sin", "cos", "tan", "abs", "sqrt", "floor", "ceil", "exp", "log", "ease" -> 1
                "pow", "hypot", "atan2", "bayer", "step" -> 2
                "if", "clamp", "lerp" -> 3
                "star" -> 4
                "seg" -> 6
                "min", "max" -> -1
                else -> error("unknown function '$token': $source")
            }
            require(if (arity < 0) args.isNotEmpty() else args.size == arity) { "wrong argument count for $token: $source" }
            if (token == "if") return { v -> if (args[0](v) != 0.0) args[1](v) else args[2](v) }
            return { v ->
                val a = args.map { it(v) }
                when (token) {
                    "sin" -> sin(a[0]); "cos" -> cos(a[0]); "tan" -> tan(a[0]); "abs" -> abs(a[0]); "sqrt" -> sqrt(a[0]); "floor" -> floor(a[0]); "ceil" -> ceil(a[0]); "exp" -> exp(a[0]); "log" -> ln(a[0])
                    "ease" -> 1 - (1 - a[0].coerceIn(0.0, 1.0)).pow(3)
                    "pow" -> a[0].pow(a[1]); "hypot" -> hypot(a[0], a[1]); "atan2" -> atan2(a[0], a[1]); "bayer" -> bayer(a[0].toInt(), a[1].toInt()); "step" -> bit(a[1] >= a[0])
                    "min" -> a.min(); "max" -> a.max(); "clamp" -> a[0].coerceIn(a[1], a[2]); "lerp" -> a[0] + (a[1] - a[0]) * a[2].coerceIn(0.0, 1.0)
                    "seg" -> segment(a[0], a[1], a[2], a[3], a[4], a[5])
                    "star" -> if (a[2] <= 0) 1.0 else ((abs(a[0]) / a[2]).pow(a[3]) + (abs(a[1]) / a[2]).pow(a[3])).pow(1 / a[3])
                    else -> error(token)
                }
            }
        }
        fun bit(b: Boolean) = if (b) 1.0 else 0.0
    }
}
