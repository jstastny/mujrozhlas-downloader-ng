package com.stastnarodina.mujrozhlas

import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class Api(
    private val baseUrl: String = "https://api.mujrozhlas.cz",
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    // --- Shows ---

    fun searchShows(query: String): List<Show> {
        val url = "$baseUrl/shows".toHttpUrl().newBuilder()
            .addQueryParameter("filter[title][like]", query)
            .addQueryParameter("page[limit]", "20")
            .build()
        val body = fetch(url.toString())
        return json.decodeFromString<JsonApiListResponse>(body).data.map { parseShow(it) }
    }

    fun getShow(uuid: String): Show {
        val body = fetch("$baseUrl/shows/$uuid")
        return parseShow(json.decodeFromString<JsonApiSingleResponse>(body).data)
    }

    fun getShowEpisodes(showUuid: String): List<Episode> {
        val url = "$baseUrl/shows/$showUuid/episodes".toHttpUrl().newBuilder()
            .addQueryParameter("page[limit]", "10000")
            .build()
        val body = fetch(url.toString())
        return json.decodeFromString<JsonApiListResponse>(body).data
            .map { parseEpisode(it) }
            .sortedBy { it.orderNumber }
    }

    fun getShowSerials(showUuid: String): List<Serial> {
        val url = "$baseUrl/shows/$showUuid/serials".toHttpUrl().newBuilder()
            .addQueryParameter("page[limit]", "100")
            .build()
        val body = fetch(url.toString())
        return json.decodeFromString<JsonApiListResponse>(body).data.map { parseSerial(it) }
    }

    // --- Serials ---

    fun searchSerials(query: String): List<Serial> {
        val url = "$baseUrl/serials".toHttpUrl().newBuilder()
            .addQueryParameter("filter[title][like]", query)
            .addQueryParameter("page[limit]", "20")
            .build()
        val body = fetch(url.toString())
        return json.decodeFromString<JsonApiListResponse>(body).data.map { parseSerial(it) }
    }

    fun getSerial(uuid: String): Serial {
        val body = fetch("$baseUrl/serials/$uuid")
        return parseSerial(json.decodeFromString<JsonApiSingleResponse>(body).data)
    }

    fun getSerialEpisodes(serialUuid: String): List<Episode> {
        val url = "$baseUrl/serials/$serialUuid/episodes".toHttpUrl().newBuilder()
            .addQueryParameter("page[limit]", "10000")
            .build()
        val body = fetch(url.toString())
        return json.decodeFromString<JsonApiListResponse>(body).data
            .map { parseEpisode(it) }
            .sortedBy { it.orderNumber }
    }

    // --- Episodes ---

    fun getRecentEpisodes(limit: Int = 200): List<Episode> {
        val url = "$baseUrl/episodes".toHttpUrl().newBuilder()
            .addQueryParameter("sort", "-since")
            .addQueryParameter("page[limit]", limit.toString())
            .build()
        val body = fetch(url.toString())
        return json.decodeFromString<JsonApiListResponse>(body).data.map { parseEpisode(it) }
    }

    fun searchEpisodes(query: String): List<Episode> {
        val url = "$baseUrl/episodes".toHttpUrl().newBuilder()
            .addQueryParameter("filter[title][like]", query)
            .addQueryParameter("page[limit]", "20")
            .build()
        val body = fetch(url.toString())
        return json.decodeFromString<JsonApiListResponse>(body).data.map { parseEpisode(it) }
    }

    fun getEpisode(uuid: String): Episode {
        val body = fetch("$baseUrl/episodes/$uuid")
        return parseEpisode(json.decodeFromString<JsonApiSingleResponse>(body).data)
    }

    // --- HTTP ---

    private fun fetch(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("API request failed: ${response.code} ${response.message} for $url")
            }
            return response.body.string()
        }
    }

    // --- Parsing ---

    private fun parseShow(resource: JsonApiResource): Show {
        val attrs = json.decodeFromJsonElement<JsonObject>(resource.attributes)
        val imageUrl = attrs["asset"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
        return Show(
            uuid = resource.id,
            title = attrs["title"]?.jsonPrimitive?.content ?: "",
            imageUrl = imageUrl,
        )
    }

    private fun parseSerial(resource: JsonApiResource): Serial {
        val attrs = json.decodeFromJsonElement<JsonObject>(resource.attributes)
        val imageUrl = attrs["asset"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull

        val showUuid = resource.relationships?.let { rels ->
            val relsObj = json.decodeFromJsonElement<JsonObject>(rels)
            relsObj["show"]?.jsonObject
                ?.get("data")?.jsonObject
                ?.get("id")?.jsonPrimitive?.contentOrNull
        }

        return Serial(
            uuid = resource.id,
            showUuid = showUuid,
            title = attrs["title"]?.jsonPrimitive?.content ?: "",
            totalParts = attrs["totalParts"]?.jsonPrimitive?.intOrNull ?: 0,
            lastEpisodeSince = attrs["lastEpisodeSince"]?.jsonPrimitive?.contentOrNull,
            imageUrl = imageUrl,
        )
    }

    private fun parseEpisode(resource: JsonApiResource): Episode {
        val attrs = json.decodeFromJsonElement<JsonObject>(resource.attributes)
        val audioLinksJson = attrs["audioLinks"]?.jsonArray ?: JsonArray(emptyList())

        val audioLinks = audioLinksJson.map { linkEl ->
            val link = linkEl.jsonObject
            AudioLink(
                url = link["url"]?.jsonPrimitive?.content ?: "",
                variant = link["variant"]?.jsonPrimitive?.content ?: "",
                duration = link["duration"]?.jsonPrimitive?.intOrNull ?: 0,
                bitrate = link["bitrate"]?.jsonPrimitive?.intOrNull ?: 0,
                playableTill = link["playableTill"]?.jsonPrimitive?.contentOrNull,
            )
        }

        val relsObj = resource.relationships?.let {
            json.decodeFromJsonElement<JsonObject>(it)
        }

        val serialUuid = relsObj?.get("serial")?.jsonObject
            ?.get("data")?.jsonObject
            ?.get("id")?.jsonPrimitive?.contentOrNull

        val showUuid = relsObj?.get("show")?.jsonObject
            ?.get("data")?.jsonObject
            ?.get("id")?.jsonPrimitive?.contentOrNull

        val serialTitle = attrs["mirroredSerial"]?.jsonObject
            ?.get("title")?.jsonPrimitive?.contentOrNull
        val showTitle = attrs["mirroredShow"]?.jsonObject
            ?.get("title")?.jsonPrimitive?.contentOrNull

        return Episode(
            uuid = resource.id,
            title = attrs["title"]?.jsonPrimitive?.content ?: "",
            part = attrs["part"]?.jsonPrimitive?.intOrNull,
            seriesEpisodeNumber = attrs["series_episode_number"]?.jsonPrimitive?.intOrNull,
            audioLinks = audioLinks,
            serialUuid = serialUuid,
            serialTitle = serialTitle,
            showUuid = showUuid,
            showTitle = showTitle,
        )
    }
}
