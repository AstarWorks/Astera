package com.astarworks.astera.stats.application.service.season

import com.astarworks.astera.stats.application.port.outbound.ISeasonRepository
import com.astarworks.astera.stats.domain.season.Season
import com.astarworks.astera.stats.domain.season.SeasonId
import java.time.Instant

/**
 * In-memory [ISeasonRepository] default (priority 0, Wave 7b, ADR-0037).
 *
 * A fresh server is never season-less: construction opens a default active
 * `season-1` ("Season 1") so [current] is non-null before any admin reset.
 * The Postgres overlay in `plugin:persistence` (priority 100) supersedes this
 * at runtime.
 *
 * [start] derives the new season's [SeasonId] from a kebab-case slug of the
 * label, suffixing `-2`, `-3`, … on collision so two seasons sharing a label
 * still get distinct partition keys.
 *
 * @param clock supplies the default season's `startedAt`; injected so tests can
 *   pin instants deterministically.
 */
public class InMemorySeasonRepository(
    private val clock: () -> Instant = { Instant.now() },
) : ISeasonRepository {

    private val lock = Any()
    private val completedSchedules = mutableSetOf<String>()

    /** Insertion order: oldest first, newest last. */
    private val seasons: MutableList<Season> = mutableListOf(
        Season(id = SeasonId("season-1"), label = "Season 1", startedAt = clock()),
    )

    override fun current(): Season? = synchronized(lock) {
        seasons.lastOrNull { it.isActive }
    }

    override fun start(label: String, at: Instant): Season = synchronized(lock) {
        val activeIndex = seasons.indexOfLast { it.isActive }
        if (activeIndex >= 0) {
            seasons[activeIndex] = seasons[activeIndex].copy(endedAt = at)
        }
        val id = uniqueId(slugify(label))
        val opened = Season(id = id, label = label, startedAt = at)
        seasons += opened
        opened
    }

    override fun startOnce(scheduleKey: String, label: String, at: Instant): Season? = synchronized(lock) {
        if (scheduleKey in completedSchedules) return@synchronized null
        val season = start(label, at)
        completedSchedules += scheduleKey
        season
    }

    override fun all(): List<Season> = synchronized(lock) {
        seasons.asReversed().toList()
    }

    private fun uniqueId(slug: String): SeasonId {
        val taken = seasons.mapTo(mutableSetOf()) { it.id.value }
        if (slug !in taken) return SeasonId(slug)
        var suffix = 2
        while ("$slug-$suffix" in taken) suffix++
        return SeasonId("$slug-$suffix")
    }

    private companion object {
        private val NON_SLUG = Regex("[^a-z0-9]+")

        /**
         * Lowercase, collapse every run of non `[a-z0-9]` into a single `-`, and
         * trim leading/trailing `-`. [SeasonId] must start with a letter, so a
         * slug that is empty or starts with a digit is prefixed with `season-`.
         */
        private fun slugify(label: String): String {
            val base = label.lowercase().replace(NON_SLUG, "-").trim('-')
            return when {
                base.isEmpty() -> "season"
                base.first() !in 'a'..'z' -> "season-$base"
                else -> base
            }
        }
    }
}
