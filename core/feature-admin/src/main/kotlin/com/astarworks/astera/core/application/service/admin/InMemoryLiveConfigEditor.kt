package com.astarworks.astera.core.application.service.admin

import com.astarworks.astera.core.application.port.outbound.IReloadableContentsRegistry
import com.astarworks.astera.core.application.port.outbound.ReloadError
import com.astarworks.astera.core.application.port.outbound.admin.ILiveConfigEditor
import com.astarworks.astera.core.application.port.outbound.admin.LiveConfigEditError
import com.astarworks.astera.core.application.service.ContentLoader
import com.astarworks.astera.core.domain.Result
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Default [ILiveConfigEditor] implementation backed by the existing
 * [IReloadableContentsRegistry] (ADR-0065).
 *
 * The editor maintains a parallel mapping of `categoryName → categoryRoot`
 * supplied at registration time (the registry itself stores the dir, but
 * keeps it private — we mirror it via [bindCategoryRoot] so the editor can
 * validate that an edit stays within the bound directory).
 *
 * Path-escape rule: [Path.normalize] + `startsWith` check against the
 * category root. Symlinks are not resolved — Phase 1 stays simple; the admin
 * who set up the symlink owns the consequence.
 */
public class InMemoryLiveConfigEditor(
    private val reloadRegistry: IReloadableContentsRegistry,
) : ILiveConfigEditor {

    private val categoryRoots: MutableMap<String, Path> = ConcurrentHashMap()

    /**
     * Bind a category's resolved on-disk root. Call this once per category
     * (typically right after `reloadRegistry.register(...)`) so the editor
     * can do path-escape validation.
     */
    public fun bindCategoryRoot(categoryName: String, root: Path) {
        categoryRoots[categoryName] = root.toAbsolutePath().normalize()
    }

    override fun edit(
        categoryName: String,
        path: Path,
        newContent: String,
    ): Result<ContentLoader.LoadReport, LiveConfigEditError> {
        val root = categoryRoots[categoryName]
            ?: return Result.failure(LiveConfigEditError.UnknownCategory(categoryName))

        val absolute = path.toAbsolutePath().normalize()
        if (!absolute.startsWith(root)) {
            return Result.failure(LiveConfigEditError.PathEscaped(absolute, root))
        }

        return try {
            val tmp = absolute.resolveSibling("${absolute.fileName}.tmp")
            Files.writeString(
                tmp,
                newContent,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            Files.move(
                tmp,
                absolute,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )

            when (val outcome = reloadRegistry.reload(categoryName)) {
                is Result.Success -> Result.success(outcome.value)
                is Result.Failure -> when (val err = outcome.error) {
                    is ReloadError.UnknownCategory ->
                        Result.failure(LiveConfigEditError.UnknownCategory(err.name))
                    is ReloadError.LoadFailed ->
                        Result.failure(LiveConfigEditError.ReloadAborted(err.categoryName, err.report))
                }
            }
        } catch (t: java.io.IOException) {
            Result.failure(LiveConfigEditError.IoFailed(t.message ?: t.javaClass.simpleName))
        }
    }

    override fun read(
        categoryName: String,
        path: Path,
    ): Result<String, LiveConfigEditError> {
        val root = categoryRoots[categoryName]
            ?: return Result.failure(LiveConfigEditError.UnknownCategory(categoryName))

        val absolute = path.toAbsolutePath().normalize()
        if (!absolute.startsWith(root)) {
            return Result.failure(LiveConfigEditError.PathEscaped(absolute, root))
        }

        return try {
            Result.success(Files.readString(absolute))
        } catch (t: java.io.IOException) {
            Result.failure(LiveConfigEditError.IoFailed(t.message ?: t.javaClass.simpleName))
        }
    }
}
