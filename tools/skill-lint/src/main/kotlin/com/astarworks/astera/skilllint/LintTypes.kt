package com.astarworks.astera.skilllint

/** FAIL blocks (exit 1); WARN is printed only. */
enum class Severity { FAIL, WARN }

/** One finding. [path] is a model path such as `gameplay.on.hit[dmg]` (or `file#path` for compose errors). */
data class LintFinding(
    val rule: String,
    val severity: Severity,
    val skill: String,
    val path: String,
    val message: String,
)

data class LintReport(val skills: List<String>, val findings: List<LintFinding>) {
    val failures: List<LintFinding> get() = findings.filter { it.severity == Severity.FAIL }
    val warnings: List<LintFinding> get() = findings.filter { it.severity == Severity.WARN }
    val ok: Boolean get() = failures.isEmpty()

    fun render(): String = buildString {
        for (f in findings.sortedWith(compareBy({ it.skill }, { it.severity }, { it.rule }, { it.path }))) {
            appendLine("${f.severity} [${f.rule}] ${f.skill} ${f.path}: ${f.message}")
        }
        appendLine("skill-lint: ${skills.size} skills, ${failures.size} FAIL, ${warnings.size} WARN")
    }
}

/** Thresholds of the structural checks (design section 9: thresholds are constants). */
object LintLimits {
    /** Pose keys: at most 1 key per 2 ticks on average. */
    const val MIN_TICKS_PER_POSE_KEY: Double = 2.0
    const val MAX_DISPLAYS: Int = 60
    const val MAX_PARTICLES_PER_TICK: Int = 300

    /** A plate under this alpha does not count as making a hit volume visible. */
    const val MIN_VISIBLE_ALPHA: Double = 0.25
}
