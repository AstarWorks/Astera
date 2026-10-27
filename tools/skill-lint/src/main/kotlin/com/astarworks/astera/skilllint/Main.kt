package com.astarworks.astera.skilllint

import com.astarworks.astera.core.application.skillengine.compose.ComposeOptions
import com.astarworks.astera.core.application.skillengine.load.SkillEngineRegistries
import com.astarworks.astera.core.application.skillengine.load.SkillDecoder
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * The one place that names the production decoder. The lint rules themselves only know [SkillDecoder].
 * Every op family is registered; the frame point tables are the compiler's concern, not the decoder's.
 */
object ProductionDecoder {
    fun create(): SkillDecoder = SkillEngineRegistries.decoder()
}

/**
 * `./gradlew :tools:skill-lint:run --args="[--root content/skills] [--balance]"`.
 * Exit 0 when there is no FAIL, 1 otherwise. WARNs are printed only.
 */
fun main(args: Array<String>) {
    var root = Path.of("content/skills")
    var balance = false
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--root" -> root = Path.of(args[++i])
            "--balance" -> balance = true
            else -> {
                System.err.println("usage: skill-lint [--root <dir>] [--balance]")
                exitProcess(2)
            }
        }
        i++
    }
    val report = SkillLinter(ProductionDecoder.create()).lint(root, ComposeOptions(allowBalancePatches = balance))
    print(report.render())
    exitProcess(if (report.ok) 0 else 1)
}
