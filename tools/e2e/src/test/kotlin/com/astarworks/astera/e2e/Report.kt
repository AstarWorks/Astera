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

object E2eReport {
    val results = CopyOnWriteArrayList<CheckResult>()
    val notes = CopyOnWriteArrayList<String>()
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
        sb.append("# Astera E2E report\n\n")
        sb.append("- generated: ${Instant.now()}\n")
        sb.append("- backend: ${E2eEnv.host}:${E2eEnv.port} (docker compose profile `e2e`, ASTERA_SERVER_ROLE=all, offline mode)\n")
        sb.append("- result: **$passed / ${results.size} checks passed**, ${results.size - passed} failed\n\n")
        sb.append("| Suite | Check | Channel | Result |\n|---|---|---|---|\n")
        for (r in results) {
            sb.append("| ${r.suite} | ${r.name} | ${r.channel} | ${if (r.passed) "PASS" else "**FAIL**"} |\n")
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
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(sb.toString())
        println("[e2e] report written to ${file.absolutePath}")
    }
}
