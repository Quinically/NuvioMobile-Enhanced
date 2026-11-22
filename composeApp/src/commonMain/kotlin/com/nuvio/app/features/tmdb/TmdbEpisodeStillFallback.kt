package com.nuvio.app.features.tmdb

import co.touchlab.kermit.Logger
import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.core.time.isoEpochDay
import com.nuvio.app.core.time.parseEpisodeReleaseLocalDate
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.mdblist.TmdbMdbListEpisodes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/** One TMDB episode as needed to fill a missing addon thumbnail. */
internal data class TmdbStillEpisode(
    val season: Int,
    val episode: Int,
    val title: String?,
    val airDate: String?,
    val stillPath: String?,
) {
    fun hasStill(): Boolean = !stillPath.isNullOrBlank()
}

internal data class TmdbStillShow(
    val title: String?,
    val seasonCounts: Map<Int, Int>,
)

/** The narrow slice of TMDB this service needs, replaceable by a fake in tests. */
internal interface TmdbStillSource {
    suspend fun resolveShowId(contentId: String?, meta: MetaDetails): Long?
    suspend fun show(showId: Long): TmdbStillShow?
    suspend fun episodes(showId: Long, seasons: Collection<Int>): Map<Pair<Int, Int>, TmdbStillEpisode>
}

/**
 * Fills blank episode thumbnails from TMDB stills without touching numbering, ids, watched state
 * or streams. Everything except [MetaVideo.thumbnail] is copied verbatim.
 */
