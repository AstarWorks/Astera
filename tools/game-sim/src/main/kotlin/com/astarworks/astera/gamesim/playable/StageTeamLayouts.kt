package com.astarworks.astera.gamesim.playable

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import java.nio.file.Files
import java.nio.file.Path

/**
 * Which teams play when N teams are requested (ADR-0323): the stage yaml's `team_layouts: { 2: [red, green], 4: [...] }`.
 * Isolated here so it is one place to switch to the plugin's stage DTO once it carries the field; until then the sim reads
 * `content/stages/<stage>.yaml` directly. A missing field or a missing key for N is an error, never a guess.
 */
object StageTeamLayouts {
    fun available(contentDir: Path, stage: String): Map<Int, List<String>> {
        val file = contentDir.resolve("stages").resolve("$stage.yaml")
        check(Files.isRegularFile(file)) { "stage file not found: $file" }
        val root = Yaml.default.parseToYamlNode(Files.readString(file)) as? YamlMap ?: return emptyMap()
        val layouts = root.get<YamlMap>("team_layouts") ?: return emptyMap()
        return layouts.entries.entries.associate { (k, v) ->
            val n = k.content.toIntOrNull() ?: error("stage $stage team_layouts key '${k.content}' is not a team count")
            val list = (v as? YamlList ?: error("stage $stage team_layouts[$n] must be a list of team ids")).items
                .map { (it as? YamlScalar ?: error("stage $stage team_layouts[$n] holds a non-scalar entry")).content }
            check(list.size == n && list.distinct().size == n) { "stage $stage team_layouts[$n] must list $n distinct teams, got $list" }
            n to list
        }
    }

    fun teamsFor(contentDir: Path, stage: String, count: Int): List<String> {
        val all = available(contentDir, stage)
        return all[count] ?: error(
            "requested $count teams but stage $stage " +
                (if (all.isEmpty()) "has no team_layouts field" else "has team_layouts only for ${all.keys.sorted()}") + " (no silent fallback)",
        )
    }
}
