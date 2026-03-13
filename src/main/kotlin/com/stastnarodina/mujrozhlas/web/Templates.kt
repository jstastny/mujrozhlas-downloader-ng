package com.stastnarodina.mujrozhlas.web

import kotlinx.html.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// --- View data classes ---

enum class ContentUnitType { SHOW, SERIAL }

data class ContentUnit(
    val uuid: String,
    val title: String,
    val type: ContentUnitType,
    val episodeCount: Int,
    val pendingCount: Int,
    val downloadedCount: Int,
    val subscribed: Boolean,
    val imageUrl: String? = null,
    val parentShowTitle: String? = null,
    val parentShowUuid: String? = null,
    val detailUrl: String,
)

data class ShowRow(
    val uuid: String,
    val title: String,
    val serialCount: Int,
    val episodeCount: Int,
    val pendingCount: Int,
    val downloadedCount: Int,
    val subscribed: Boolean = false,
    val imageUrl: String? = null,
)

data class SerialRow(
    val uuid: String,
    val showUuid: String,
    val title: String,
    val totalParts: Int,
    val pendingCount: Int,
    val downloadedCount: Int,
    val lastEpisodeSince: String?,
    val m4bDownloadUrl: String? = null,
)

data class EpisodeRow(
    val uuid: String,
    val title: String,
    val number: Int?,
    val status: EpisodeStatus,
    val duration: Int,
    val playableTill: String?,
    val discoveredAt: Instant,
    val downloadedAt: Instant?,
    val filePath: String?,
    val errorMessage: String?,
    val downloadUrl: String? = null,
)

private val dateFormatter = DateTimeFormatter.ofPattern("d.M.yyyy HH:mm")
    .withZone(ZoneId.systemDefault())

private fun formatInstant(instant: Instant?): String =
    if (instant != null) dateFormatter.format(instant) else ""

private fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "${m}m ${s}s"
}

// --- Layout ---

fun HTML.layout(pageTitle: String, content: MAIN.() -> Unit) {
    head {
        meta { charset = "utf-8" }
        meta { name = "viewport"; this.content = "width=device-width, initial-scale=1" }
        title { +pageTitle }
        link {
            rel = "stylesheet"
            href = "https://cdn.jsdelivr.net/npm/@picocss/pico@2/css/pico.min.css"
        }
        script { src = "https://unpkg.com/htmx.org@2.0.4" }
        style {
            unsafe {
                +"""
                .badge { display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 0.8em; font-weight: bold; }
                .badge-pending { background: #ffc107; color: #000; }
                .badge-approved { background: #17a2b8; color: #fff; }
                .badge-downloading { background: #007bff; color: #fff; }
                .badge-downloaded { background: #28a745; color: #fff; }
                .badge-skipped { background: #6c757d; color: #fff; }
                .badge-error { background: #dc3545; color: #fff; }
                .badge-subscribed { background: #6f42c1; color: #fff; }
                .show-card { margin-bottom: 1rem; }
                .serial-card { margin-bottom: 0.5rem; padding: 0.5rem 1rem; background: var(--pico-card-sectioning-background-color); border-radius: 4px; }
                nav ul li { padding: 0 0.5rem; }
                .htmx-indicator { display: none; }
                .htmx-request .htmx-indicator { display: inline; }
                .htmx-request.htmx-indicator { display: inline; }
                """.trimIndent()
            }
        }
    }
    body {
        nav {
            attributes["class"] = "container"
            ul {
                li { a(href = "/") { strong { +"mujrozhlas-dl" } } }
            }
            ul {
                li { a(href = "/") { +"Dashboard" } }
                li { a(href = "/search") { +"Search" } }
                li { a(href = "/downloads") { +"Downloads" } }
            }
        }
        main {
            attributes["class"] = "container"
            content()
        }
    }
}

// --- Dashboard ---