internal class TmdbEpisodeStillFallbackService internal constructor(
    private val source: TmdbStillSource,
    private val settings: () -> TmdbSettings = { TmdbSettingsRepository.snapshot() },
    private val apiKey: () -> String = { TmdbSettingsRepository.effectiveApiKey() },
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val log = Logger.withTag("TmdbEpisodeStills")

    private val cacheLock = Mutex()
    private val cache = LinkedHashMap<String, CacheEntry>()
    private val inFlightLock = Mutex()
    private val inFlight = mutableMapOf<String, CompletableDeferred<ResolvedStills?>>()

    private data class CacheEntry(val data: ResolvedStills, val fetchedAtMs: Long)

    private data class ResolvedStills(
        val showId: Long,
        val seasonCounts: Map<Int, Int>,
        val episodes: Map<Pair<Int, Int>, TmdbStillEpisode>,
    )

    /** True when [meta] has at least one episode this feature is allowed to fill. */
    fun shouldApply(meta: MetaDetails): Boolean =
        meta.type.equals("series", ignoreCase = true) &&
            meta.videos.any { video -> isEligible(video) }

    suspend fun applyTo(meta: MetaDetails, contentId: String?): MetaDetails = try {
        if (!shouldApply(meta)) return meta
        if (!settings().enabled || apiKey().isBlank()) return meta
        val cacheKey = listOfNotNull(contentId, meta.id, meta.imdbId)
            .map(String::trim)
            .firstOrNull(String::isNotBlank)
            ?: return meta

        val stills = loadStills(meta, cacheKey) ?: return meta
        applyStills(meta, stills)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        // Never surface a failure: this feature only paints optional artwork.
        log.w { "episode still fallback failed key=${contentId.orEmpty()}: ${error::class.simpleName}" }
        InAppLogger.warn("Metadata/TMDB", "Episode still fallback failed: ${error::class.simpleName}")
        meta
    }

    private suspend fun loadStills(meta: MetaDetails, cacheKey: String): ResolvedStills? {
        cached(cacheKey)?.let { return it }
        val (deferred, owner) = inFlightLock.withLock {
            cached(cacheKey)?.let { existing ->
                CompletableDeferred<ResolvedStills?>().apply { complete(existing) } to false
            } ?: (inFlight[cacheKey]?.let { it to false }
                ?: (CompletableDeferred<ResolvedStills?>().also { inFlight[cacheKey] = it } to true))
        }
        if (!owner) return deferred.await()

        return try {
            val resolved = withTimeoutOrNull(NETWORK_TIMEOUT_MS) {
                val showId = source.resolveShowId(cacheKey, meta) ?: return@withTimeoutOrNull null
                val show = source.show(showId) ?: return@withTimeoutOrNull null
                if (!titlesCompatible(show.title, meta.name)) {
                    InAppLogger.info("Metadata/TMDB", "Episode stills show title mismatch id=$showId")
                    return@withTimeoutOrNull null
                }
                val seasons = neededSeasons(meta, show.seasonCounts)
                if (seasons.isEmpty()) return@withTimeoutOrNull null
                val episodes = source.episodes(showId, seasons)
                if (episodes.isEmpty()) return@withTimeoutOrNull null
                ResolvedStills(showId, show.seasonCounts, episodes)
            }
            if (resolved != null) {
                remember(cacheKey, resolved)
                remember("tmdb:${resolved.showId}", resolved)
            }
            deferred.complete(resolved)
            resolved
        } catch (error: CancellationException) {
            deferred.complete(null)
            throw error
        } catch (error: Exception) {
            log.w { "episode still lookup failed key=$cacheKey: ${error::class.simpleName}" }
            deferred.complete(null)
            null
        } finally {
            inFlightLock.withLock { inFlight.remove(cacheKey) }
        }
    }

    private suspend fun cached(key: String): ResolvedStills? {
        val entry = cacheLock.withLock { cache[key] } ?: return null
        if (nowMs() - entry.fetchedAtMs > CACHE_TTL_MS) {
            cacheLock.withLock { cache.remove(key) }
            return null
        }
        return entry.data
    }

    private suspend fun remember(key: String, data: ResolvedStills) {
        cacheLock.withLock { cache[key] = CacheEntry(data, nowMs()) }
    }

    private fun applyStills(meta: MetaDetails, stills: ResolvedStills): MetaDetails {
        val tvdbCounts = tvdbSeasonCounts(meta.videos)
        val replacements = linkedMapOf<Int, String>()
        meta.videos.forEachIndexed { index, video ->
            if (!isEligible(video)) return@forEachIndexed
            val season = video.season ?: return@forEachIndexed
            val episode = video.episode ?: return@forEachIndexed
            val arithmetic = arithmeticCandidate(video, tvdbCounts, stills)
            val match = arithmetic?.takeIf { verified(video, it) }
                ?: uniqueTitleMatch(video, stills.episodes.values)
                ?: return@forEachIndexed
            val path = match.stillPath?.trim()?.takeIf(String::isNotBlank) ?: return@forEachIndexed
            // Same rule as TmdbMetadataService.buildImageUrl.
            replacements[index] = "$STILL_BASE_URL$STILL_SIZE$path"
        }
        if (replacements.isEmpty()) return meta
        val filled = replacements.size
        InAppLogger.info(
            "Metadata/TMDB",
            "Episode stills show=${stills.showId} blank=${meta.videos.count { isEligible(it) }} filled=$filled",
        )
        return meta.copy(
            videos = meta.videos.mapIndexed { index, video ->
                replacements[index]?.let { video.copy(thumbnail = it) } ?: video
            },
        )
    }

    /**
     * TVDB's seasons are often split differently from TMDB's. Try the same numbers first, then
     * the flattened position obtained by adding the number of episodes in earlier TVDB seasons.
     */
    private fun arithmeticCandidate(
        video: MetaVideo,
        tvdbCounts: Map<Int, Int>,
        stills: ResolvedStills,
    ): TmdbStillEpisode? {
        val season = video.season ?: return null
        val episode = video.episode ?: return null
        val direct = stills.episodes[season to episode]?.takeIf(TmdbStillEpisode::hasStill)
        if (direct != null) return direct
        val prior = tvdbCounts.filterKeys { it < season }.values.sum()
        val position = flattenedTarget(prior + episode, stills.seasonCounts) ?: return null
        return stills.episodes[position]?.takeIf(TmdbStillEpisode::hasStill)
    }

    /**
     * Accept a candidate only on a title match or a nearby air date. When both signals exist and
     * disagree, the episode is treated as unknown. Runtime is deliberately never used.
     */
    private fun verified(video: MetaVideo, candidate: TmdbStillEpisode): Boolean {
        val videoTitle = usefulTitle(video.title)
        val candidateTitle = usefulTitle(candidate.title)
        val titleAvailable = videoTitle != null && candidateTitle != null
        val titleMatches = titleAvailable && videoTitle == candidateTitle

        val videoDate = parseEpisodeReleaseLocalDate(video.released)
        val candidateDate = parseEpisodeReleaseLocalDate(candidate.airDate)
        val dateAvailable = videoDate != null && candidateDate != null
        val dateMatches = dateAvailable &&
            kotlin.math.abs(isoEpochDay(videoDate) - isoEpochDay(candidateDate)) <= MAX_DATE_GAP_DAYS

        if (titleAvailable && dateAvailable && titleMatches != dateMatches) return false
        return titleMatches || dateMatches
    }

    /** Last resort: an unmistakable, unique title anywhere in the fetched TMDB episodes. */
    private fun uniqueTitleMatch(video: MetaVideo, episodes: Collection<TmdbStillEpisode>): TmdbStillEpisode? {
        val title = usefulTitle(video.title) ?: return null
        return episodes.filter { it.hasStill() && usefulTitle(it.title) == title }.singleOrNull()
    }

    private fun neededSeasons(meta: MetaDetails, tmdbSeasons: Map<Int, Int>): Set<Int> {
        if (tmdbSeasons.isEmpty()) return emptySet()
        val tvdbCounts = tvdbSeasonCounts(meta.videos)
        val needed = linkedSetOf<Int>()
        meta.videos.forEach { video ->
            if (!isEligible(video)) return@forEach
            val season = video.season ?: return@forEach
            val episode = video.episode ?: return@forEach
            if (season in tmdbSeasons) needed += season
            val prior = tvdbCounts.filterKeys { it < season }.values.sum()
            flattenedTarget(prior + episode, tmdbSeasons)?.first?.let(needed::add)
        }
        return needed
    }

    private fun isEligible(video: MetaVideo): Boolean {
        val season = video.season ?: return false
        return season >= 2 && video.episode != null && video.thumbnail.isNullOrBlank()
    }

    private fun tvdbSeasonCounts(videos: List<MetaVideo>): Map<Int, Int> =
        videos.mapNotNull { video ->
            val season = video.season?.takeIf { it >= 1 } ?: return@mapNotNull null
            val episode = video.episode?.takeIf { it > 0 } ?: return@mapNotNull null
            season to episode
        }.groupBy({ it.first }, { it.second }).mapValues { (_, episodes) -> episodes.toSet().size }

    /** Turns a one-based episode position into TMDB's (season, episode) pair. */
    private fun flattenedTarget(position: Int, seasonCounts: Map<Int, Int>): Pair<Int, Int>? {
        if (position < 1) return null
        var remaining = position
        for (season in seasonCounts.keys.sorted()) {
            val count = seasonCounts[season]?.takeIf { it > 0 } ?: continue
            if (remaining <= count) return season to remaining
            remaining -= count
        }
        return null
    }

    private fun usefulTitle(raw: String?): String? {
        val value = raw?.trim()?.takeIf(String::isNotBlank) ?: return null
        val normalized = value.lowercase().replace(NON_ALPHANUMERIC, "")
        if (normalized.isEmpty()) return null
        if (GENERIC_TITLE.containsMatchIn(value) || NORMALIZED_GENERIC.matches(normalized)) return null
        return normalized
    }

    private fun titlesCompatible(tmdbTitle: String?, metaName: String?): Boolean {
        val actual = usefulTitle(tmdbTitle) ?: return true
        val expected = usefulTitle(metaName) ?: return true
        if (actual == expected) return true
        if (actual.length < MIN_TITLE_LENGTH || expected.length < MIN_TITLE_LENGTH) return false
        // Prefix matching tolerates anime aliases such as "Re:Zero" vs
        // "Re:Zero -Starting Life in Another World-", but not unrelated titles that
        // merely end with the same short word.
        return actual.startsWith(expected) || expected.startsWith(actual)
    }

    private companion object {
        const val NETWORK_TIMEOUT_MS = 4_000L
        const val CACHE_TTL_MS = 10L * 60 * 1_000
        const val MAX_DATE_GAP_DAYS = 2L
        const val MIN_TITLE_LENGTH = 4
        const val STILL_BASE_URL = "https://image.tmdb.org/t/p/"
        const val STILL_SIZE = "w500"
        val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")
        val GENERIC_TITLE = Regex("(?i)\\b(episode|ep|e)\\s*\\d+\\b")
        val NORMALIZED_GENERIC = Regex("^(episode|ep|e)\\d+$")
    }
}

