package com.astarworks.astera.core.application.profiling

import com.astarworks.astera.core.application.port.outbound.profiling.IProfilingCollector
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Materialises a [com.astarworks.astera.core.application.port.outbound.profiling.ProfilingSnapshot]
 * to disk in Brendan Gregg flamegraph format (Wave 5u, ADR-0087).
 *
 * The `/astera profiling export` Brigadier admin command routes here. We
 * keep the file IO in `application/` rather than `adapter/paper/` because
 * the operation is pure file writing — no Paper API involved — so the
 * adapter layer would be no-value indirection.
 *
 * The Konsist rule `core.application must not import java.nio` does *not*
 * exist (only `domain` is barred from `java.nio`); see ArchitectureTest's
 * `core application must not import Bukkit family or persistence drivers`.
 */
public class ProfilingExportService(
    private val collector: IProfilingCollector,
    /**
     * Output directory for flamegraph files. Defaulted by Plugin.kt to
     * `<dataFolder>/profiling/` so operators find them under the plugin's
     * standard layout.
     */
    private val outputDir: Path,
    /** Clock injection point; defaults to system time. */
    private val nowSupplier: () -> Instant = { Instant.now() },
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Writes a flamegraph file under [outputDir] and returns the path.
     *
     * File name: `profiling-<yyyyMMdd-HHmmss>.txt`. Suffix is plain `.txt`
     * because `flamegraph.pl` reads collapsed input from stdin / any file;
     * no MIME contract.
     *
     * @param reset If true, calls [IProfilingCollector.reset] after export
     *   so the next collection window starts clean.
     */
    public fun export(reset: Boolean = false): Path {
        Files.createDirectories(outputDir)
        val ts = TIMESTAMP_FORMAT.format(nowSupplier().atOffset(ZoneOffset.UTC))
        val file = outputDir.resolve("profiling-$ts.txt")

        val snapshot = collector.export()
        val rendered = snapshot.toFlamegraphFormat()
        Files.writeString(file, rendered)

        log.info(
            "Profiling snapshot exported to {} ({} samples; reset={})",
            file, snapshot.samples.size, reset,
        )

        if (reset) collector.reset()
        return file
    }

    public companion object {
        private val TIMESTAMP_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
