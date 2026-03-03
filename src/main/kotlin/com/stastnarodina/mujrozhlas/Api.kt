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

    fun searchSerials(query: String): List<Serial> {
        val url = "$baseUrl/serials".toHttpUrl().newBuilder()
            .addQueryParameter("filter[title][like]", query)
            .addQueryParameter("page[limit]", "20")
            .build()

        val body = fetch(url.toString())
        val response = json.decodeFromString<JsonApiListResponse>(body)
        return response.data.map { parseSerial(it) }
    }

    fun getRecentEpisodes(limit: Int = 200): List<Episode> {
        val url = "$baseUrl/episodes".toHttpUrl().newBuilder()
            .addQueryParameter("sort", "-since")
            .addQueryParameter("page[limit]", limit.toString())
            .build()

        val body = fetch(url.toString())
        val response = json.decodeFromString<JsonApiListResponse>(body)
        return response.data.map { parseEpisode(it) }
    }

    fun getSerial(uuid: String): Serial {
        val body = fetch("$baseUrl/serials/$uuid")
        val response = json.decodeFromString<JsonApiSingleResponse>(body)
        return parseSerial(response.data)
    }

    fun getSerialEpisodes(serialUuid: String): List<Episode> {
        val url = "$baseUrl/serials/$serialUuid/episodes".toHttpUrl().newBuilder()
            .addQueryParameter("page[limit]", "10000")
            .build()

        val body = fetch(url.toString())
        val response = json.decodeFromString<JsonApiListResponse>(body)
        return response.data.map { parseEpisode(it) }.sortedBy { it.part }
    }

    fun searchEpisodes(query: String): List<Episode> {
        val url = "$baseUrl/episodes".toHttpUrl().newBuilder()
            .addQueryParameter("filter[title][like]", query)
            .addQueryParameter("page[limit]", "20")
            .build()

        val body = fetch(url.toString())
        val response = json.decodeFromString<JsonApiListResponse>(body)
        return response.data.map { parseEpisode(it) }
    }

    fun getEpisode(uuid: String): Episode {
        val body = fetch("$baseUrl/episodes/$uuid")
        val response = json.decodeFromString<JsonApiSingleResponse>(body)
        return parseEpisode(response.data)
    }

    private fun fetch(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("API request failed: ${response.code} ${response.message} for $url")
            }
            return response.body?.string() ?: throw RuntimeException("Empty response body for $url")
        }
    }

    private fun parseSerial(resource: JsonApiResource): Serial {
        val attrs = json.decodeFromJsonElement<JsonObject>(resource.attributes)
        return Serial(
            uuid = resource.id,
            title = attrs["title"]?.jsonPrimitive?.content ?: "",
            totalParts = attrs["totalParts"]?.jsonPrimitive?.intOrNull ?: 0,
            lastEpisodeSince = attrs["lastEpisodeSince"]?.jsonPrimitive?.contentOrNull,
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

        val serialUuid = resource.relationships?.let { rels ->
            val relsObj = json.decodeFromJsonElement<JsonObject>(rels)
            relsObj["serial"]?.jsonObject
                ?.get("data")?.jsonObject
                ?.get("id")?.jsonPrimitive?.contentOrNull
        }

        val mirroredSerial = attrs["mirroredSerial"]?.jsonObject
        val serialTitle = mirroredSerial?.get("title")?.jsonPrimitive?.contentOrNull

        return Episode(
            uuid = resource.id,
            title = attrs["title"]?.jsonPrimitive?.content ?: "",
            part = attrs["part"]?.jsonPrimitive?.intOrNull ?: 0,
            audioLinks = audioLinks,
            serialUuid = serialUuid,
            serialTitle = serialTitle,
        )
    }
}
