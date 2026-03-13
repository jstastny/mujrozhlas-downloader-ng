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
import kotlinx.coroutines.runBlocking
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

    val server = embeddedServer(Netty, port = port) {
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
                val units = transaction {
                    val results = mutableListOf<ContentUnit>()

                    // Shows that have serials (container shows)
                    val showsWithSerials = Serials
                        .select(Serials.showUuid)
                        .withDistinct()
                        .map { it[Serials.showUuid] }
                        .toSet()

                    // 1) Shows WITHOUT serials → appear directly as content units
                    Shows.selectAll()
                        .where { (Shows.hidden eq false) and (Shows.uuid notInList showsWithSerials) }
                        .orderBy(Shows.title)
                        .forEach { row ->
                            val uuid = row[Shows.uuid]
                            val epCount = Episodes.selectAll()
                                .where { Episodes.showUuid eq uuid }
                                .count().toInt()
                            val pending = Episodes.selectAll()
                                .where { (Episodes.showUuid eq uuid) and (Episodes.status eq EpisodeStatus.PENDING) }
                                .count().toInt()
                            val downloaded = Episodes.selectAll()
                                .where { (Episodes.showUuid eq uuid) and (Episodes.status eq EpisodeStatus.DOWNLOADED) }
                                .count().toInt()
                            results.add(ContentUnit(
                                uuid = uuid,
                                title = row[Shows.title],
                                type = ContentUnitType.SHOW,
                                episodeCount = epCount,
                                pendingCount = pending,
                                downloadedCount = downloaded,
                                subscribed = row[Shows.subscribed],
                                imageUrl = row[Shows.imageUrl],
                                detailUrl = "/shows/$uuid",
                            ))
                        }

                    // 2) Serials → each appears as its own content unit
                    (Serials innerJoin Shows)
                        .selectAll()
                        .where { Shows.hidden eq false }
                        .orderBy(Serials.title)
                        .forEach { row ->
                            val serialUuid = row[Serials.uuid]
                            val epCount = Episodes.selectAll()
                                .where { Episodes.serialUuid eq serialUuid }
                                .count().toInt()
                            val pending = Episodes.selectAll()
                                .where { (Episodes.serialUuid eq serialUuid) and (Episodes.status eq EpisodeStatus.PENDING) }
                                .count().toInt()
                            val downloaded = Episodes.selectAll()
                                .where { (Episodes.serialUuid eq serialUuid) and (Episodes.status eq EpisodeStatus.DOWNLOADED) }
                                .count().toInt()
                            results.add(ContentUnit(
                                uuid = serialUuid,
                                title = row[Serials.title],
                                type = ContentUnitType.SERIAL,
                                episodeCount = epCount,
                                pendingCount = pending,
                                downloadedCount = downloaded,
                                subscribed = row[Shows.subscribed],
                                imageUrl = row[Serials.imageUrl] ?: row[Shows.imageUrl],
                                parentShowTitle = row[Shows.title],
                                parentShowUuid = row[Shows.uuid],
                                detailUrl = "/serials/$serialUuid",
                            ))
                        }

                    results.sortedBy { it.title }
                }

                call.respondHtml {
                    layout("Dashboard - mujrozhlas-dl") {
                        dashboard(units, discoverer.isDiscovering)
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

            get("/serials/{serialUuid}") {
                val serialUuid = call.parameters["serialUuid"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                respondSerialDetail(call, serialUuid, urlSigner)
            }

            // Keep old URL working for backward compat
            get("/shows/{showUuid}/serials/{serialUuid}") {
                val serialUuid = call.parameters["serialUuid"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                respondSerialDetail(call, serialUuid, urlSigner)
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
                    val input = url.trim()
                    val uuidRegex = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
                    val result = if (uuidRegex.matches(input)) {
                        resolveByUuid(api, input)
                    } else {
                        Resolver(api).resolve(input)
                    }

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

                    // Build content unit cards for each added item
                    val html = buildString {
                        if (show.serials.isNotEmpty()) {
                            // Serials → each gets its own card
                            for (serial in show.serials) {
                                append(createHTML().div {
                                    contentUnitCard(ContentUnit(
                                        uuid = serial.uuid,
                                        title = serial.title,
                                        type = ContentUnitType.SERIAL,
                                        episodeCount = serial.episodes.size,
                                        pendingCount = serial.episodes.size,
                                        downloadedCount = 0,
                                        subscribed = false,
                                        imageUrl = serial.imageUrl ?: show.imageUrl,
                                        parentShowTitle = show.title,
                                        parentShowUuid = show.uuid,
                                        detailUrl = "/serials/${serial.uuid}",
                                    ))
                                })
                            }
                        }
                        if (show.episodes.isNotEmpty()) {
                            // Direct episodes → show card
                            append(createHTML().div {
                                contentUnitCard(ContentUnit(
                                    uuid = show.uuid,
                                    title = show.title,
                                    type = ContentUnitType.SHOW,
                                    episodeCount = show.episodes.size,
                                    pendingCount = show.episodes.size,
                                    downloadedCount = 0,
                                    subscribed = false,
                                    imageUrl = show.imageUrl,
                                    detailUrl = "/shows/${show.uuid}",
                                ))
                            })
                        }
                        if (show.serials.isEmpty() && show.episodes.isEmpty()) {
                            // Show with no content yet
                            append(createHTML().div {
                                contentUnitCard(ContentUnit(
                                    uuid = show.uuid,
                                    title = show.title,
                                    type = ContentUnitType.SHOW,
                                    episodeCount = 0,
                                    pendingCount = 0,
                                    downloadedCount = 0,
                                    subscribed = false,
                                    imageUrl = show.imageUrl,
                                    detailUrl = "/shows/${show.uuid}",
                                ))
                            })
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
                                    (Episodes.status eq EpisodeStatus.PENDING) and
                                    (Episodes.duration greater 0)
                        }
                        .map { it[Episodes.uuid] }

                    Episodes.update({
                        (Episodes.showUuid eq uuid) and
                                Episodes.serialUuid.isNull() and
                                (Episodes.status eq EpisodeStatus.PENDING) and
                                (Episodes.duration greater 0)
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
                        .where {
                            (Episodes.serialUuid eq uuid) and
                                    (Episodes.status eq EpisodeStatus.PENDING) and
                                    (Episodes.duration greater 0)
                        }
                        .map { it[Episodes.uuid] }

                    Episodes.update({
                        (Episodes.serialUuid eq uuid) and
                                (Episodes.status eq EpisodeStatus.PENDING) and
                                (Episodes.duration greater 0)
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

                val totalParts = serialData[Serials.totalParts]
                if (totalParts > MAX_M4B_EPISODES) {
                    call.respondText(
                        "Too many episodes ($totalParts) for M4B creation (max $MAX_M4B_EPISODES)",
                        contentType = ContentType.Text.Html,
                    )
                    return@post
                }

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
                    Episodes.update({ Episodes.uuid eq uuid }) {
                        it[status] = EpisodeStatus.APPROVED
                        it[errorMessage] = null
                    }
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

            // --- Search ---

            get("/search") {
                call.respondHtml {
                    layout("Search - mujrozhlas-dl") {
                        searchPage()
                    }
                }
            }

            get("/search/results") {
                val query = call.request.queryParameters["q"]?.trim() ?: ""
                if (query.length < 2) {
                    call.respondText("", contentType = ContentType.Text.Html)
                    return@get
                }

                val knownShowUuids = transaction {
                    Shows.selectAll().map { it[Shows.uuid] }.toSet()
                }
                val knownSerialUuids = transaction {
                    Serials.selectAll().map { it[Serials.uuid] }.toSet()
                }

                val results = mutableListOf<SearchResult>()

                try {
                    val shows = api.searchShows(query)
                    for (show in shows) {
                        results.add(SearchResult(
                            uuid = show.uuid,
                            title = show.title,
                            type = "show",
                            detail = null,
                            imageUrl = show.imageUrl,
                            alreadyAdded = show.uuid in knownShowUuids,
                        ))
                    }
                } catch (e: Exception) {
                    serverLog.warn("Show search failed for '$query': ${e.message}")
                }

                try {
                    val serials = api.searchSerials(query)
                    for (serial in serials) {
                        val parentShowTitle = serial.showUuid?.let { showUuid ->
                            try { api.getShow(showUuid).title } catch (_: Exception) { null }
                        }
                        val detail = listOfNotNull(
                            "${serial.totalParts} parts",
                            parentShowTitle?.let { "in $it" },
                        ).joinToString(" | ")
                        results.add(SearchResult(
                            uuid = serial.uuid,
                            title = serial.title,
                            type = "serial",
                            detail = detail,
                            imageUrl = serial.imageUrl,
                            alreadyAdded = serial.uuid in knownSerialUuids,
                        ))
                    }
                } catch (e: Exception) {
                    serverLog.warn("Serial search failed for '$query': ${e.message}")
                }

                val deduplicated = results.distinctBy { it.uuid }
                val html = createHTML().div {
                    searchResults(deduplicated)
                }
                call.respondText(html, contentType = ContentType.Text.Html)
            }

            post("/search/add") {
                val params = call.receiveParameters()
                val uuid = params["uuid"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val type = params["type"] ?: return@post call.respond(HttpStatusCode.BadRequest)

                try {
                    val result = when (type) {
                        "show" -> {
                            val show = api.getShow(uuid)
                            val serials = api.getShowSerials(uuid).map { serial ->
                                serial.copy(episodes = api.getSerialEpisodes(serial.uuid))
                            }
                            val allEpisodes = api.getShowEpisodes(uuid)
                            val serialEpUuids = serials.flatMap { s -> s.episodes.map { it.uuid } }.toSet()
                            val populatedShow = show.copy(
                                serials = serials,
                                episodes = allEpisodes.filter { it.uuid !in serialEpUuids },
                            )
                            insertShow(populatedShow)
                            SearchResult(show.uuid, show.title, "show", null, show.imageUrl, true)
                        }
                        "serial" -> {
                            val serial = api.getSerial(uuid)
                            val showUuid = serial.showUuid
                                ?: throw RuntimeException("Serial has no parent show")
                            val parentShow = api.getShow(showUuid)
                            val episodes = api.getSerialEpisodes(uuid)
                            insertShow(parentShow.copy(
                                serials = listOf(serial.copy(episodes = episodes)),
                                episodes = emptyList(),
                            ))
                            SearchResult(serial.uuid, serial.title, "serial", "${serial.totalParts} parts", serial.imageUrl, true)
                        }
                        "episode" -> {
                            val episode = api.getEpisode(uuid)
                            val showUuid = episode.showUuid
                                ?: throw RuntimeException("Episode has no parent show")
                            val parentShow = api.getShow(showUuid)
                            insertShow(parentShow.copy(
                                serials = emptyList(),
                                episodes = listOf(episode),
                            ))
                            SearchResult(episode.uuid, episode.title, "episode", null, null, true)
                        }
                        else -> throw RuntimeException("Unknown type: $type")
                    }

                    val html = createHTML().div {
                        searchResultAdded(result)
                    }
                    call.respondText(html, contentType = ContentType.Text.Html)
                } catch (e: Exception) {
                    serverLog.error("Failed to add $type $uuid from search", e)
                    call.respondText(
                        "<article style='padding:0.75rem 1rem;margin-bottom:0.5rem;'><p>Error: ${e.message}</p></article>",
                        contentType = ContentType.Text.Html,
                    )
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
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        serverLog.info("Shutdown signal received, waiting for current work to finish...")
        runBlocking {
            discoverer.shutdown()
            downloadQueue.shutdown()
        }
        server.stop(1000, 5000)
        serverLog.info("Shutdown complete")
    })

    server.start(wait = true)
}

// --- Helpers ---

private suspend fun respondSerialDetail(call: ApplicationCall, serialUuid: String, urlSigner: UrlSigner) {
    val serialData = transaction {
        Serials.selectAll().where { Serials.uuid eq serialUuid }.firstOrNull()
    } ?: return call.respond(HttpStatusCode.NotFound, "Serial not found")

    val showData = transaction {
        Shows.selectAll().where { Shows.uuid eq serialData[Serials.showUuid] }.firstOrNull()
    }

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
            serialDetail(
                serialRow,
                episodes,
                parentShowTitle = showData?.get(Shows.title),
                parentShowSubscribed = showData?.get(Shows.subscribed) ?: false,
            )
        }
    }
}

/**
 * Try to resolve a UUID by probing the API as episode, serial, and show.
 * Tries all three (cheapest first) and returns the first match.
 */
private fun resolveByUuid(api: Api, uuid: String): ResolvedResult {
    // Try as episode (single fetch, cheapest)
    try {
        val episode = api.getEpisode(uuid)
        return ResolvedResult.EpisodeResult(episode)
    } catch (_: Exception) {}

    // Try as serial
    try {
        val serial = api.getSerial(uuid)
        val episodes = api.getSerialEpisodes(uuid)
        return ResolvedResult.SerialResult(serial.copy(episodes = episodes))
    } catch (_: Exception) {}

    // Try as show (most expensive — fetches serials + episodes)
    try {
        val show = api.getShow(uuid)
        val serials = api.getShowSerials(uuid).map { serial ->
            serial.copy(episodes = api.getSerialEpisodes(serial.uuid))
        }
        val allEpisodes = api.getShowEpisodes(uuid)
        val serialEpUuids = serials.flatMap { s -> s.episodes.map { it.uuid } }.toSet()
        return ResolvedResult.ShowResult(
            show.copy(serials = serials, episodes = allEpisodes.filter { it.uuid !in serialEpUuids })
        )
    } catch (_: Exception) {}

    throw RuntimeException("UUID $uuid not found as episode, serial, or show")
}

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
        number = this[Episodes.part] ?: this[Episodes.seriesEpisodeNumber],
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