fun MAIN.dashboard(units: List<ContentUnit>, isDiscovering: Boolean, isRefreshing: Boolean) {
    div {
        style = "display: flex; justify-content: space-between; align-items: center; margin-bottom: 1rem;"
        h1 { +"Dashboard" }
        div {
            style = "display: flex; gap: 0.5rem; align-items: center;"
            if (isDiscovering) {
                span {
                    id = "discover-status"
                    attributes["hx-get"] = "/discover/status"
                    attributes["hx-trigger"] = "every 2s"
                    attributes["hx-swap"] = "outerHTML"
                    +"Discovering..."
                    span { attributes["aria-busy"] = "true" }
                }
            } else {
                button {
                    attributes["hx-post"] = "/discover"
                    attributes["hx-target"] = "#discover-status"
                    attributes["hx-swap"] = "outerHTML"
                    +"Discover"
                }
                span { id = "discover-status" }
            }
            if (isRefreshing) {
                span {
                    id = "refresh-status"
                    attributes["hx-get"] = "/refresh/status"
                    attributes["hx-trigger"] = "every 2s"
                    attributes["hx-swap"] = "outerHTML"
                    +"Refreshing..."
                    span { attributes["aria-busy"] = "true" }
                }
            } else {
                button {
                    attributes["class"] = "outline"
                    attributes["hx-post"] = "/refresh"
                    attributes["hx-target"] = "#refresh-status"
                    attributes["hx-swap"] = "outerHTML"
                    +"Refresh Subscribed"
                }
                span { id = "refresh-status" }
            }
        }
    }

    // URL input
    form {
        attributes["hx-post"] = "/shows/add"
        attributes["hx-target"] = "#content-list"
        attributes["hx-swap"] = "afterbegin"
        role = "group"
        input {
            type = InputType.text
            name = "url"
            placeholder = "Paste mujrozhlas.cz URL or UUID..."
        }
        button { type = ButtonType.submit; +"Add" }
    }

    input {
        type = InputType.search
        id = "content-search"
        placeholder = "Filter..."
        attributes["oninput"] = "filterContent(this.value)"
        style = "margin-bottom: 1rem;"
    }

    div {
        id = "content-list"
        if (units.isEmpty()) {
            p { +"No content tracked yet. Add a URL above or click 'Discover Now'." }
        } else {
            for (unit in units) {
                contentUnitCard(unit)
            }
        }
    }

    script {
        unsafe {
            +"""
            function normalize(s) {
                return s.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
            }
            function filterContent(query) {
                var q = normalize(query);
                document.querySelectorAll('#content-list .content-card').forEach(function(card) {
                    var text = normalize(card.textContent);
                    card.style.display = text.includes(q) ? '' : 'none';
                });
            }
            """.trimIndent()
        }
    }
}

fun FlowContent.contentUnitCard(unit: ContentUnit) {
    val cardId = "${unit.type.name.lowercase()}-${unit.uuid}"
    val showUuid = if (unit.type == ContentUnitType.SHOW) unit.uuid else unit.parentShowUuid!!
    article {
        id = cardId
        attributes["class"] = "content-card"
        header {
            div {
                style = "display: flex; justify-content: space-between; align-items: center;"
                div {
                    a(href = unit.detailUrl) {
                        strong { +unit.title }
                    }
                    unit.parentShowTitle?.let { parentTitle ->
                        br
                        small { +"in $parentTitle" }
                    }
                }
                div {
                    if (unit.pendingCount > 0) {
                        span("badge badge-pending") { +"${unit.pendingCount} pending" }
                        +" "
                    }
                    if (unit.downloadedCount > 0) {
                        span("badge badge-downloaded") { +"${unit.downloadedCount} downloaded" }
                        +" "
                    }
                    if (unit.subscribed) {
                        button {
                            attributes["class"] = "outline contrast"
                            attributes["hx-post"] = "/shows/$showUuid/unsubscribe"
                            attributes["hx-confirm"] = "Unsubscribe from '${unit.title}'? Queued downloads will be cancelled."
                            style = "margin-left: 0.5rem; padding: 4px 12px; font-size: 0.85em;"
                            +"Unsubscribe"
                        }
                    } else {
                        button {
                            attributes["class"] = "outline"
                            attributes["hx-post"] = "/shows/$showUuid/subscribe"
                            style = "margin-left: 0.5rem; padding: 4px 12px; font-size: 0.85em;"
                            +"Subscribe"
                        }
                    }
                    button {
                        attributes["class"] = "outline secondary"
                        attributes["hx-post"] = "/shows/$showUuid/hide"
                        attributes["hx-target"] = "#$cardId"
                        attributes["hx-swap"] = "outerHTML"
                        attributes["hx-confirm"] = "Hide '${unit.title}' from dashboard?"
                        style = "margin-left: 0.5rem; padding: 4px 12px; font-size: 0.85em;"
                        +"Hide"
                    }
                }
            }
        }
        footer {
            small {
                +"${unit.episodeCount} episode(s)"
            }
        }
    }
}

