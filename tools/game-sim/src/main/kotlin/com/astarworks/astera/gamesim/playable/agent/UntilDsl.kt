package com.astarworks.astera.gamesim.playable.agent

/** What an `until` expression can look at; the AgentPort implements it over the live match. */
interface UntilProbe {
    fun phase(): String
    fun hp(): Double
    fun nearestEnemy(): Double
    fun stars(): Int
    fun ore(): Int
    fun norma(): Int
    fun tick(): Long
    fun arrived(): Boolean
    fun died(): Boolean
}

/**
 * The `skip` / `act` stop condition: atoms joined by `|` (or). Atoms: `phase=<build|engage|center|escalation|sudden-death>`,`hp<N`, `hp<=N`, `enemy<=D`, `stars>=N`,
 * `ore>=N`, `norma>=N`, `tick>=N`, `arrived`, `died`. Parse errors are reported to the caller, never silently ignored.
 */
class UntilDsl private constructor(private val atoms: List<Atom>, val source: String) {

    private class Atom(val text: String, val test: (UntilProbe) -> Boolean)

    /** The first atom that holds now, or null. */
    fun fired(probe: UntilProbe): String? = atoms.firstOrNull { it.test(probe) }?.text

    companion object {
        private val PHASES = listOf("build", "engage", "center", "escalation", "sudden-death")
        private val CMP = Regex("""^(phase|hp|enemy|stars|ore|norma|tick)(<=|>=|<|>|=)(.+)$""")

        fun parse(source: String): Result<UntilDsl> = runCatching {
            val atoms = source.split('|').map { it.trim() }.filter { it.isNotEmpty() }.map(::atom)
            require(atoms.isNotEmpty()) { "empty until expression" }
            UntilDsl(atoms, source)
        }

        private fun atom(text: String): Atom {
            when (text) {
                "arrived" -> return Atom(text) { it.arrived() }
                "died" -> return Atom(text) { it.died() }
            }
            val m = CMP.matchEntire(text.replace(" ", "")) ?: error("unknown until atom '$text' (phase=, hp<, enemy<=, stars>=, ore>=, norma>=, tick>=, arrived, died)")
            val key = m.groupValues[1]
            val op = m.groupValues[2]
            val rhs = m.groupValues[3]
            if (key == "phase") {
                require(op == "=") { "phase only supports '=' ($text)" }
                require(rhs in PHASES) { "unknown phase '$rhs' (${PHASES.joinToString("|")})" }
                return Atom(text) { it.phase() == rhs }
            }
            val n = rhs.toDoubleOrNull() ?: error("number expected in '$text'")
            val read: (UntilProbe) -> Double = when (key) {
                "hp" -> { p -> p.hp() }
                "enemy" -> { p -> p.nearestEnemy() }
                "stars" -> { p -> p.stars().toDouble() }
                "ore" -> { p -> p.ore().toDouble() }
                "norma" -> { p -> p.norma().toDouble() }
                else -> { p -> p.tick().toDouble() }
            }
            val cmp: (Double, Double) -> Boolean = when (op) {
                "<" -> { a, b -> a < b }
                "<=" -> { a, b -> a <= b }
                ">" -> { a, b -> a > b }
                ">=" -> { a, b -> a >= b }
                else -> { a, b -> a == b }
            }
            return Atom(text) { cmp(read(it), n) }
        }
    }
}
