package com.stastnarodina.mujrozhlas.web

import com.stastnarodina.mujrozhlas.Api
import com.stastnarodina.mujrozhlas.Episode
import kotlinx.coroutines.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.Instant

class Discoverer(
    private val api: Api,
    private val scope: CoroutineScope,
    private val downloadQueue: DownloadQueue? = null,
) {
    private val log = LoggerFactory.getLogger(Discoverer::class.java)

    private var periodicJob: Job? = null

    @Volatile
    var isDiscovering = false
        private set

    fun start(intervalHours: Long = 6) {
        periodicJob = scope.launch {
            while (isActive) {
                runDiscovery()
                delay(intervalHours * 3600 * 1000)
            }
        }
    }

    fun discoverNow() {
        if (isDiscovering) return
        scope.launch { runDiscovery() }
    }

    /** Cancel periodic discovery and wait for any in-progress run to finish. */
    suspend fun shutdown() {
        log.info("Shutting down discoverer...")
        periodicJob?.cancelAndJoin()
        log.info("Discoverer stopped")
    }

    suspend fun runDiscovery() {
        if (isDiscovering) return
        isDiscovering = true
        log.info("Starting discovery...")

        try {
            withContext(Dispatchers.IO) {
                // Phase 1: Discover new content from recent episodes
                discoverRecentContent()

                // Phase 2: Re-scan subscribed shows for updates
                rescanSubscribedShows()
            }
        } catch (e: Exception) {
            log.error("Discovery failed: ${e.message}", e)
        } finally {
            isDiscovering = false
            log.info("Discovery complete")
        }
    }

    /**
     * Phase 1: Fetch recent episodes from the API, discover their parent
     * shows and serials, then fetch the FULL episode lists for each
     * newly discovered serial/show.
     */
    private fun discoverRecentContent() {
        val recentEpisodes = api.getRecentEpisodes(200)
        log.info("Fetched ${recentEpisodes.size} recent episodes for discovery")

        val showUuids = recentEpisodes.mapNotNull { it.showUuid }.distinct()
        val serialUuids = recentEpisodes.mapNotNull { it.serialUuid }.distinct()

        log.info("Found ${showUuids.size} shows, ${serialUuids.size} serials in recent episodes")

        // Upsert shows (metadata only)
        for (showUuid in showUuids) {
            try {
                ensureShow(showUuid)
            } catch (e: Exception) {
                log.error("Error upserting show $showUuid: ${e.message}")
            }
        }

        // Upsert serials and fetch full episode lists for new ones
        for (serialUuid in serialUuids) {
            try {
                ensureSerialWithEpisodes(serialUuid)
            } catch (e: Exception) {
                log.error("Error upserting serial $serialUuid: ${e.message}")
            }
        }
    }

    /**
     * Ensure a show exists in the DB. If not, fetch from API and insert.
     * If it exists, update metadata.
     */
    private fun ensureShow(showUuid: String) {
        val exists = transaction {
            Shows.selectAll().where { Shows.uuid eq showUuid }.count() > 0
        }

        val show = api.getShow(showUuid)

        transaction {
            if (!exists) {
                Shows.insert {
                    it[uuid] = showUuid
                    it[title] = show.title
                    it[imageUrl] = show.imageUrl
                }
                log.info("Discovered show: '${show.title}'")
            } else {
                Shows.update({ Shows.uuid eq showUuid }) {
                    it[title] = show.title
                    it[imageUrl] = show.imageUrl
                }
            }
        }
    }

    /**
     * Ensure a serial exists in the DB (and its parent show).
     * For newly discovered serials, fetch and insert ALL episodes.
     */
    private fun ensureSerialWithEpisodes(serialUuid: String) {
        val exists = transaction {
            Serials.selectAll().where { Serials.uuid eq serialUuid }.count() > 0
        }

        val serial = api.getSerial(serialUuid)

        if (serial.showUuid == null) {
            log.warn("Serial '${serial.title}' ($serialUuid) has no parent show, skipping")
            return
        }

        // Ensure parent show exists
        try {
            ensureShow(serial.showUuid)
        } catch (e: Exception) {
            log.error("Error ensuring parent show for serial $serialUuid: ${e.message}")
            return
        }

        transaction {
            if (!exists) {
                Serials.insert {
                    it[uuid] = serialUuid
                    it[showUuid] = serial.showUuid
                    it[title] = serial.title
                    it[totalParts] = serial.totalParts
                    it[lastEpisodeSince] = serial.lastEpisodeSince
                    it[imageUrl] = serial.imageUrl
                }
                log.info("Discovered serial: '${serial.title}'")
            } else {
                Serials.update({ Serials.uuid eq serialUuid }) {
                    it[title] = serial.title
                    it[totalParts] = serial.totalParts
                    it[lastEpisodeSince] = serial.lastEpisodeSince
                    it[imageUrl] = serial.imageUrl
                }
            }
        }

        // Fetch and upsert all episodes for this serial
        val allEpisodes = api.getSerialEpisodes(serialUuid)
        upsertEpisodes(allEpisodes, serial.showUuid, serialUuid)
    }

    /**
     * Insert episodes that are not yet in the DB. New episodes get PENDING status.
     * Episodes for subscribed shows with audio get auto-APPROVED.
     */
    private fun upsertEpisodes(
        episodes: List<Episode>,
        showUuid: String,
        serialUuid: String?,
    ) {
        val knownUuids = transaction {
            val query = if (serialUuid != null) {
                Episodes.selectAll().where { Episodes.serialUuid eq serialUuid }
            } else {
                Episodes.selectAll().where {
                    (Episodes.showUuid eq showUuid) and Episodes.serialUuid.isNull()
                }
            }
            query.map { it[Episodes.uuid] }.toSet()
        }

        val newEpisodes = episodes.filter { it.uuid !in knownUuids }
        if (newEpisodes.isEmpty()) return

        val isSubscribed = transaction {
            Shows.selectAll().where { (Shows.uuid eq showUuid) and (Shows.subscribed eq true) }.count() > 0
        }

        log.info("  Inserting ${newEpisodes.size} new episode(s) for ${serialUuid ?: "show $showUuid"}")

        transaction {
            for (ep in newEpisodes) {
                val hlsLink = ep.audioLinks.firstOrNull { it.variant == "hls" }
                val duration = hlsLink?.duration ?: 0
                val initialStatus = if (isSubscribed && duration > 0) {
                    EpisodeStatus.APPROVED
                } else {
                    EpisodeStatus.PENDING
                }

                Episodes.insert {
                    it[uuid] = ep.uuid
                    it[Episodes.showUuid] = showUuid
                    it[Episodes.serialUuid] = serialUuid
                    it[title] = ep.title
                    it[part] = ep.part
                    it[seriesEpisodeNumber] = ep.seriesEpisodeNumber
                    it[status] = initialStatus
                    it[Episodes.hlsUrl] = hlsLink?.url
                    it[Episodes.duration] = duration
                    it[playableTill] = hlsLink?.playableTill
                    it[discoveredAt] = Instant.now()
                }

                if (initialStatus == EpisodeStatus.APPROVED) {
                    downloadQueue?.enqueue(ep.uuid)
                }
            }
        }
    }

    /**
     * Phase 2: Re-scan subscribed shows — fetch all their serials and episodes
     * from the API and upsert any new content. Auto-approve new episodes.
     * Also checks if previously unavailable episodes now have audio.
     */
    private fun rescanSubscribedShows() {
        val subscribedShows = transaction {
            Shows.selectAll()
                .where { Shows.subscribed eq true }
                .map { it[Shows.uuid] }
        }

        if (subscribedShows.isEmpty()) return
        log.info("Re-scanning ${subscribedShows.size} subscribed show(s)")

        for (showUuid in subscribedShows) {
            try {
                processSubscribedShow(showUuid)
            } catch (e: Exception) {
                log.error("Error re-scanning show $showUuid: ${e.message}")
            }
        }
    }

    private fun processSubscribedShow(showUuid: String) {
        val show = api.getShow(showUuid)
        log.info("Re-scanning subscribed show: '${show.title}'")

        // Update show metadata
        transaction {
            Shows.update({ Shows.uuid eq showUuid }) {
                it[title] = show.title
                it[imageUrl] = show.imageUrl
                it[lastScanned] = Instant.now()
            }
        }

        // Sync serials and their episodes
        val apiSerials = api.getShowSerials(showUuid)
        for (serial in apiSerials) {
            ensureSerialWithEpisodes(serial.uuid)
            refreshPendingEpisodes(showUuid, serial.uuid)
        }

        // Sync direct show episodes (not in any serial)
        val showEpisodes = api.getShowEpisodes(showUuid)
        val serialEpisodeUuids = apiSerials.flatMap { serial ->
            api.getSerialEpisodes(serial.uuid).map { it.uuid }
        }.toSet()
        val directEpisodes = showEpisodes.filter { it.uuid !in serialEpisodeUuids }

        if (directEpisodes.isNotEmpty()) {
            upsertEpisodes(directEpisodes, showUuid, null)
            refreshPendingEpisodes(showUuid, null)
        }
    }

    /**
     * For subscribed shows: check if PENDING episodes with duration=0
     * now have audio available, and if so, approve and enqueue them.
     */
    private fun refreshPendingEpisodes(showUuid: String, serialUuid: String?) {
        if (downloadQueue == null) return

        val isSubscribed = transaction {
            Shows.selectAll().where { (Shows.uuid eq showUuid) and (Shows.subscribed eq true) }.count() > 0
        }
        if (!isSubscribed) return

        val pendingNoAudio = transaction {
            val query = if (serialUuid != null) {
                Episodes.selectAll().where {
                    (Episodes.serialUuid eq serialUuid) and
                            (Episodes.status eq EpisodeStatus.PENDING) and
                            (Episodes.duration eq 0)
                }
            } else {
                Episodes.selectAll().where {
                    (Episodes.showUuid eq showUuid) and
                            Episodes.serialUuid.isNull() and
                            (Episodes.status eq EpisodeStatus.PENDING) and
                            (Episodes.duration eq 0)
                }
            }
            query.map { it[Episodes.uuid] }.toSet()
        }

        if (pendingNoAudio.isEmpty()) return

        // Re-fetch episodes from API to check for audio
        val apiEpisodes = if (serialUuid != null) {
            api.getSerialEpisodes(serialUuid)
        } else {
            api.getShowEpisodes(showUuid)
        }
        val apiByUuid = apiEpisodes.associateBy { it.uuid }

        val nowAvailable = pendingNoAudio.filter { epUuid ->
            val apiEp = apiByUuid[epUuid] ?: return@filter false
            val hlsLink = apiEp.audioLinks.firstOrNull { it.variant == "hls" }
            hlsLink != null && hlsLink.duration > 0
        }

        if (nowAvailable.isNotEmpty()) {
            log.info("  ${nowAvailable.size} episode(s) now have audio available")
            transaction {
                for (epUuid in nowAvailable) {
                    val apiEp = apiByUuid[epUuid]!!
                    val hlsLink = apiEp.audioLinks.first { it.variant == "hls" }
                    Episodes.update({ Episodes.uuid eq epUuid }) {
                        it[Episodes.hlsUrl] = hlsLink.url
                        it[Episodes.duration] = hlsLink.duration
                        it[Episodes.playableTill] = hlsLink.playableTill
                        it[Episodes.status] = EpisodeStatus.APPROVED
                    }
                }
            }
            for (epUuid in nowAvailable) {
                downloadQueue.enqueue(epUuid)
            }
        }
    }
}
