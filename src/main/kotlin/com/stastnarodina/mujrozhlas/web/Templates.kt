package com.stastnarodina.mujrozhlas.web

import kotlinx.html.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// --- View data classes ---

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
    val number: Int,
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

fun MAIN.dashboard(shows: List<ShowRow>, isDiscovering: Boolean) {
    div {
        style = "display: flex; justify-content: space-between; align-items: center; margin-bottom: 1rem;"
        h1 { +"Dashboard" }
        div {
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
                    +"Discover Now"
                }
                span { id = "discover-status" }
            }
        }
    }

    // URL input
    form {
        attributes["hx-post"] = "/shows/add"
        attributes["hx-target"] = "#show-list"
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
        id = "show-search"
        placeholder = "Filter shows..."
        attributes["oninput"] = "filterShows(this.value)"
        style = "margin-bottom: 1rem;"
    }

    div {
        id = "show-list"
        if (shows.isEmpty()) {
            p { +"No shows tracked yet. Add a show URL above or click 'Discover Now'." }
        } else {
            for (show in shows) {
                showCard(show)
            }
        }
    }

    script {
        unsafe {
            +"""
            function normalize(s) {
                return s.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
            }
            function filterShows(query) {
                var q = normalize(query);
                document.querySelectorAll('#show-list .show-card').forEach(function(card) {
                    var title = normalize(card.textContent);
                    card.style.display = title.includes(q) ? '' : 'none';
                });
            }
            """.trimIndent()
        }
    }
}

fun FlowContent.showCard(show: ShowRow) {
    article {
        id = "show-${show.uuid}"
        attributes["class"] = "show-card"
        header {
            div {
                style = "display: flex; justify-content: space-between; align-items: center;"
                a(href = "/shows/${show.uuid}") {
                    strong { +show.title }
                }
                div {
                    if (show.subscribed) {
                        span("badge badge-subscribed") { +"subscribed" }
                        +" "
                    }
                    if (show.pendingCount > 0) {
                        span("badge badge-pending") { +"${show.pendingCount} pending" }
                        +" "
                    }
                    if (show.downloadedCount > 0) {
                        span("badge badge-downloaded") { +"${show.downloadedCount} downloaded" }
                        +" "
                    }
                    if (!show.subscribed) {
                        button {
                            attributes["class"] = "outline"
                            attributes["hx-post"] = "/shows/${show.uuid}/subscribe"
                            style = "margin-left: 0.5rem; padding: 4px 12px; font-size: 0.85em;"
                            +"Subscribe"
                        }
                    }
                    button {
                        attributes["class"] = "outline secondary"
                        attributes["hx-post"] = "/shows/${show.uuid}/hide"
                        attributes["hx-target"] = "#show-${show.uuid}"
                        attributes["hx-swap"] = "outerHTML"
                        attributes["hx-confirm"] = "Hide '${show.title}' from dashboard?"
                        style = "margin-left: 0.5rem; padding: 4px 12px; font-size: 0.85em;"
                        +"Hide"
                    }
                }
            }
        }
        footer {
            small {
                if (show.serialCount > 0) +"${show.serialCount} serial(s) | "
                +"${show.episodeCount} episode(s)"
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
                span("badge badge-subscribed") { +"subscribed" }
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
                    a(href = "/shows/${show.uuid}/serials/${serial.uuid}") {
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

fun MAIN.serialDetail(serial: SerialRow, episodes: List<EpisodeRow>) {
    div {
        style = "display: flex; justify-content: space-between; align-items: center;"
        h1 { +serial.title }
        div {
            val downloadedCount = episodes.count { it.status == EpisodeStatus.DOWNLOADED }
            if (downloadedCount > 0) {
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
        td { +"${episode.number}" }
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
