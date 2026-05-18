package com.astarworks.astera.core.application.contents

import com.astarworks.astera.core.application.service.ContentLoader
import java.nio.file.Path

/**
 * Cross-content-type abstraction for the `/astera-<plugin> reload` command
 * family (Wave _infra-2a) and the future `/astera reload <category>` Core-side
 * dispatcher (Wave _infra-2b).
 *
 * Any registry that owns an atomic-swap store and a YAML directory can
 * participate. The contract is intentionally minimal:
 *
 *   - [categoryName] is the human-facing identifier (`"weapons"`, `"skills"`,
 *     …) used by command dispatch and log lines.
 *   - [reload] is **all-or-nothing**: if any file in [dir] fails to parse,
 *     the previous state stays intact and the report's `failed > 0` so the
 *     command can surface the error count to the operator. Successful reload
 *     atomically replaces every entry.
 *
 * Bootstrap (`onEnable`) uses [YamlDynamicContents.bootstrap] instead — that
 * one is best-effort because admins seeing "1 of 3 weapons failed at boot"
 * still want the 2 that succeeded.
 */
public interface ReloadableContents {
    /**
     * Discriminator for command dispatch (and future cross-plugin reload
     * registry in Wave _infra-2b). Convention: short lowercase noun, no
     * pluralization mismatch with the directory name under `content/`.
     */
    public val categoryName: String

    /**
     * Atomically swap the registry to whatever loads from [dir]. On any
     * file-level parse failure (`report.failed > 0`) the previous state is
     * **preserved unchanged** and the report is returned so the caller can
     * surface the error to the operator. Successful reload (`report.failed
     * == 0`) replaces every entry.
     */
    public fun reload(dir: Path): ContentLoader.LoadReport
}
