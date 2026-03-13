package com.stastnarodina.mujrozhlas

import java.net.URI
import java.text.Normalizer

sealed class ResolvedResult {
    data class ShowResult(val show: Show) : ResolvedResult()
    data class SerialResult(val serial: Serial) : ResolvedResult()
    data class EpisodeResult(val episode: Episode) : ResolvedResult()
}

class Resolver(private val api: Api) {

    fun resolve(url: String): ResolvedResult {
        val segments = parseUrlSegments(url)
        if (segments.isEmpty()) {
            throw RuntimeException("No path segments in URL: $url")
        }

        // Strategy 1: Try the first segment as a show slug.
        val showSlug = segments.first()
        val showResult = tryResolveByShowSlug(showSlug)
        if (showResult != null) return showResult

        // Strategy 2: Fall back to serial/episode search using last segment.
        val lastSegment = segments.last()
        val slug = lastSegment.replace(Regex("-\\d+$"), "")
        return resolveBySlug(slug, url)
    }

    /**
     * Try to find a show matching the given slug. If found, populate it
     * with its serials and/or direct episodes and return a [ResolvedResult].
     */
    private fun tryResolveByShowSlug(showSlug: String): ResolvedResult? {
        val query = showSlug.replace("-", " ")
        println("Searching shows for: \"$query\"")
        val shows = api.searchShows(query)
        if (shows.isEmpty()) return null

        val scored = shows
            .map { it to scoreMatch(showSlug, it.title) }
            .sortedByDescending { it.second }

        val best = scored.first()
        if (best.second <= 0.3) return null

        val show = best.first
        println("Found show: \"${show.title}\" (score: ${"%.2f".format(best.second)})")

        // Populate show with serials and direct episodes
        val serials = api.getShowSerials(show.uuid)
        val episodes = api.getShowEpisodes(show.uuid)

        val populatedSerials = serials.map { serial ->
            val serialEpisodes = api.getSerialEpisodes(serial.uuid)
            serial.copy(episodes = serialEpisodes)
        }

        // Direct episodes are those not belonging to any serial
        val serialEpisodeUuids = populatedSerials.flatMap { s -> s.episodes.map { it.uuid } }.toSet()
        val directEpisodes = episodes.filter { it.uuid !in serialEpisodeUuids }

        val populatedShow = show.copy(
            serials = populatedSerials,
            episodes = directEpisodes,
        )

        println("Show has ${populatedSerials.size} serial(s) and ${directEpisodes.size} direct episode(s)")
        return ResolvedResult.ShowResult(populatedShow)
    }

    /**
     * Original resolution strategy: generate search queries from the slug
     * and search serials, then episodes.
     */
    private fun resolveBySlug(slug: String, url: String): ResolvedResult {
        println("Falling back to slug search: $slug")
        val queries = slugToSearchQueries(slug)

        // Try serial search
        for (query in queries) {
            println("Searching serials for: \"$query\"")
            val serials = api.searchSerials(query)
            if (serials.isNotEmpty()) {
                val scored = serials
                    .map { it to scoreMatch(slug, it.title) }
                    .sortedByDescending { it.second }

                val best = scored.first()
                if (best.second > 0.3) {
                    println("Found serial: \"${best.first.title}\" (score: ${"%.2f".format(best.second)})")
                    val serial = best.first
                    val episodes = api.getSerialEpisodes(serial.uuid)
                    return ResolvedResult.SerialResult(serial.copy(episodes = episodes))
                }
            }
        }

        // Fall back to episode search
        for (query in queries) {
            println("Searching episodes for: \"$query\"")
            val episodes = api.searchEpisodes(query)
            if (episodes.isNotEmpty()) {
                val scored = episodes
                    .map { it to scoreMatch(slug, it.title) }
                    .sortedByDescending { it.second }

                val best = scored.first()
                if (best.second > 0.3) {
                    println("Found episode: \"${best.first.title}\" (score: ${"%.2f".format(best.second)})")
                    return ResolvedResult.EpisodeResult(best.first)
                }
            }
        }

        throw RuntimeException("Could not resolve URL: $url")
    }

    companion object {
        fun parseUrlSegments(url: String): List<String> {
            val path = URI(url).path.trimEnd('/')
            return path.split("/").filter { it.isNotEmpty() }
        }

        fun parseSlug(url: String): String {
            val path = URI(url).path.trimEnd('/')
            val lastSegment = path.substringAfterLast('/')
            return lastSegment.replace(Regex("-\\d+$"), "")
        }

        fun slugToSearchQueries(slug: String): List<String> {
            val words = slug.split("-").filter { it.isNotEmpty() }
            val queries = mutableListOf<String>()

            for (windowSize in words.size downTo 2) {
                for (start in 0..words.size - windowSize) {
                    queries.add(words.subList(start, start + windowSize).joinToString(" "))
                }
            }

            words.sortedByDescending { it.length }
                .filter { it.length >= 4 }
                .forEach { queries.add(it) }

            return queries
        }

        fun titleToSlug(title: String): String {
            val normalized = Normalizer.normalize(title.lowercase(), Normalizer.Form.NFKD)
            val ascii = normalized.replace(Regex("[^\\p{ASCII}]"), "")
            return ascii.replace(Regex("[^a-z0-9]+"), "-").trim('-')
        }

        fun scoreMatch(slug: String, title: String): Double {
            val titleSlug = titleToSlug(title)
            if (titleSlug.startsWith(slug)) return 1.0
            if (slug in titleSlug) return 0.8

            val slugWords = slug.split("-").toSet()
            val titleWords = titleSlug.split("-").toSet()
            val overlap = slugWords.intersect(titleWords).size
            return overlap.toDouble() / slugWords.size.coerceAtLeast(1)
        }
    }
}
