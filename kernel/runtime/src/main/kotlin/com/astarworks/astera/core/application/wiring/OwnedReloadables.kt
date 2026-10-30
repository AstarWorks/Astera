package com.astarworks.astera.core.application.wiring

import com.astarworks.astera.core.application.contents.ReloadableContents
import com.astarworks.astera.core.application.port.outbound.IReloadableContentsRegistry
import com.astarworks.astera.core.application.port.outbound.ReloadError
import com.astarworks.astera.core.application.service.ContentLoader
import com.astarworks.astera.core.domain.Result
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * The reloadable-content registry as one wiring generation sees it (ADR-0311): records every category
 * bound under [tag], so [unregisterAll] detaches exactly what this generation bound.
 */
public class OwnedReloadables(
    private val inner: IReloadableContentsRegistry,
    public val tag: WiringTag,
) : IReloadableContentsRegistry {

    private val live: MutableSet<IReloadableContentsRegistry.Registration> = ConcurrentHashMap.newKeySet()

    public val activeCount: Int get() = live.size

    override fun register(contents: ReloadableContents, dir: Path): IReloadableContentsRegistry.Registration {
        val raw = inner.register(contents, dir)
        val tracked = object : IReloadableContentsRegistry.Registration {
            override fun unregister() {
                raw.unregister()
                live.remove(this)
            }
        }
        live += tracked
        return tracked
    }

    override fun categories(): Set<String> = inner.categories()

    override fun reload(categoryName: String): Result<ContentLoader.LoadReport, ReloadError> = inner.reload(categoryName)

    public fun unregisterAll() {
        live.toList().forEach { it.unregister() }
    }
}
