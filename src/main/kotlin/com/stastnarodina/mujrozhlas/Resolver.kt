package com.stastnarodina.mujrozhlas

import java.net.URI
import java.text.Normalizer

sealed class ResolvedResult {
    data class SerialResult(val serial: Serial) : ResolvedResult()
    data class EpisodeResult(val episode: Episode) : ResolvedResult()
}

class Resolver(private val api: Api) {

    fun resolve(url: String): ResolvedResult {
        val slug = parseSlug(url)
        println("Parsed slug: $slug")

        // Try serial search first
        val queries = slugToSearchQueries(slug)
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
        fun parseSlug(url: String): String {
            val path = URI(url).path.trimEnd('/')
            val lastSegment = path.substringAfterLast('/')
            // Strip trailing Drupal node ID (e.g., -3453094)
            return lastSegment.replace(Regex("-\\d+$"), "")
        }

        fun slugToSearchQueries(slug: String): List<String> {
            val words = slug.split("-").filter { it.isNotEmpty() }
            val queries = mutableListOf<String>()

            // Windows of decreasing size
            for (windowSize in words.size downTo 2) {
                for (start in 0..words.size - windowSize) {
                    queries.add(words.subList(start, start + windowSize).joinToString(" "))
                }
            }

            // Single-word fallback (longest words first, min 4 chars)
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