/** Default entry point used by metadata enrichment. */
internal object TmdbEpisodeStillFallback {
    private val default = TmdbEpisodeStillFallbackService(NetworkTmdbStillSource)

    fun shouldApply(meta: MetaDetails): Boolean = default.shouldApply(meta)

    suspend fun applyTo(meta: MetaDetails, contentId: String?): MetaDetails =
        default.applyTo(meta, contentId)

    /** Fresh instance with fake dependencies for tests (separate cache and in-flight map). */
    internal fun withDependencies(
        source: TmdbStillSource,
        settings: () -> TmdbSettings = { TmdbSettingsRepository.snapshot() },
        apiKey: () -> String = { TmdbSettingsRepository.effectiveApiKey() },
        nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    ): TmdbEpisodeStillFallbackService = TmdbEpisodeStillFallbackService(source, settings, apiKey, nowMs)
}

internal object NetworkTmdbStillSource : TmdbStillSource {
    private const val LANGUAGE = "en-US"
    private const val SEASON_CONCURRENCY = 4
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun resolveShowId(contentId: String?, meta: MetaDetails): Long? {
        val candidates = linkedSetOf<String>()
        contentId?.trim()?.takeIf(String::isNotBlank)?.let(candidates::add)
        meta.id.trim().takeIf(String::isNotBlank)?.let(candidates::add)
        meta.imdbId?.trim()?.takeIf(String::isNotBlank)?.let(candidates::add)
        meta.tvdbId?.trim()?.takeIf(String::isNotBlank)?.let { candidates += "tvdb:$it" }
        for (candidate in candidates) {
            TmdbMdbListEpisodes.showId(candidate)?.let { return it }
            TmdbService.ensureTmdbId(candidate, "tv", meta.imdbId)?.toLongOrNull()?.let { return it }
        }
        return null
    }

