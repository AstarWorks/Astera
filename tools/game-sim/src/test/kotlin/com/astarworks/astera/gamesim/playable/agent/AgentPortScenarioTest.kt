package com.astarworks.astera.gamesim.playable.agent

import com.astarworks.astera.gamesim.GameSim
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.StringReader

/**
 * ADR-0316: one Annihilation seat driven through the real JSON-lines [AgentPort] loop (real wiring, real content, real
 * bot-runner Navigator). Scripted: goto the Mid iron -> dig -> shop -> buy -> skip to Engage -> collect stars -> norma.
 *
 * A solo seat cannot buy the cheapest weapon inside the test window (6 iron = 6 node regrowths), so the buy asserts the
 * real, explained refusal; the norma line is reached through stars (level 2 = the first star goal).
 */
class AgentPortScenarioTest {

    private val script = listOf(
        """{"op":"new","mode":"annihilation","stage":"annihilation-arena","me":"red","seed":7}""",
        """{"op":"look"}""",
        """{"op":"act","do":"goto mid-ore:iron-8","max":2400}""",
        """{"op":"act","do":"dig mid-ore:iron-8","max":600}""",
        """{"op":"act","do":"goto shop:red","max":2400}""",
        """{"op":"act","do":"buy dagger","max":100,"map":"off"}""",
        """{"op":"skip","until":"phase=engage","max":3000,"map":"off"}""",
        """{"op":"act","do":"goto star","max":1200,"map":"off"}""",
        """{"op":"act","do":"goto star","max":1200,"map":"off"}""",
        """{"op":"act","do":"goto base:red","max":1200,"map":"off"}""",
        """{"op":"act","do":"norma star","max":300,"map":"off"}""",
        """{"op":"end"}""",
    )

    @Test
    fun `a scripted seat goes mine buy skip and star through the real port`() {
        val port = AgentPort(SoloMatchHost, GameSim.defaultContentDir().toFile(), GameSim.defaultTraceDir())
        val out = StringBuilder()
        port.run(BufferedReader(StringReader(script.joinToString("\n"))), out)
        val lines = out.lines().filter { it.isNotBlank() }
        assertThat(lines).`as`("one observation per command").hasSize(script.size)
        val obs = lines.map { Json.parseToJsonElement(it).jsonObject }

        // new: observation shape, full map with the legend glyphs, enemies stand in for the other teams.
        val first = obs[0]
        assertThat(first.bool("ok")).isTrue()
        assertThat(first["phase"]!!.jsonPrimitive.content).isEqualTo("build")
        assertThat(first["enemies"]!!.jsonArray).hasSize(2)
        assertThat(first["menu"]!!.jsonArray).isNotEmpty()
        val rows = mapRows(first)
        assertThat(rows.size).isBetween(20, 80)
        assertThat(rows.maxOf { it.length }).isLessThanOrEqualTo(80)
        val glyphs = rows.joinToString("").toSet()
        assertThat(glyphs).contains('@', '$', 'o', '#')

        // look: local 7x7 dump (7 rows + a legend line).
        val look = obs[1]["look"]!!.jsonArray
        assertThat(look).hasSize(8)
        assertThat(look.take(7).map { it.jsonPrimitive.content.substringAfter(' ').split(' ').size }).containsOnly(7)

        // goto -> dig: the Navigator walks to the node, the dig credits one iron.
        assertThat(obs[2]["stopped"]!!.jsonPrimitive.content).isEqualTo("arrived")
        assertThat(obs[2]["map"]!!.jsonPrimitive.content.lines().size).isLessThanOrEqualTo(20)
        assertThat(obs[3]["stopped"]!!.jsonPrimitive.content).isEqualTo("mined")
        assertThat(obs[3]["me"]!!.jsonObject["ore"]!!.jsonObject["iron"]!!.jsonPrimitive.int).isEqualTo(1)

        // shop + buy: reachable, and the refusal says what is missing.
        assertThat(obs[4]["stopped"]!!.jsonPrimitive.content).isEqualTo("arrived")
        assertThat(obs[5]["ok"]!!.jsonPrimitive.content).isEqualTo("false")
        assertThat(obs[5]["result"]!!.jsonPrimitive.content).contains("needs 6 iron")

        // skip DSL stops on its own condition, not on max.
        assertThat(obs[6]["stopped"]!!.jsonPrimitive.content).isEqualTo("until:phase=engage")
        assertThat(obs[6]["phase"]!!.jsonPrimitive.content).isEqualTo("engage")

        // stars and norma: lv2 is reached by collecting stars and checking at the base.
        val last = obs[10]["me"]!!.jsonObject
        assertThat(obs[8]["me"]!!.jsonObject["stars"]!!.jsonPrimitive.int).isGreaterThan(0)
        assertThat(last["norma"]!!.jsonObject["lv"]!!.jsonPrimitive.int).isGreaterThanOrEqualTo(2)

        // end: summary, three reflection questions, a trace path.
        val end = obs.last()
        assertThat(end["ended"]!!.jsonPrimitive.content).isEqualTo("true")
        assertThat(end["questions"]!!.jsonArray).hasSize(3)
        assertThat(end["trace"]!!.jsonPrimitive.content).isNotBlank()
    }

    private fun JsonObject.bool(key: String): Boolean = this[key]!!.jsonPrimitive.content.toBoolean()

    /** The ASCII rows of an observation (the header line `x0=.. z0=.. 1ch=..` dropped). */
    private fun mapRows(o: JsonObject): List<String> {
        val text = o["map"]!!.jsonPrimitive.content.lines()
        assertThat(text.first()).startsWith("x0=")
        return text.drop(1)
    }
}
