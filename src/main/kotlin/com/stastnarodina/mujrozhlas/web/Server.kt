package com.stastnarodina.mujrozhlas.web

import com.stastnarodina.mujrozhlas.*
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
    val downloadQueue = DownloadQueue(downloader, outputDir, scope)
    val discoverer = Discoverer(api, scope, downloadQueue)
    val urlSigner = UrlSigner(outputDir)

    val authUser = System.getenv("AUTH_USER")
    val authPass = System.getenv("AUTH_PASS")

    downloader.checkFfmpeg()
    downloadQueue.start()
    discoverer.start()

    embeddedServer(Netty, port = port) {
        install(StatusPages) {
            exception<Throwable> { call, cause ->
                serverLog.error("Unhandled error", cause)
                call.respondText("Error: ${cause.message}", status = HttpStatusCode.InternalServerError)
            }
        }

        if (authUser != null && authPass != null) {
            install(createApplicationPlugin("BasicAuth") {
                onCall { call ->
                    if (call.request.uri.startsWith("/dl/")) return@onCall

                    val authHeader = call.request.headers["Authorization"]
                    if (authHeader != null && authHeader.startsWith("Basic ")) {
                        val decoded = java.util.Base64.getDecoder()
                            .decode(authHeader.removePrefix("Basic "))
                            .toString(Charsets.UTF_8)
                        val parts = decoded.split(":", limit = 2)
                        if (parts.size == 2 && parts[0] == authUser && parts[1] == authPass) return@onCall
                    }

                    call.response.header("WWW-Authenticate", "Basic realm=\"mujrozhlas-dl\"")
                    call.respond(HttpStatusCode.Unauthorized)
                }
            })
        }

        routing {
            // --- Public: presigned file downloads ---

            get("/dl/{path...}") {
                val relativePath = call.parameters.getAll("path")?.joinToString("/") ?: ""
                val expires = call.request.queryParameters["e"]
                val signature = call.request.queryParameters["s"]

                val file = urlSigner.verify(relativePath, expires, signature)
                if (file == null) {
                    call.respond(HttpStatusCode.Forbidden, "Invalid or expired link")
                    return@get
                }

                call.response.header("Content-Disposition", "attachment; filename=\"${file.name}\"")
                call.respondFile(file)
            }

            // --- Dashboard ---

            get("/") {
                val shows = transaction {
                    val pendingCounts = Episodes
                        .select(Episodes.showUuid, Episodes.uuid.count())
                        .where { Episodes.status eq EpisodeStatus.PENDING }
                        .groupBy(Episodes.showUuid)
                        .associate { it[Episodes.showUuid] to it[Episodes.uuid.count()] }

                    val downloadedCounts = Episodes
                        .select(Episodes.showUuid, Episodes.uuid.count())
                        .where { Episodes.status eq EpisodeStatus.DOWNLOADED }
                        .groupBy(Episodes.showUuid)
                        .associate { it[Episodes.showUuid] to it[Episodes.uuid.count()] }

                    val serialCounts = Serials
                        .select(Serials.showUuid, Serials.uuid.count())
                        .groupBy(Serials.showUuid)
                        .associate { it[Serials.showUuid] to it[Serials.uuid.count()] }

                    val episodeCounts = Episodes
                        .select(Episodes.showUuid, Episodes.uuid.count())
                        .groupBy(Episodes.showUuid)
                        .associate { it[Episodes.showUuid] to it[Episodes.uuid.count()] }

                    Shows.selectAll()
                        .where { Shows.hidden eq false }
                        .orderBy(Shows.title)
                        .map { row ->
                            val uuid = row[Shows.uuid]
                            ShowRow(
                                uuid = uuid,
                                title = row[Shows.title],
                                serialCount = serialCounts[uuid]?.toInt() ?: 0,
                                episodeCount = episodeCounts[uuid]?.toInt() ?: 0,
                                pendingCount = pendingCounts[uuid]?.toInt() ?: 0,
                                downloadedCount = downloadedCounts[uuid]?.toInt() ?: 0,
                                subscribed = row[Shows.subscribed],
                                imageUrl = row[Shows.imageUrl],
                            )
                        }
                        .filter { it.pendingCount > 0 || it.downloadedCount > 0 || it.subscribed }
                }

                call.respondHtml {
                    layout("Dashboard - mujrozhlas-dl") {
                        dashboard(shows, discoverer.isDiscovering)
                    }
                }
            }

            // --- Show detail ---

            get("/shows/{uuid}") {
                val uuid = call.parameters["uuid"] ?: return@get call.respond(HttpStatusCode.BadRequest)

                val showData = transaction {
                    Shows.selectAll().where { Shows.uuid eq uuid }.firstOrNull()
                } ?: return@get call.respond(HttpStatusCode.NotFound, "Show not found")

                val serials = transaction {
                    Serials.selectAll().where { Serials.showUuid eq uuid }
                        .orderBy(Serials.title)
                        .map { row ->
                            val serialUuid = row[Serials.uuid]
                            val pending = Episodes.selectAll()
                                .where { (Episodes.serialUuid eq serialUuid) and (Episodes.status eq EpisodeStatus.PENDING) }
                                .count().toInt()
                            val downloaded = Episodes.selectAll()
                                .where { (Episodes.serialUuid eq serialUuid) and (Episodes.status eq EpisodeStatus.DOWNLOADED) }
                                .count().toInt()
                            val m4bPath = row[Serials.m4bPath]
                            SerialRow(
                                uuid = serialUuid,
                                showUuid = uuid,
                                title = row[Serials.title],
                                totalParts = row[Serials.totalParts],
                                pendingCount = pending,
                                downloadedCount = downloaded,
                                lastEpisodeSince = row[Serials.lastEpisodeSince],
                                m4bDownloadUrl = if (m4bPath != null) urlSigner.sign(File(m4bPath)) else null,
                            )
                        }
                }

                val directEpisodes = transaction {
                    Episodes.selectAll()
                        .where { (Episodes.showUuid eq uuid) and Episodes.serialUuid.isNull() }
                        .orderBy(Episodes.seriesEpisodeNumber)
                        .map { it.toEpisodeRow(urlSigner) }
                }

                val totalEpisodes = transaction {
                    Episodes.selectAll().where { Episodes.showUuid eq uuid }.count().toInt()
                }

                val showRow = ShowRow(
                    uuid = uuid,
                    title = showData[Shows.title],
                    serialCount = serials.size,
                    episodeCount = totalEpisodes,
                    pendingCount = serials.sumOf { it.pendingCount } + directEpisodes.count { it.status == EpisodeStatus.PENDING },
                    downloadedCount = serials.sumOf { it.downloadedCount } + directEpisodes.count { it.status == EpisodeStatus.DOWNLOADED },
                    subscribed = showData[Shows.subscribed],
                    imageUrl = showData[Shows.imageUrl],
                )

                call.respondHtml {
                    layout("${showRow.title} - mujrozhlas-dl") {
                        showDetail(showRow, serials, directEpisodes)
                    }
                }
            }

            // --- Serial detail ---

            get("/shows/{showUuid}/serials/{serialUuid}") {
                val serialUuid = call.parameters["serialUuid"] ?: return@get call.respond(HttpStatusCode.BadRequest)

                val serialData = transaction {
                    Serials.selectAll().where { Serials.uuid eq serialUuid }.firstOrNull()
                } ?: return@get call.respond(HttpStatusCode.NotFound, "Serial not found")

                val episodes = transaction {
                    Episodes.selectAll().where { Episodes.serialUuid eq serialUuid }
                        .orderBy(Episodes.part)
                        .map { it.toEpisodeRow(urlSigner) }
                }

                val m4bPath = serialData[Serials.m4bPath]
                val serialRow = SerialRow(
                    uuid = serialData[Serials.uuid],
                    showUuid = serialData[Serials.showUuid],
                    title = serialData[Serials.title],
                    totalParts = serialData[Serials.totalParts],
                    pendingCount = episodes.count { it.status == EpisodeStatus.PENDING },
                    downloadedCount = episodes.count { it.status == EpisodeStatus.DOWNLOADED },
                    lastEpisodeSince = serialData[Serials.lastEpisodeSince],
                    m4bDownloadUrl = if (m4bPath != null) urlSigner.sign(File(m4bPath)) else null,
                )

                call.respondHtml {
                    layout("${serialRow.title} - mujrozhlas-dl") {
                        serialDetail(serialRow, episodes)
                    }
                }
            }

            // --- Show actions ---

            post("/shows/add") {
                val params = call.receiveParameters()
                val url = params["url"]
                if (url.isNullOrBlank()) {
                    call.respondText("<p>Please provide a URL</p>", contentType = ContentType.Text.Html)
                    return@post
                }

                try {
                    val resolver = Resolver(api)
                    val result = resolver.resolve(url)

                    val show = when (result) {
                        is ResolvedResult.ShowResult -> result.show
                        is ResolvedResult.SerialResult -> {
                            val serial = result.serial
                            val showUuid = serial.showUuid
                                ?: throw RuntimeException("Serial has no parent show")
                            val parentShow = api.getShow(showUuid)
                            parentShow.copy(
                                serials = listOf(serial),
                                episodes = emptyList(),
                            )
                        }
                        is ResolvedResult.EpisodeResult -> {
                            val ep = result.episode
                            val showUuid = ep.showUuid
                                ?: throw RuntimeException("Episode has no parent show")
                            val parentShow = api.getShow(showUuid)
                            val serials = api.getShowSerials(showUuid).map { serial ->
                                serial.copy(episodes = api.getSerialEpisodes(serial.uuid))
                            }
                            val allShowEpisodes = api.getShowEpisodes(showUuid)
                            val serialEpUuids = serials.flatMap { s -> s.episodes.map { it.uuid } }.toSet()
                            parentShow.copy(
                                serials = serials,
                                episodes = allShowEpisodes.filter { it.uuid !in serialEpUuids },
                            )
                        }
                    }

                    insertShow(show)

                    val episodeCount = show.serials.sumOf { it.episodes.size } + show.episodes.size
                    val html = createHTML().article {
                        id = "show-${show.uuid}"
                        attributes["class"] = "show-card"
                        header {
                            div {
                                style = "display: flex; justify-content: space-between; align-items: center;"
                                a(href = "/shows/${show.uuid}") {
                                    strong { +show.title }
                                }
                                div {
                                    span("badge badge-pending") { +"$episodeCount pending" }
                                }
                            }
                        }
                        footer {
                            small {
                                if (show.serials.isNotEmpty()) +"${show.serials.size} serial(s) | "
                                +"$episodeCount episode(s) | Added manually"
                            }
                        }
                    }
                    call.respondText(html, contentType = ContentType.Text.Html)
                } catch (e: Exception) {
                    serverLog.error("Failed to add show from URL: $url", e)
                    call.respondText(
                        "<article><p>Error: ${e.message}</p></article>",
                        contentType = ContentType.Text.Html,
                    )
                }
            }

            post("/shows/{uuid}/hide") {
                val uuid = call.parameters["uuid"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                transaction {
                    Shows.update({ Shows.uuid eq uuid }) { it[hidden] = true }
                }
                call.respondText("", contentType = ContentType.Text.Html)
            }

            post("/shows/{uuid}/subscribe") {
                val uuid = call.parameters["uuid"] ?: return@post call.respond(HttpStatusCode.BadRequest)

                transaction {
                    Shows.update({ Shows.uuid eq uuid }) { it[subscribed] = true }
                }

                // Approve all pending episodes with audio
                val episodeUuids = transaction {
                    val uuids = Episodes.selectAll()
                        .where {
                            (Episodes.showUuid eq uuid) and
                                    (Episodes.status eq EpisodeStatus.PENDING) and
                                    (Episodes.duration greater 0)
                        }
                        .map { it[Episodes.uuid] }

                    Episodes.update({
                        (Episodes.showUuid eq uuid) and
                                (Episodes.status eq EpisodeStatus.PENDING) and
                                (Episodes.duration greater 0)
                    }) { it[status] = EpisodeStatus.APPROVED }

                    uuids
                }

                for (epUuid in episodeUuids) {
                    downloadQueue.enqueue(epUuid)
                }

                call.response.header("HX-Redirect", "/shows/$uuid")
                call.respondText("", contentType = ContentType.Text.Html)
            }

            post("/shows/{uuid}/approve-all") {
                val uuid = call.parameters["uuid"] ?: return@post call.respond(HttpStatusCode.BadRequest)

                val episodeUuids = transaction {
                    val uuids = Episodes.selectAll()
                        .where {
                            (Episodes.showUuid eq uuid) and
                                    Episodes.serialUuid.isNull() and
                                    (Episodes.status eq EpisodeStatus.PENDING)
                        }
                        .map { it[Episodes.uuid] }

                    Episodes.update({
                        (Episodes.showUuid eq uuid) and
                                Episodes.serialUuid.isNull() and
                                (Episodes.status eq EpisodeStatus.PENDING)
                    }) { it[status] = EpisodeStatus.APPROVED }

                    uuids
                }

                for (epUuid in episodeUuids) {
                    downloadQueue.enqueue(epUuid)
                }

                val episodes = transaction {
                    Episodes.selectAll()
                        .where { (Episodes.showUuid eq uuid) and Episodes.serialUuid.isNull() }
                        .orderBy(Episodes.seriesEpisodeNumber)
                        .map { it.toEpisodeRow(urlSigner) }
                }

                val html = createHTML().table {
                    id = "episode-list"
                    thead { tr { th { +"#" }; th { +"Title" }; th { +"Duration" }; th { +"Status" }; th { +"Actions" } } }
                    tbody { for (episode in episodes) { episodeRow(episode) } }
                }
                call.respondText(html, contentType = ContentType.Text.Html)
            }

            // --- Serial actions ---

            post("/serials/{uuid}/approve-all") {
                val uuid = call.parameters["uuid"] ?: return@post call.respond(HttpStatusCode.BadRequest)

                val episodeUuids = transaction {
                    val uuids = Episodes.selectAll()
                        .where { (Episodes.serialUuid eq uuid) and (Episodes.status eq EpisodeStatus.PENDING) }
                        .map { it[Episodes.uuid] }

                    Episodes.update({
                        (Episodes.serialUuid eq uuid) and (Episodes.status eq EpisodeStatus.PENDING)
                    }) { it[status] = EpisodeStatus.APPROVED }

                    uuids
                }

                for (epUuid in episodeUuids) {
                    downloadQueue.enqueue(epUuid)
                }

                val episodes = transaction {
                    Episodes.selectAll().where { Episodes.serialUuid eq uuid }
                        .orderBy(Episodes.part)
                        .map { it.toEpisodeRow(urlSigner) }
                }

                val html = createHTML().table {
                    id = "episode-list"
                    thead { tr { th { +"#" }; th { +"Title" }; th { +"Duration" }; th { +"Status" }; th { +"Actions" } } }
                    tbody { for (episode in episodes) { episodeRow(episode) } }
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
                            val num = row[Episodes.part] ?: row[Episodes.seriesEpisodeNumber] ?: 0
                            val padWidth = Downloader.digitCount(num)
                            DownloadedEpisode(
                                title = row[Episodes.title],
                                number = num,
                                duration = row[Episodes.duration],
                                m4aFile = File(
                                    File(outputDir, Downloader.sanitizeFilename(serialTitle)),
                                    "%0${padWidth}d - %s.m4a".format(num, Downloader.sanitizeFilename(serialTitle))
                                ),
                            )
                        }
                }

                if (episodes.isEmpty()) {
                    call.respondText("No downloaded episodes to combine", contentType = ContentType.Text.Html)
                    return@post
                }

                val missing = episodes.filter { !it.m4aFile.exists() }
                if (missing.isNotEmpty()) {
                    val names = missing.joinToString(", ") { it.m4aFile.name }
                    call.respondText("Missing m4a files: $names", contentType = ContentType.Text.Html)
                    return@post
                }

                try {
                    val serialDir = File(outputDir, Downloader.sanitizeFilename(serialTitle))

                    val coverFile = if (imageUrl != null) {
                        val file = File(serialDir, "cover.jpg")
                        try { downloader.downloadFile(imageUrl, file); file }
                        catch (e: Exception) { serverLog.warn("Cover image download failed: ${e.message}"); null }
                    } else null

                    val m4bFile = File(serialDir, "${Downloader.sanitizeFilename(serialTitle)}.m4b")
                    downloader.combineToM4b(episodes, serialTitle, coverFile, m4bFile)
                    transaction {
                        Serials.update({ Serials.uuid eq uuid }) { it[m4bPath] = m4bFile.absolutePath }
                    }

                    val dlUrl = urlSigner.sign(m4bFile)
                    val linkHtml = if (dlUrl != null) " <a href=\"$dlUrl\" download>Download</a>" else ""
                    call.respondText("M4B created: ${m4bFile.name}$linkHtml", contentType = ContentType.Text.Html)
                } catch (e: Exception) {
                    serverLog.error("M4B creation failed for $serialTitle", e)
                    call.respondText("Error: ${e.message}", contentType = ContentType.Text.Html)
                }
            }

            // --- Episode actions ---

            post("/episodes/{uuid}/approve") {
                val uuid = call.parameters["uuid"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                transaction {
                    Episodes.update({ Episodes.uuid eq uuid }) { it[status] = EpisodeStatus.APPROVED }
                }
                downloadQueue.enqueue(uuid)
                respondEpisodeRow(call, uuid, urlSigner)
            }

            post("/episodes/{uuid}/skip") {
                val uuid = call.parameters["uuid"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                transaction {
                    Episodes.update({ Episodes.uuid eq uuid }) { it[status] = EpisodeStatus.SKIPPED }
                }
                respondEpisodeRow(call, uuid, urlSigner)
            }

            get("/episodes/{uuid}/status") {
                val uuid = call.parameters["uuid"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                respondEpisodeRow(call, uuid, urlSigner)
            }

            // --- Discovery ---

            post("/discover") {
                discoverer.discoverNow()
                val html = createHTML().span {
                    id = "discover-status"
                    attributes["hx-get"] = "/discover/status"
                    attributes["hx-trigger"] = "every 2s"
                    attributes["hx-swap"] = "outerHTML"
                    +"Discovering..."
                    +" "
                    span { attributes["aria-busy"] = "true" }
                }
                call.respondText(html, contentType = ContentType.Text.Html)
            }

            get("/discover/status") {
                if (discoverer.isDiscovering) {
                    val html = createHTML().span {
                        id = "discover-status"
                        attributes["hx-get"] = "/discover/status"
                        attributes["hx-trigger"] = "every 2s"
                        attributes["hx-swap"] = "outerHTML"
                        +"Discovering..."
                        +" "
                        span { attributes["aria-busy"] = "true" }
                    }
                    call.respondText(html, contentType = ContentType.Text.Html)
                } else {
                    call.response.header("HX-Refresh", "true")
                    call.respondText("", contentType = ContentType.Text.Html)
                }
            }

            // --- Downloads ---

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
                        .map { it.toEpisodeRow(urlSigner) }
                }

                call.respondHtml {
                    layout("Downloads - mujrozhlas-dl") {
                        downloadsPage(downloads, downloadQueue.currentDownload)
                    }
                }
            }
        }
    }.start(wait = true)
}