// --- Show detail ---

fun MAIN.showDetail(
    show: ShowRow,
    serials: List<SerialRow>,
    directEpisodes: List<EpisodeRow>,
) {
    div {
        style = "display: flex; justify-content: space-between; align-items: center;"
        h1 { +show.title }
        div {
            if (!show.subscribed) {
                button {
                    attributes["hx-post"] = "/shows/${show.uuid}/subscribe"
                    style = "margin-right: 0.5rem;"
                    +"Subscribe"
                }
            } else {
                button {
                    attributes["class"] = "outline contrast"
                    attributes["hx-post"] = "/shows/${show.uuid}/unsubscribe"
                    attributes["hx-confirm"] = "Unsubscribe? Queued downloads will be cancelled."
                    style = "margin-right: 0.5rem;"
                    +"Unsubscribe"
                }
            }
        }
    }

    // Serials section
    if (serials.isNotEmpty()) {
        h2 { +"Serials" }
        for (serial in serials) {
            div {
                attributes["class"] = "serial-card"
                div {
                    style = "display: flex; justify-content: space-between; align-items: center;"
                    a(href = "/serials/${serial.uuid}") {
                        strong { +serial.title }
                    }
                    div {
                        if (serial.pendingCount > 0) {
                            span("badge badge-pending") { +"${serial.pendingCount} pending" }
                            +" "
                        }
                        if (serial.downloadedCount > 0) {
                            span("badge badge-downloaded") { +"${serial.downloadedCount} downloaded" }
                            +" "
                        }
                        serial.m4bDownloadUrl?.let { url ->
                            a(href = url) { attributes["download"] = ""; +"M4B" }
                        }
                    }
                }
                small {
                    +"${serial.totalParts} parts"
                    serial.lastEpisodeSince?.let { +" | Last: $it" }
                }
            }
        }
    }

    // Direct episodes section
    if (directEpisodes.isNotEmpty()) {
        h2 { +"Episodes" }
        val pendingCount = directEpisodes.count { it.status == EpisodeStatus.PENDING }
        if (pendingCount > 0) {
            button {
                attributes["class"] = "outline"
                attributes["hx-post"] = "/shows/${show.uuid}/approve-all"
                attributes["hx-target"] = "#episode-list"
                attributes["hx-swap"] = "innerHTML"
                +"Approve All ($pendingCount)"
            }
        }
        episodeTable(directEpisodes)
    }

    if (serials.isEmpty() && directEpisodes.isEmpty()) {
        p { +"No content found for this show." }
    }
}

// --- Serial detail ---

fun MAIN.serialDetail(serial: SerialRow, episodes: List<EpisodeRow>, parentShowTitle: String?, parentShowSubscribed: Boolean) {
    div {
        style = "display: flex; justify-content: space-between; align-items: center;"
        div {
            h1 { +serial.title }
            if (parentShowTitle != null) {
                p {
                    +"in "
                    a(href = "/shows/${serial.showUuid}") { +parentShowTitle }
                }
            }
        }
        div {
            if (parentShowSubscribed) {
                button {
                    attributes["class"] = "outline contrast"
                    attributes["hx-post"] = "/shows/${serial.showUuid}/unsubscribe"
                    attributes["hx-confirm"] = "Unsubscribe? Queued downloads will be cancelled."
                    style = "margin-right: 0.5rem;"
                    +"Unsubscribe"
                }
            } else {
                button {
                    attributes["hx-post"] = "/shows/${serial.showUuid}/subscribe"
                    style = "margin-right: 0.5rem;"
                    +"Subscribe"
                }
            }
            val downloadedCount = episodes.count { it.status == EpisodeStatus.DOWNLOADED }
            if (downloadedCount > 0 && serial.totalParts <= MAX_M4B_EPISODES) {
                span {
                    id = "m4b-status"
                    button {
                        attributes["class"] = "outline"
                        attributes["hx-post"] = "/serials/${serial.uuid}/combine-m4b"
                        attributes["hx-target"] = "#m4b-status"
                        attributes["hx-swap"] = "innerHTML"
                        style = "margin-right: 0.5rem;"
                        +"Recreate M4B"
                    }
                }
            }
            val pendingCount = episodes.count { it.status == EpisodeStatus.PENDING }
            if (pendingCount > 0) {
                button {
                    attributes["class"] = "outline"
                    attributes["hx-post"] = "/serials/${serial.uuid}/approve-all"
                    attributes["hx-target"] = "#episode-list"
                    attributes["hx-swap"] = "innerHTML"
                    +"Approve All ($pendingCount)"
                }
            }
        }
    }

    p {
        +"${serial.totalParts} parts total"
        serial.lastEpisodeSince?.let { +" | Last episode: $it" }
        serial.m4bDownloadUrl?.let { url ->
            +" | "
            a(href = url) { attributes["download"] = ""; +"Download M4B" }
        }
    }

    episodeTable(episodes)
}

