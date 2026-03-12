package com.stastnarodina.mujrozhlas.web

import kotlinx.html.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// --- View data classes ---

data class SerialRow(
    val uuid: String,
    val title: String,
    val totalParts: Int,
    val pendingCount: Int,
    val downloadedCount: Int,
    val lastEpisodeSince: String?,
    val subscribed: Boolean = false,
)

data class EpisodeRow(
    val uuid: String,
    val title: String,
    val part: Int,
    val status: EpisodeStatus,
    val duration: Int,
    val playableTill: String?,
    val discoveredAt: Instant,
    val downloadedAt: Instant?,
    val filePath: String?,
    val errorMessage: String?,
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
                .serial-card { margin-bottom: 1rem; }
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

fun MAIN.dashboard(serials: List<SerialRow>, isScanning: Boolean) {
    div {
        style = "display: flex; justify-content: space-between; align-items: center; margin-bottom: 1rem;"
        h1 { +"Dashboard" }
        div {
            if (isScanning) {
                span {
                    id = "scan-status"
                    attributes["hx-get"] = "/scan/status"
                    attributes["hx-trigger"] = "every 2s"
                    attributes["hx-swap"] = "outerHTML"
                    +"Scanning..."
                    span { attributes["aria-busy"] = "true" }
                }
            } else {
                button {
                    attributes["hx-post"] = "/scan"
                    attributes["hx-target"] = "#scan-status"
                    attributes["hx-swap"] = "outerHTML"
                    +"Scan Now"
                }
                span { id = "scan-status" }
            }
        }
    }

    // Manual URL input
    form {
        attributes["hx-post"] = "/serials/add"
        attributes["hx-target"] = "#serial-list"
        attributes["hx-swap"] = "afterbegin"
        role = "group"
        input {
            type = InputType.url
            name = "url"
            placeholder = "Paste mujrozhlas.cz URL to add manually..."
        }
        button { type = ButtonType.submit; +"Add" }
    }

    div {
        id = "serial-list"
        if (serials.isEmpty()) {
            p { +"No serials with pending episodes. Click 'Scan Now' to check for new content." }
        } else {
            for (serial in serials) {
                serialCard(serial)
            }
        }
    }
}

fun FlowContent.serialCard(serial: SerialRow) {
    article {
        id = "serial-${serial.uuid}"
        attributes["class"] = "serial-card"
        header {
            div {
                style = "display: flex; justify-content: space-between; align-items: center;"
                a(href = "/serials/${serial.uuid}") {
                    strong { +serial.title }
                }
                div {
                    if (serial.subscribed) {
                        span("badge badge-subscribed") { +"subscribed" }
                        +" "
                    }
                    if (serial.pendingCount > 0) {
                        span("badge badge-pending") { +"${serial.pendingCount} pending" }
                        +" "
                    }
                    if (serial.downloadedCount > 0) {
                        span("badge badge-downloaded") { +"${serial.downloadedCount} downloaded" }
                        +" "
                    }
                    if (!serial.subscribed) {
                        button {
                            attributes["class"] = "outline"
                            attributes["hx-post"] = "/serials/${serial.uuid}/subscribe"
                            style = "margin-left: 0.5rem; padding: 4px 12px; font-size: 0.85em;"
                            +"Download Series"
                        }
                    }
                    button {
                        attributes["class"] = "outline secondary"
                        attributes["hx-post"] = "/serials/${serial.uuid}/hide"
                        attributes["hx-target"] = "#serial-${serial.uuid}"
                        attributes["hx-swap"] = "outerHTML"
                        attributes["hx-confirm"] = "Hide '${serial.title}' from dashboard?"
                        style = "margin-left: 0.5rem; padding: 4px 12px; font-size: 0.85em;"
                        +"Hide"
                    }
                }
            }
        }
        footer {
            small {
                +"${serial.totalParts} parts total"
                serial.lastEpisodeSince?.let { +" | Last episode: $it" }
            }
        }
    }
}

// --- Serial detail ---

fun MAIN.serialDetail(serial: SerialRow, episodes: List<EpisodeRow>) {
    div {
        style = "display: flex; justify-content: space-between; align-items: center;"
        h1 { +serial.title }
        div {
            val pendingCount = episodes.count { it.status == EpisodeStatus.PENDING }
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
            if (!serial.subscribed && pendingCount > 0) {
                button {
                    attributes["hx-post"] = "/serials/${serial.uuid}/subscribe"
                    style = "margin-right: 0.5rem;"
                    +"Download Series"
                }
            }
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
        if (serial.subscribed) +" | Subscribed"
        serial.lastEpisodeSince?.let { +" | Last episode: $it" }
    }

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
        td { +"${episode.part}" }
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
