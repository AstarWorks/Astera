package com.astarworks.astera.application.service

import com.astarworks.astera.application.config.ElementMatrixYamlConfig
import com.astarworks.astera.application.port.outbound.IContentParser
import com.astarworks.astera.domain.model.weapon.DamageAttribute
import com.astarworks.astera.domain.rule.IElementMatrix
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/**
 * In-memory [IElementMatrix] backed by `content/balance/element-matrix.yaml`.
 *
 * Missing matchups default to `1.0` so authors only have to fill the
 * non-neutral cells of the matrix.
 *
 * Phase 2 mid: single-file. Phase 4 may layer multiple balance files for
 * seasonal events; the constructor takes a pre-merged table so the layering
 * concern stays out of this class.
 */
public class YamlElementMatrix(
    private val table: Map<DamageAttribute, Map<DamageAttribute, Double>>,
) : IElementMatrix {

    override fun multiplier(attacker: DamageAttribute, defender: DamageAttribute): Double =
        table[attacker]?.get(defender) ?: 1.0

    public companion object {
        private val log = LoggerFactory.getLogger(YamlElementMatrix::class.java)

        /** Build from an already-parsed config. */
        public fun fromConfig(config: ElementMatrixYamlConfig): YamlElementMatrix =
            YamlElementMatrix(config.toTable())

        /**
         * Loads from a single file. Returns the [IElementMatrix.Neutral]
         * instance when the file is absent so callers can wire the matrix
         * unconditionally and content authors can omit the file in early dev.
         */
        public fun loadFromFile(
            file: Path,
            parsers: List<IContentParser> = WeaponLoaderService.DEFAULT_PARSERS,
        ): IElementMatrix {
            if (!file.isRegularFile()) {
                log.warn("Element matrix file not found, using neutral matrix: {}", file)
                return IElementMatrix.Neutral
            }
            val parser = parsers.firstOrNull { it.supports(file.extension) }
            if (parser == null) {
                log.warn(
                    "No content parser for {}, using neutral matrix (registered: {})",
                    file,
                    parsers.joinToString { it::class.simpleName ?: "?" },
                )
                return IElementMatrix.Neutral
            }
            return try {
                val text = Files.readString(file)
                val cfg = parser.parse(text, ElementMatrixYamlConfig.serializer())
                log.info("Loaded element matrix from {}", file)
                fromConfig(cfg)
            } catch (t: Throwable) {
                log.error("Failed to parse element matrix {}, falling back to neutral: {}", file, t.message, t)
                IElementMatrix.Neutral
            }
        }
    }
}