// --- Shared episode table ---

fun FlowContent.episodeTable(episodes: List<EpisodeRow>) {
    table {
        id = "episode-list"
        thead {
            tr {
                th { +"#" }
                th { +"Title" }
                th { +"Duration" }
                th { +"Status" }
                th { +"Actions" }
            }
        }
        tbody {
            for (episode in episodes) {
                episodeRow(episode)
            }
        }
    }
}

fun TBODY.episodeRow(episode: EpisodeRow) {
    tr {
        id = "episode-${episode.uuid}"
        td { +(episode.number?.toString() ?: "") }
        td {
            +episode.title
            episode.errorMessage?.let {
                br
                small { style = "color: var(--pico-del-color);"; +it }
            }
        }
        td { +formatDuration(episode.duration) }
        td {
            val badgeClass = when (episode.status) {
                EpisodeStatus.PENDING -> "badge-pending"
                EpisodeStatus.APPROVED -> "badge-approved"
                EpisodeStatus.DOWNLOADING -> "badge-downloading"
                EpisodeStatus.DOWNLOADED -> "badge-downloaded"
                EpisodeStatus.SKIPPED -> "badge-skipped"
                EpisodeStatus.ERROR -> "badge-error"
            }
            span("badge $badgeClass") { +episode.status.name }
        }
        td {
            when (episode.status) {
                EpisodeStatus.PENDING -> {
                    button {
                        attributes["class"] = "outline"
                        attributes["hx-post"] = "/episodes/${episode.uuid}/approve"
                        attributes["hx-target"] = "#episode-${episode.uuid}"
                        attributes["hx-swap"] = "outerHTML"
                        style = "padding: 4px 12px; font-size: 0.85em; margin-right: 4px;"
                        +"Approve"
                    }
                    button {
                        attributes["class"] = "outline secondary"
                        attributes["hx-post"] = "/episodes/${episode.uuid}/skip"
                        attributes["hx-target"] = "#episode-${episode.uuid}"
                        attributes["hx-swap"] = "outerHTML"
                        style = "padding: 4px 12px; font-size: 0.85em;"
                        +"Skip"
                    }
                }
                EpisodeStatus.DOWNLOADING -> {
                    span {
                        attributes["hx-get"] = "/episodes/${episode.uuid}/status"
                        attributes["hx-trigger"] = "every 5s"
                        attributes["hx-target"] = "#episode-${episode.uuid}"
                        attributes["hx-swap"] = "outerHTML"
                        attributes["aria-busy"] = "true"
                        +"Downloading..."
                    }
                }
                EpisodeStatus.DOWNLOADED -> {
                    small { +formatInstant(episode.downloadedAt) }
                    episode.downloadUrl?.let { url ->
                        +" "
                        a(href = url) { attributes["download"] = ""; +"Download" }
                    }
                }
                EpisodeStatus.ERROR -> {
                    button {
                        attributes["class"] = "outline"
                        attributes["hx-post"] = "/episodes/${episode.uuid}/approve"
                        attributes["hx-target"] = "#episode-${episode.uuid}"
                        attributes["hx-swap"] = "outerHTML"
                        style = "padding: 4px 12px; font-size: 0.85em;"
                        +"Retry"
                    }
                }
                else -> {}
            }
        }
    }
}

// --- Search page ---

