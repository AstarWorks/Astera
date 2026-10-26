package com.astarworks.astera.core.application.contents

import com.astarworks.astera.core.application.service.ContentLoader
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Shared guard for every [ReloadableContents.reload]: a reload that yields 0 entries over a populated
 * store is a loader/layout mismatch (the sound-cue reload bug), never an intent, so the caller keeps
 * the old store and returns the failure report from [check].
 */
public object EmptyReloadGuard {
    private val log = LoggerFactory.getLogger(EmptyReloadGuard::class.java)

    /** Null when the swap may proceed; otherwise a logged (`[content-reload]` WARN) failure report. */
    public fun check(categoryName: String, dir: Path, newSize: Int, oldSize: Int): ContentLoader.LoadReport? {
        if (newSize != 0 || oldSize == 0) return null
        log.warn(
            "[content-reload] {}: reload of {} produced 0 entries but the registry holds {}; keeping the old store",
            categoryName, dir, oldSize,
        )
        return ContentLoader.LoadReport(
            loaded = 0,
            failed = 1,
            failures = listOf(ContentLoader.LoadFailure(dir, "reload produced no entries; keeping the previous $oldSize")),
        )
    }
}
