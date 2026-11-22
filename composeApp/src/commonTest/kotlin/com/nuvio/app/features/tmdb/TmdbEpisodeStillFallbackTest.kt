package com.nuvio.app.features.tmdb

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TmdbEpisodeStillFallbackTest {
    private val base = "https://image.tmdb.org/t/p/w500"

    private class FakeSource(
        private val showId: Long? = 42L,
        private val title: String? = "Show",
        private val seasons: Map<Int, Int> = mapOf(1 to 60),
        private val episodes: Map<Pair<Int, Int>, TmdbStillEpisode> = emptyMap(),
    ) : TmdbStillSource {
        var showCalls: Int = 0
            private set
        var episodeCalls: Int = 0
            private set
        var requestedSeasons: Set<Int> = emptySet()
            private set
        var failEpisodes: Boolean = false

        override suspend fun resolveShowId(contentId: String?, meta: MetaDetails): Long? = showId

        override suspend fun show(showId: Long): TmdbStillShow {
            showCalls++
            return TmdbStillShow(title = title, seasonCounts = seasons)
        }

        override suspend fun episodes(
            showId: Long,
            seasons: Collection<Int>,
        ): Map<Pair<Int, Int>, TmdbStillEpisode> {
            episodeCalls++
            requestedSeasons = seasons.toSet()
            if (failEpisodes) throw IllegalStateException("TMDB unavailable")
            return episodes.filterKeys { it.first in seasons }
        }
    }

    private fun fallback(
        source: FakeSource,
        enabled: Boolean = true,
        apiKey: String = "test-key",
    ): TmdbEpisodeStillFallbackService = TmdbEpisodeStillFallback.withDependencies(
        source = source,
        settings = { TmdbSettings(enabled = enabled) },
        apiKey = { apiKey },
        nowMs = { 0L },
    )

    private fun still(
        season: Int,
        episode: Int,
        title: String,
        airDate: String? = null,
        path: String = "/still$season$episode.jpg",
    ) = (season to episode) to TmdbStillEpisode(
        season = season,
        episode = episode,
        title = title,
        airDate = airDate,
        stillPath = path,
    )

    private fun video(
        season: Int,
        episode: Int,
        title: String = "Episode $episode",
        released: String? = null,
        thumbnail: String? = null,
    ) = MetaVideo(
        id = "tt1:$season:$episode",
        title = title,
        released = released,
        season = season,
        episode = episode,
        thumbnail = thumbnail,
        overview = "Overview $season $episode",
        runtime = 24,
    )

    private fun meta(
        tvdbCounts: Map<Int, Int> = mapOf(1 to 24, 2 to 2),
        titles: Map<Pair<Int, Int>, String> = emptyMap(),
        releases: Map<Pair<Int, Int>, String> = emptyMap(),
        thumbnails: Map<Pair<Int, Int>, String> = emptyMap(),
        withSpecials: Boolean = false,
    ): MetaDetails {
        val videos = buildList {
            if (withSpecials) add(video(0, 1, "A Special"))
            tvdbCounts.toSortedMap().forEach { (season, count) ->
                (1..count).forEach { episode ->
                    val key = season to episode
                    add(
                        video(
                            season = season,
                            episode = episode,
                            title = titles[key] ?: "Episode $episode",
                            released = releases[key],
                            thumbnail = thumbnails[key],
                        ),
                    )
                }
            }
        }
        return MetaDetails(id = "tt1", type = "series", name = "Show", videos = videos)
    }

    /** Comparing whole copies proves that no field other than thumbnail was touched. */
    private fun expected(meta: MetaDetails, fills: Map<Pair<Int, Int>, String>): MetaDetails = meta.copy(
        videos = meta.videos.map { video ->
            fills[video.season to video.episode]?.let { video.copy(thumbnail = it) } ?: video
        },
    )

    @Test
    fun `Re Zero style TVDB season one of 26 maps season two episode two to TMDB S1E28`() = runTest {
        val title = "The Name of the Planet"
        val input = meta(
            tvdbCounts = mapOf(1 to 26, 2 to 25),
            titles = mapOf(2 to 2 to title),
            releases = mapOf(2 to 2 to "2021-01-15"),
        )
        val source = FakeSource(
            seasons = mapOf(1 to 51),
            episodes = mapOf(still(1, 28, title, "2021-01-15", "/rezero28.jpg")),
        )

        val result = fallback(source).applyTo(input, "tt1")

        assertEquals(expected(input, mapOf(2 to 2 to "$base/rezero28.jpg")), result)
        assertEquals(setOf(1), source.requestedSeasons)
    }

    @Test
    fun `Apothecary style TVDB season one of 24 maps season two episode three to TMDB S1E27`() = runTest {
        val title = "The Sword and the Sorcerer"
        val input = meta(
            tvdbCounts = mapOf(1 to 24, 2 to 24),
            titles = mapOf(2 to 3 to title),
            releases = mapOf(2 to 3 to "2023-10-21"),
        )
        val source = FakeSource(
            seasons = mapOf(1 to 47),
            episodes = mapOf(still(1, 27, title, "2023-10-21", "/apothecary27.jpg")),
        )

        val result = fallback(source).applyTo(input, "tt1")

        assertEquals(expected(input, mapOf(2 to 3 to "$base/apothecary27.jpg")), result)
    }

    @Test
    fun `TMDB multi season layout uses the direct season and episode`() = runTest {
        val title = "A New Beginning"
        val input = meta(
            tvdbCounts = mapOf(1 to 2, 2 to 2),
            titles = mapOf(2 to 2 to title),
            releases = mapOf(2 to 2 to "2024-05-10"),
        )
        val source = FakeSource(
            seasons = mapOf(1 to 2, 2 to 2),
            episodes = mapOf(still(2, 2, title, "2024-05-10", "/direct.jpg")),
        )

        val result = fallback(source).applyTo(input, "tt1")

        assertEquals(expected(input, mapOf(2 to 2 to "$base/direct.jpg")), result)
    }

    @Test
    fun `recap at the arithmetic position with a conflicting title stays blank`() = runTest {
        val input = meta(
            tvdbCounts = mapOf(1 to 24, 2 to 3),
            titles = mapOf(2 to 3 to "The Choice"),
            releases = mapOf(2 to 3 to "2021-02-01"),
        )
        val source = FakeSource(
            seasons = mapOf(1 to 40),
            episodes = mapOf(
                still(1, 27, "Recap", "2021-02-01", "/recap.jpg"),
                still(1, 33, "The Choice", null, "/choice1.jpg"),
                still(1, 34, "The Choice", null, "/choice2.jpg"),
            ),
        )

        val result = fallback(source).applyTo(input, "tt1")

        assertEquals(input, result)
    }

    @Test
    fun `generic title without a usable date stays blank`() = runTest {
        val input = meta(
            tvdbCounts = mapOf(1 to 24, 2 to 2),
            titles = mapOf(2 to 2 to "Episode 2"),
        )
        val source = FakeSource(
            seasons = mapOf(1 to 30),
            episodes = mapOf(still(1, 26, "Some Other Episode", "2021-01-01", "/wrong.jpg")),
        )

        val result = fallback(source).applyTo(input, "tt1")

        assertEquals(input, result)
    }

    @Test
    fun `unique title match is used when arithmetic verification fails`() = runTest {
        val input = meta(
            tvdbCounts = mapOf(1 to 24, 2 to 5),
            titles = mapOf(2 to 5 to "The Promise"),
            releases = mapOf(2 to 5 to "2021-03-10"),
        )
        val source = FakeSource(
            seasons = mapOf(1 to 40),
            episodes = mapOf(
                still(1, 29, "Recap", "2021-03-10", "/wrong.jpg"),
                still(1, 34, "The Promise", "2021-03-10", "/promise.jpg"),
            ),
        )

        val result = fallback(source).applyTo(input, "tt1")

        assertEquals(expected(input, mapOf(2 to 5 to "$base/promise.jpg")), result)
    }

    @Test
    fun `season zero and season one thumbnails are never filled`() = runTest {
        val title = "Finale"
        val input = meta(
            tvdbCounts = mapOf(1 to 2, 2 to 1),
            titles = mapOf(2 to 1 to title),
            releases = mapOf(2 to 1 to "2024-06-01"),
            withSpecials = true,
        )
        val source = FakeSource(
            seasons = mapOf(1 to 2, 2 to 1),
            episodes = mapOf(
                still(0, 1, "A Special", "2024-05-01", "/special.jpg"),
                still(1, 1, "Episode 1", "2024-05-15", "/s1e1.jpg"),
                still(2, 1, title, "2024-06-01", "/finale.jpg"),
            ),
        )

        val result = fallback(source).applyTo(input, "tt1")

        assertEquals(expected(input, mapOf(2 to 1 to "$base/finale.jpg")), result)
        assertEquals(setOf(2), source.requestedSeasons)
    }

    @Test
    fun `existing thumbnail is never overwritten`() = runTest {
        val input = meta(
            tvdbCounts = mapOf(1 to 2, 2 to 2),
            titles = mapOf(2 to 1 to "New Beginning"),
            thumbnails = mapOf(2 to 2 to "https://addon.example/existing.jpg"),
        )
        val source = FakeSource(
            seasons = mapOf(1 to 4),
            episodes = mapOf(
                still(1, 3, "New Beginning", null, "/new.jpg"),
                still(1, 4, "Second Season Finale", null, "/other.jpg"),
            ),
        )

        val result = fallback(source).applyTo(input, "tt1")

        assertEquals(expected(input, mapOf(2 to 1 to "$base/new.jpg")), result)
        assertEquals("https://addon.example/existing.jpg", result.videos.first { it.episode == 2 && it.season == 2 }.thumbnail)
    }

    @Test
    fun `disabled TMDB or blank API key leaves meta unchanged`() = runTest {
        val input = meta()
        val source = FakeSource(seasons = mapOf(1 to 30), episodes = mapOf(still(1, 3, "A Title", null, "/x.jpg")))

        assertEquals(input, fallback(source, enabled = false).applyTo(input, "tt1"))
        assertEquals(input, fallback(source, apiKey = "").applyTo(input, "tt1"))
        assertEquals(0, source.showCalls)
    }

    @Test
    fun `mismatched show title leaves meta unchanged`() = runTest {
        val input = meta()
        val source = FakeSource(title = "An Entirely Different Show")

        assertEquals(input, fallback(source).applyTo(input, "tt1"))
        assertEquals(1, source.showCalls)
        assertEquals(0, source.episodeCalls)
    }

    @Test
    fun `fetch failure leaves meta unchanged`() = runTest {
        val input = meta(titles = mapOf(2 to 1 to "New Beginning"))
        val source = FakeSource(episodes = mapOf(still(1, 3, "New Beginning", null, "/x.jpg"))).apply {
            failEpisodes = true
        }

        assertEquals(input, fallback(source).applyTo(input, "tt1"))
    }

    @Test
    fun `cache hit avoids a second fetch`() = runTest {
        val input = meta(titles = mapOf(2 to 1 to "New Beginning"))
        val source = FakeSource(episodes = mapOf(still(1, 3, "New Beginning", null, "/cached.jpg")))
        val service = fallback(source)

        val first = service.applyTo(input, "tt1")
        val second = service.applyTo(input, "tt1")

        val filled = expected(input, mapOf(2 to 1 to "$base/cached.jpg"))
        assertEquals(filled, first)
        assertEquals(filled, second)
        assertEquals(1, source.showCalls)
        assertEquals(1, source.episodeCalls)
        assertTrue(source.showCalls <= 1)
    }
}