data class SearchResult(
    val uuid: String,
    val title: String,
    val type: String,         // "show", "serial", "episode"
    val detail: String?,      // e.g. "15 parts" or "Part 3"
    val imageUrl: String?,
    val alreadyAdded: Boolean,
)

fun MAIN.searchPage() {
    h1 { +"Search" }

    input {
        type = InputType.search
        name = "q"
        placeholder = "Search shows, serials, episodes..."
        attributes["hx-get"] = "/search/results"
        attributes["hx-trigger"] = "keyup changed delay:400ms, search"
        attributes["hx-target"] = "#search-results"
        attributes["hx-indicator"] = "#search-spinner"
        style = "margin-bottom: 1rem;"
    }

    span {
        id = "search-spinner"
        attributes["class"] = "htmx-indicator"
        attributes["aria-busy"] = "true"
    }

    div { id = "search-results" }
}

fun FlowContent.searchResults(results: List<SearchResult>) {
    if (results.isEmpty()) {
        p { +"No results found." }
        return
    }

    for (result in results) {
        article {
            attributes["class"] = "search-result"
            style = "padding: 0.75rem 1rem; margin-bottom: 0.5rem;"
            div {
                style = "display: flex; justify-content: space-between; align-items: center;"
                div {
                    strong { +result.title }
                    +" "
                    val badgeClass = when (result.type) {
                        "show" -> "badge-approved"
                        "serial" -> "badge-subscribed"
                        else -> "badge-pending"
                    }
                    span("badge $badgeClass") { +result.type }
                    result.detail?.let {
                        +" "
                        small { +it }
                    }
                }
                div {
                    if (result.alreadyAdded) {
                        span("badge badge-downloaded") { +"Added" }
                    } else {
                        button {
                            attributes["hx-post"] = "/search/add"
                            attributes["hx-vals"] = """{"uuid":"${result.uuid}","type":"${result.type}"}"""
                            attributes["hx-target"] = "closest article"
                            attributes["hx-swap"] = "outerHTML"
                            style = "padding: 4px 16px; font-size: 0.85em;"
                            +"Add"
                        }
                    }
                }
            }
        }
    }
}

fun FlowContent.searchResultAdded(result: SearchResult) {
    article {
        attributes["class"] = "search-result"
        style = "padding: 0.75rem 1rem; margin-bottom: 0.5rem;"
        div {
            style = "display: flex; justify-content: space-between; align-items: center;"
            div {
                strong { +result.title }
                +" "
                val badgeClass = when (result.type) {
                    "show" -> "badge-approved"
                    "serial" -> "badge-subscribed"
                    else -> "badge-pending"
                }
                span("badge $badgeClass") { +result.type }
                result.detail?.let {
                    +" "
                    small { +it }
                }
            }
            div {
                span("badge badge-downloaded") { +"Added" }
            }
        }
    }
}

// --- Downloads page ---

fun MAIN.downloadsPage(downloads: List<EpisodeRow>, currentDownloadUuid: String?) {
    h1 { +"Downloads" }

    if (downloads.isEmpty()) {
        p { +"No downloads yet. Approve some episodes first." }
        return
    }

    table {
        thead {
            tr {
                th { +"Title" }
                th { +"Status" }
                th { +"Downloaded" }
                th { +"File" }
            }
        }
        tbody {
            for (dl in downloads) {
                tr {
                    id = "download-${dl.uuid}"
                    td { +dl.title }
                    td {
                        val badgeClass = when (dl.status) {
                            EpisodeStatus.DOWNLOADING -> "badge-downloading"
                            EpisodeStatus.DOWNLOADED -> "badge-downloaded"
                            EpisodeStatus.ERROR -> "badge-error"
                            else -> "badge-approved"
                        }
                        span("badge $badgeClass") { +dl.status.name }
                        if (dl.uuid == currentDownloadUuid) {
                            +" "
                            span {
                                attributes["aria-busy"] = "true"
                                attributes["hx-get"] = "/downloads"
                                attributes["hx-trigger"] = "every 5s"
                                attributes["hx-target"] = "main"
                                attributes["hx-select"] = "main > *"
                            }
                        }
                    }
                    td { +formatInstant(dl.downloadedAt) }
                    td { small { +(dl.filePath ?: "") } }
                }
            }
        }
    }
}
