package cz.mujrozhlas.dl

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// --- JSON:API response wrappers ---

@Serializable
data class JsonApiListResponse(
    val data: List<JsonApiResource>,
    val meta: JsonApiMeta? = null,
)

@Serializable
data class JsonApiSingleResponse(
    val data: JsonApiResource,
)

@Serializable
data class JsonApiMeta(
    val count: Int? = null,
)

@Serializable
data class JsonApiResource(
    val type: String,
    val id: String,
    val attributes: JsonElement,
    val relationships: JsonElement? = null,
)

// --- Domain models ---

data class Serial(
    val uuid: String,
    val title: String,
    val totalParts: Int,
    val episodes: List<Episode> = emptyList(),
)

data class Episode(
    val uuid: String,
    val title: String,
    val part: Int,
    val audioLinks: List<AudioLink>,
    val serialUuid: String? = null,
    val serialTitle: String? = null,
)

data class AudioLink(
    val url: String,
    val variant: String,
    val duration: Int,
    val bitrate: Int,
    val playableTill: String? = null,
)
