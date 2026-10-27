package com.astarworks.astera.e2e

import org.junit.platform.launcher.LauncherSession
import org.junit.platform.launcher.LauncherSessionListener
import java.io.File
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** One named assertion with the evidence a human (or the orchestrator) needs to judge it. */
data class CheckResult(
    val suite: String,
    val name: String,
    /** The player-visible channel this check is attributable to (packet / command / GUI). */
    val channel: String,
    val passed: Boolean,
    val evidence: String,
)

/**
 * Which track (`.claude/rules/tracks.md`) to drop from the integration batch when a suite fails. A suite not listed here
 * shows as `unmapped` in the report: add it when a new `*E2eTest` is written.
 */
object SuiteOwners {
    private val owners: Map<String, String> = buildMap {
        fun own(track: String, vararg suites: String) = suites.forEach { put(it, track) }
        own("ui-access", "Access map", "GUI", "Lobby kit & HUD", "Play entry", "Match menu", "First 10 minutes")
        own(
            "modes-combat",
            "Annihilation playthrough", "Capture Wool playthrough", "Carry TNT playthrough",
            "Carry Mini TNT playthrough", "Steal Jewelry playthrough", "Norma playthrough", "PvE waves playthrough", "Match", "Game loop", "Combat", "Stars", "WeaponLimit",
            "Mid-match leave", "Mid-match reconnect",
        )
        own("economy-life", "Shop purchase")
        own("presentation", "ClientReceives", "HUD")
        own("skills-vfx", "Weapons", "PersonaWeapons", "SkinRig")
        own("bots-qa", "BootHealth", "Bot vs bot", "Commands", "Join", "Velocity", "CueGuard", "Live match", "Live smoke", "Live weapons", "Live chat")
    }

    fun of(suite: String): String = owners[suite] ?: "unmapped"
}

object E2eReport {
    val results = CopyOnWriteArrayList<CheckResult>()
    val notes = CopyOnWriteArrayList<String>()
    /** Pre-rendered markdown sections (title -> table body), appended after the evidence. */
    val tables = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val failuresThisTest = ThreadLocal.withInitial { mutableListOf<String>() }

    /**
     * Soft check: runs [body] (which throws AssertionError / any exception on failure and returns the
     * evidence string on success), records the outcome and keeps going so one broken channel does not
     * hide the others. The surrounding test fails at the end via [failIfAny].
     */
    fun check(suite: String, name: String, channel: String, body: () -> String) {
        val result = try {
            CheckResult(suite, name, channel, true, body())
        } catch (t: Throwable) {
            failuresThisTest.get() += "$suite / $name"
            CheckResult(suite, name, channel, false, (t.message ?: t.javaClass.simpleName).take(1_500))
        }
        results += result
        println("[e2e] ${if (result.passed) "PASS" else "FAIL"} $suite / $name :: ${result.evidence.lineSequence().first().take(200)}")
    }

    fun failIfAny() {
        val failed = failuresThisTest.get().toList()
        failuresThisTest.get().clear()
        if (failed.isNotEmpty()) throw AssertionError("${failed.size} e2e check(s) failed: ${failed.joinToString("; ")}")
    }
}

/** Writes build/e2e-report.md when the Gradle test run ends (registered through ServiceLoader). */
class ReportWriter : LauncherSessionListener {
    override fun launcherSessionClosed(session: LauncherSession) {
        val path = System.getProperty("astera.e2e.report") ?: return
        val results = E2eReport.results.toList()
        val sb = StringBuilder()
        val passed = results.count { it.passed }
        sb.append("# ${System.getProperty("astera.e2e.reportTitle", "Astera E2E report")}\n\n")
        sb.append("- generated: ${Instant.now()}\n")
        sb.append("- backend: ${System.getProperty("astera.e2e.backendDesc") ?: "${E2eEnv.host}:${E2eEnv.port} (docker compose profile `e2e`, ASTERA_SERVER_ROLE=all, offline mode)"}\n")
        sb.append("- result: **$passed / ${results.size} checks passed**, ${results.size - passed} failed\n\n")
        val failedByTrack = results.filterNot { it.passed }.groupBy { SuiteOwners.of(it.suite) }.toSortedMap()
        if (failedByTrack.isNotEmpty()) {
            sb.append("Failures by owning track (drop the track from the batch, do not revert):\n\n")
            failedByTrack.forEach { (track, fails) ->
                sb.append("- **$track**: ${fails.size} (${fails.map { it.suite }.distinct().joinToString(", ")})\n")
            }
            sb.append("\n")
        }
        sb.append("| Suite | Owner | Check | Channel | Result |\n|---|---|---|---|---|\n")
        for (r in results) {
            sb.append("| ${r.suite} | ${SuiteOwners.of(r.suite)} | ${r.name} | ${r.channel} | ${if (r.passed) "PASS" else "**FAIL**"} |\n")
        }
        sb.append("\n## Evidence\n")
        var lastSuite = ""
        for (r in results) {
            if (r.suite != lastSuite) {
                sb.append("\n### ${r.suite}\n")
                lastSuite = r.suite
            }
            sb.append("\n**${if (r.passed) "PASS" else "FAIL"}** ${r.name} (`${r.channel}`)\n\n")
            sb.append("```\n${r.evidence.replace("```", "'''")}\n```\n")
        }
        if (E2eReport.notes.isNotEmpty()) {
            sb.append("\n## Notes\n\n")
            E2eReport.notes.forEach { sb.append("- $it\n") }
        }
        E2eReport.tables.toSortedMap().forEach { (title, body) -> sb.append("\n## $title\n\n$body\n") }
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(sb.toString())
        println("[e2e] report written to ${file.absolutePath}")
    }
}
