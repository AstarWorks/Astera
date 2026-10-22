package com.astarworks.astera.core.application.contents

import com.astarworks.astera.core.application.i18n.LanguageLoader
import com.astarworks.astera.core.application.i18n.SimpleMessageRenderer
import com.astarworks.astera.core.application.service.ContentLoader
import java.nio.file.Path

/**
 * `content/languages/<locale>.yaml` as a reload category: `/astera admin reload languages`
 * re-reads every locale file and swaps the renderer's immutable key table in one step, so a
 * new i18n key applies without a restart. An empty result (missing directory, every file
 * unparsable) is reported as a failure and the previous table stays in place.
 */
public class LanguageContents(
    private val renderer: SimpleMessageRenderer,
) : ReloadableContents {

    override val categoryName: String = "languages"

    override fun reload(dir: Path): ContentLoader.LoadReport {
        val loaded = LanguageLoader.loadFrom(dir)
        if (loaded.isEmpty()) {
            return ContentLoader.LoadReport(
                loaded = 0,
                failed = 1,
                failures = listOf(ContentLoader.LoadFailure(dir, "no language file could be loaded; keeping the previous table")),
            )
        }
        renderer.replaceLanguages(loaded)
        return ContentLoader.LoadReport(loaded = loaded.size, failed = 0)
    }
}