    override suspend fun show(showId: Long): TmdbStillShow? {
        val response = fetch<TmdbShowStillsResponse>("tv/$showId", mapOf("language" to LANGUAGE))
            ?: return null
        val seasonCounts = response.seasons.mapNotNull { season ->
            val number = season.seasonNumber?.takeIf { it > 0 } ?: return@mapNotNull null
            val count = season.episodeCount?.takeIf { it > 0 } ?: return@mapNotNull null
            number to count
        }.toMap()
        return TmdbStillShow(
            title = response.name?.trim()?.takeIf(String::isNotBlank),
            seasonCounts = seasonCounts,
        )
    }

    override suspend fun episodes(
        showId: Long,
        seasons: Collection<Int>,
    ): Map<Pair<Int, Int>, TmdbStillEpisode> = coroutineScope {
        val semaphore = Semaphore(SEASON_CONCURRENCY)
        seasons.sorted().map { season ->
            async {
                semaphore.withPermit {
                    fetch<TmdbSeasonStillsResponse>(
                        endpoint = "tv/$showId/season/$season",
                        query = mapOf("language" to LANGUAGE),
                    )?.episodes.orEmpty().mapNotNull { episode ->
                        val number = episode.episodeNumber?.takeIf { it > 0 } ?: return@mapNotNull null
                        (season to number) to TmdbStillEpisode(
                            season = season,
                            episode = number,
                            title = episode.name,
                            airDate = episode.airDate,
                            stillPath = episode.stillPath,
                        )
                    }
                }
            }
        }.awaitAll().flatten().toMap()
    }

    private suspend inline fun <reified T> fetch(
        endpoint: String,
        query: Map<String, String> = emptyMap(),
    ): T? {
        val apiKey = TmdbSettingsRepository.effectiveApiKey().takeIf(String::isNotBlank) ?: return null
        return try {
            json.decodeFromString<T>(httpGetText(buildTmdbUrl(endpoint = endpoint, apiKey = apiKey, query = query)))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }
}

@Serializable
private data class TmdbShowStillsResponse(
    val name: String? = null,
    val seasons: List<TmdbSeasonStillsSummary> = emptyList(),
)

@Serializable
private data class TmdbSeasonStillsSummary(
    @SerialName("season_number") val seasonNumber: Int? = null,
    @SerialName("episode_count") val episodeCount: Int? = null,
)

@Serializable
private data class TmdbSeasonStillsResponse(
    val episodes: List<TmdbStillEpisodeResponse> = emptyList(),
)

@Serializable
private data class TmdbStillEpisodeResponse(
    val name: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    @SerialName("still_path") val stillPath: String? = null,
    @SerialName("episode_number") val episodeNumber: Int? = null,
)