// --- Helpers ---

/** Insert a fully-populated Show (with serials and episodes) into the database. */
private fun insertShow(show: Show) {
    transaction {
        val showExists = Shows.selectAll().where { Shows.uuid eq show.uuid }.count() > 0
        if (!showExists) {
            Shows.insert {
                it[uuid] = show.uuid
                it[title] = show.title
                it[imageUrl] = show.imageUrl
                it[lastScanned] = Instant.now()
            }
        }

        for (serial in show.serials) {
            val serialExists = Serials.selectAll().where { Serials.uuid eq serial.uuid }.count() > 0
            if (!serialExists) {
                Serials.insert {
                    it[uuid] = serial.uuid
                    it[showUuid] = show.uuid
                    it[title] = serial.title
                    it[totalParts] = serial.totalParts
                    it[lastEpisodeSince] = serial.lastEpisodeSince
                    it[imageUrl] = serial.imageUrl
                }
            }
            for (ep in serial.episodes) {
                insertEpisode(ep, show.uuid, serial.uuid)
            }
        }

        for (ep in show.episodes) {
            insertEpisode(ep, show.uuid, null)
        }
    }
}

private fun insertEpisode(ep: Episode, showUuid: String, serialUuid: String?) {
    val exists = Episodes.selectAll().where { Episodes.uuid eq ep.uuid }.count() > 0
    if (exists) return

    val hlsLink = ep.audioLinks.firstOrNull { it.variant == "hls" }
    Episodes.insert {
        it[uuid] = ep.uuid
        it[Episodes.showUuid] = showUuid
        it[Episodes.serialUuid] = serialUuid
        it[title] = ep.title
        it[part] = ep.part
        it[seriesEpisodeNumber] = ep.seriesEpisodeNumber
        it[status] = EpisodeStatus.PENDING
        it[hlsUrl] = hlsLink?.url
        it[duration] = hlsLink?.duration ?: 0
        it[playableTill] = hlsLink?.playableTill
        it[discoveredAt] = Instant.now()
    }
}

private suspend fun respondEpisodeRow(call: ApplicationCall, uuid: String, urlSigner: UrlSigner) {
    val episode = transaction {
        Episodes.selectAll().where { Episodes.uuid eq uuid }.firstOrNull()?.toEpisodeRow(urlSigner)
    }
    if (episode == null) {
        call.respond(HttpStatusCode.NotFound)
        return
    }
    val html = createHTML().tr {
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

private fun ResultRow.toEpisodeRow(urlSigner: UrlSigner? = null): EpisodeRow {
    val path = this[Episodes.filePath]
    val dlUrl = if (path != null && urlSigner != null) urlSigner.sign(File(path)) else null
    return EpisodeRow(
        uuid = this[Episodes.uuid],
        title = this[Episodes.title],
        number = this[Episodes.part] ?: this[Episodes.seriesEpisodeNumber] ?: 0,
        status = this[Episodes.status],
        duration = this[Episodes.duration],
        playableTill = this[Episodes.playableTill],
        discoveredAt = this[Episodes.discoveredAt],
        downloadedAt = this[Episodes.downloadedAt],
        filePath = this[Episodes.filePath],
        downloadUrl = dlUrl,
        errorMessage = this[Episodes.errorMessage],
    )
}
