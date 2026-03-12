package com.stastnarodina.mujrozhlas.web

import com.stastnarodina.mujrozhlas.Api
import com.stastnarodina.mujrozhlas.DownloadedEpisode
import com.stastnarodina.mujrozhlas.Downloader
import com.stastnarodina.mujrozhlas.Resolver
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.html.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.html.*
import kotlinx.html.stream.createHTML
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant

private val serverLog = LoggerFactory.getLogger("com.stastnarodina.mujrozhlas.web.Server")

fun startServer(port: Int, outputDir: File, dbPath: String) {
    initDatabase(dbPath)

    val api = Api()
    val downloader = Downloader()
    val scope = CoroutineScope(SupervisorJob())
    val scanner = Scanner(api, scope)
    val downloadQueue = DownloadQueue(downloader, outputDir, scope)

    downloader.checkFfmpeg()
    downloadQueue.start()
    scanner.start()

    embeddedServer(Netty, port = port) {
        install(StatusPages) {
            exception<Throwable> { call, cause ->
                serverLog.error("Unhandled error", cause)
                call.respondText("Error: ${cause.message}", status = HttpStatusCode.InternalServerError)
            }
        }

        routing {
            get("/") {
                val serials = transaction {
                    val pendingCounts = Episodes
                        .select(Episodes.serialUuid, Episodes.uuid.count())
                        .where { Episodes.status eq EpisodeStatus.PENDING }
                        .groupBy(Episodes.serialUuid)
                        .associate { it[Episodes.serialUuid] to it[Episodes.uuid.count()] }

                    val downloadedCounts = Episodes
                        .select(Episodes.serialUuid, Episodes.uuid.count())
                        .where { Episodes.status eq EpisodeStatus.DOWNLOADED }
                        .groupBy(Episodes.serialUuid)
                        .associate { it[Episodes.serialUuid] to it[Episodes.uuid.count()] }

                    Serials.selectAll()
                        .where { (Serials.hidden eq false) }
                        .orderBy(Serials.lastEpisodeSince, SortOrder.DESC_NULLS_LAST)
                        .map { row ->
                            val uuid = row[Serials.uuid]
                            SerialRow(
                                uuid = uuid,
                                title = row[Serials.title],
                                totalParts = row[Serials.totalParts],
                                pendingCount = pendingCounts[uuid]?.toInt() ?: 0,
                                downloadedCount = downloadedCounts[uuid]?.toInt() ?: 0,
                                lastEpisodeSince = row[Serials.lastEpisodeSince],
                            )
                        }
                        .filter { it.pendingCount > 0 || it.downloadedCount > 0 }
                }

                call.respondHtml {
                    layout("Dashboard - mujrozhlas-dl") {
                        dashboard(serials, scanner.isScanning)
                    }
                }
            }

            get("/serials/{uuid}") {
                val uuid = call.parameters["uuid"] ?: return@get call.respond(HttpStatusCode.BadRequest)

                val serial = transaction {
                    Serials.selectAll().where { Serials.uuid eq uuid }.firstOrNull()
                } ?: return@get call.respond(HttpStatusCode.NotFound, "Serial not found")

                val episodes = transaction {
                    Episodes.selectAll().where { Episodes.serialUuid eq uuid }
                        .orderBy(Episodes.part)
                        .map { it.toEpisodeRow() }
                }

                val serialRow = SerialRow(
                    uuid = serial[Serials.uuid],
                    title = serial[Serials.title],
                    totalParts = serial[Serials.totalParts],
                    pendingCount = episodes.count { it.status == EpisodeStatus.PENDING },
                    downloadedCount = episodes.count { it.status == EpisodeStatus.DOWNLOADED },
                    lastEpisodeSince = serial[Serials.lastEpisodeSince],
                )

                call.respondHtml {
                    layout("${serialRow.title} - mujrozhlas-dl") {
                        serialDetail(serialRow, episodes)
                    }
                }
            }

            post("/serials/{uuid}/hide") {
                val uuid = call.parameters["uuid"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                transaction {
                    Serials.update({ Serials.uuid eq uuid }) {
                        it[hidden] = true
                    }
                }
                call.respondText("", contentType = ContentType.Text.Html)
            }

            post("/serials/{uuid}/approve-all") {
                val uuid = call.parameters["uuid"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val episodeUuids = transaction {
                    val uuids = Episodes.selectAll()
                        .where { (Episodes.serialUuid eq uuid) and (Episodes.status eq EpisodeStatus.PENDING) }
                        .map { it[Episodes.uuid] }

                    Episodes.update({ (Episodes.serialUuid eq uuid) and (Episodes.status eq EpisodeStatus.PENDING) }) {
                        it[status] = EpisodeStatus.APPROVED
                    }
                    uuids
                }

                for (epUuid in episodeUuids) {
                    downloadQueue.enqueue(epUuid)
                }

                // Return updated episode table body
                val episodes = transaction {
                    Episodes.selectAll().where { Episodes.serialUuid eq uuid }
                        .orderBy(Episodes.part)
                        .map { it.toEpisodeRow() }
                }

                val html = createHTML().table {
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
                call.respondText(html, contentType = ContentType.Text.Html)
            }

            post("/serials/{uuid}/combine-m4b") {
                val uuid = call.parameters["uuid"] ?: return@post call.respond(HttpStatusCode.BadRequest)

                val serialData = transaction {
                    Serials.selectAll().where { Serials.uuid eq uuid }.firstOrNull()
                } ?: return@post call.respond(HttpStatusCode.NotFound, "Serial not found")

                val serialTitle = serialData[Serials.title]
                val imageUrl = serialData[Serials.imageUrl]

                val episodes = transaction {
                    Episodes.selectAll()
                        .where { (Episodes.serialUuid eq uuid) and (Episodes.status eq EpisodeStatus.DOWNLOADED) }
                        .orderBy(Episodes.part)
                        .map { row ->
                            DownloadedEpisode(
                                title = row[Episodes.title],
                                part = row[Episodes.part],
                                duration = row[Episodes.duration],
                                m4aFile = java.io.File(
                                    java.io.File(outputDir, Downloader.sanitizeFilename(serialTitle)),
                                    "%02d - %s.m4a".format(row[Episodes.part], Downloader.sanitizeFilename(serialTitle))
                                ),
                            )
                        }
                }

                if (episodes.isEmpty()) {
                    call.respondText("No downloaded episodes to combine", contentType = ContentType.Text.Html)
                    return@post
                }

                // Check that all m4a files exist
                val missing = episodes.filter { !it.m4aFile.exists() }
                if (missing.isNotEmpty()) {
                    val names = missing.joinToString(", ") { it.m4aFile.name }
                    call.respondText("Missing m4a files: $names", contentType = ContentType.Text.Html)
                    return@post
                }

                try {
                    val serialDir = java.io.File(outputDir, Downloader.sanitizeFilename(serialTitle))

                    // Download cover image
                    val coverFile = if (imageUrl != null) {
                        val file = java.io.File(serialDir, "cover.jpg")
                        try {
                            downloader.downloadFile(imageUrl, file)
                            file
                        } catch (e: Exception) {
                            serverLog.warn("Cover image download failed: ${e.message}")
                            null
                        }
                    } else null

                    val m4bFile = java.io.File(serialDir, "${Downloader.sanitizeFilename(serialTitle)}.m4b")
                    downloader.combineToM4b(episodes, serialTitle, coverFile, m4bFile)

                    call.respondText("M4B created: ${m4bFile.name}", contentType = ContentType.Text.Html)
                } catch (e: Exception) {
                    serverLog.error("M4B creation failed for $serialTitle", e)
                    call.respondText("Error: ${e.message}", contentType = ContentType.Text.Html)
                }
            }

            post("/episodes/{uuid}/approve") {
                val uuid = call.parameters["uuid"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                transaction {
                    Episodes.update({ Episodes.uuid eq uuid }) {
                        it[status] = EpisodeStatus.APPROVED
                    }
                }
                downloadQueue.enqueue(uuid)

                respondEpisodeRow(call, uuid)
            }

            post("/episodes/{uuid}/skip") {
                val uuid = call.parameters["uuid"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                transaction {
                    Episodes.update({ Episodes.uuid eq uuid }) {
                        it[status] = EpisodeStatus.SKIPPED
                    }
                }
                respondEpisodeRow(call, uuid)
            }

            get("/episodes/{uuid}/status") {
                val uuid = call.parameters["uuid"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                respondEpisodeRow(call, uuid)
            }

            post("/scan") {
                scanner.scanNow()
                val html = createHTML().span {
                    id = "scan-status"
                    attributes["hx-get"] = "/scan/status"
                    attributes["hx-trigger"] = "every 2s"
                    attributes["hx-swap"] = "outerHTML"
                    +"Scanning..."
                    +" "
                    span { attributes["aria-busy"] = "true" }
                }
                call.respondText(html, contentType = ContentType.Text.Html)
            }

            get("/scan/status") {
                if (scanner.isScanning) {
                    val html = createHTML().span {
                        id = "scan-status"
                        attributes["hx-get"] = "/scan/status"
                        attributes["hx-trigger"] = "every 2s"
                        attributes["hx-swap"] = "outerHTML"
                        +"Scanning..."
                        +" "
                        span { attributes["aria-busy"] = "true" }
                    }
                    call.respondText(html, contentType = ContentType.Text.Html)
                } else {
                    // Scan done — tell client to reload the page
                    call.response.header("HX-Refresh", "true")
                    call.respondText("", contentType = ContentType.Text.Html)
                }
            }

            get("/downloads") {
                val downloads = transaction {
                    Episodes.selectAll()
                        .where {
                            Episodes.status inList listOf(
                                EpisodeStatus.APPROVED,
                                EpisodeStatus.DOWNLOADING,
                                EpisodeStatus.DOWNLOADED,
                                EpisodeStatus.ERROR,
                            )
                        }
                        .orderBy(Episodes.discoveredAt, SortOrder.DESC)
                        .map { it.toEpisodeRow() }
                }

                call.respondHtml {
                    layout("Downloads - mujrozhlas-dl") {
                        downloadsPage(downloads, downloadQueue.currentDownload)
                    }
                }
            }

            post("/serials/add") {
                val params = call.receiveParameters()
                val url = params["url"]
                if (url.isNullOrBlank()) {
                    call.respondText("<p>Please provide a URL</p>", contentType = ContentType.Text.Html)
                    return@post
                }

                try {
                    val resolver = Resolver(api)
                    val result = resolver.resolve(url)

                    val serial = when (result) {
                        is com.stastnarodina.mujrozhlas.ResolvedResult.SerialResult -> result.serial
                        is com.stastnarodina.mujrozhlas.ResolvedResult.EpisodeResult -> {
                            val ep = result.episode
                            if (ep.serialUuid != null) {
                                val s = api.getSerial(ep.serialUuid)
                                val episodes = api.getSerialEpisodes(ep.serialUuid)
                                s.copy(episodes = episodes)
                            } else {
                                call.respondText(
                                    "<p>Episode does not belong to a serial</p>",
                                    contentType = ContentType.Text.Html
                                )
                                return@post
                            }
                        }
                    }

                    // Insert serial + episodes
                    transaction {
                        val exists = Serials.selectAll().where { Serials.uuid eq serial.uuid }.count() > 0
                        if (!exists) {
                            Serials.insert {
                                it[Serials.uuid] = serial.uuid
                                it[title] = serial.title
                                it[totalParts] = serial.totalParts
                                it[lastEpisodeSince] = serial.lastEpisodeSince
                                it[imageUrl] = serial.imageUrl
                                it[lastScanned] = Instant.now()
                            }
                        }
                        for (ep in serial.episodes) {
                            val epExists = Episodes.selectAll().where { Episodes.uuid eq ep.uuid }.count() > 0
                            if (!epExists) {
                                val hlsLink = ep.audioLinks.firstOrNull { it.variant == "hls" }
                                Episodes.insert {
                                    it[Episodes.uuid] = ep.uuid
                                    it[serialUuid] = serial.uuid
                                    it[title] = ep.title
                                    it[part] = ep.part
                                    it[status] = EpisodeStatus.PENDING
                                    it[hlsUrl] = hlsLink?.url
                                    it[duration] = hlsLink?.duration ?: 0
                                    it[playableTill] = hlsLink?.playableTill
                                    it[discoveredAt] = Instant.now()
                                }
                            }
                        }
                    }

                    val pendingCount = serial.episodes.size
                    val serialRow = SerialRow(
                        uuid = serial.uuid,
                        title = serial.title,
                        totalParts = serial.totalParts,
                        pendingCount = pendingCount,
                        downloadedCount = 0,
                        lastEpisodeSince = serial.lastEpisodeSince,
                    )
                    val html = createHTML().article {
                        id = "serial-${serial.uuid}"
                        attributes["class"] = "serial-card"
                        header {
                            div {
                                style = "display: flex; justify-content: space-between; align-items: center;"
                                a(href = "/serials/${serial.uuid}") {
                                    strong { +serial.title }
                                }
                                div {
                                    span("badge badge-pending") { +"$pendingCount pending" }
                                }
                            }
                        }
                        footer {
                            small { +"${serial.totalParts} parts total | Added manually" }
                        }
                    }
                    call.respondText(html, contentType = ContentType.Text.Html)
                } catch (e: Exception) {
                    serverLog.error("Failed to add serial from URL: $url", e)
                    call.respondText(
                        "<article><p>Error: ${e.message}</p></article>",
                        contentType = ContentType.Text.Html,
                    )
                }
            }
        }
    }.start(wait = true)
}

private suspend fun respondEpisodeRow(call: ApplicationCall, uuid: String) {
    val episode = transaction {
        Episodes.selectAll().where { Episodes.uuid eq uuid }.firstOrNull()?.toEpisodeRow()
    }
    if (episode == null) {
        call.respond(HttpStatusCode.NotFound)
        return
    }
    val html = createHTML().tr {
        id = "episode-${episode.uuid}"
        // Re-render using the same structure as episodeRow but inline since we need <tr> at top level
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
    call.respondText(html, contentType = ContentType.Text.Html)
}

private fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "${m}m ${s}s"
}

private fun formatInstant(instant: Instant?): String {
    if (instant == null) return ""
    val formatter = java.time.format.DateTimeFormatter.ofPattern("d.M.yyyy HH:mm")
        .withZone(java.time.ZoneId.systemDefault())
    return formatter.format(instant)
}

private fun ResultRow.toEpisodeRow() = EpisodeRow(
    uuid = this[Episodes.uuid],
    title = this[Episodes.title],
    part = this[Episodes.part],
    status = this[Episodes.status],
    duration = this[Episodes.duration],
    playableTill = this[Episodes.playableTill],
    discoveredAt = this[Episodes.discoveredAt],
    downloadedAt = this[Episodes.downloadedAt],
    filePath = this[Episodes.filePath],
    errorMessage = this[Episodes.errorMessage],
)
