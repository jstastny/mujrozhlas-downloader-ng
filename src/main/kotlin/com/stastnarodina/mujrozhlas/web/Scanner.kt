package com.stastnarodina.mujrozhlas.web

import com.stastnarodina.mujrozhlas.Api
import kotlinx.coroutines.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.Instant

class Scanner(
    private val api: Api,
    private val scope: CoroutineScope,
    private val downloadQueue: DownloadQueue? = null,
) {
    private val log = LoggerFactory.getLogger(Scanner::class.java)

    @Volatile
    var isScanning = false
        private set

    fun start(intervalHours: Long = 6) {
        scope.launch {
            while (isActive) {
                runScan()
                delay(intervalHours * 3600 * 1000)
            }
        }
    }

    fun scanNow() {
        if (isScanning) return
        scope.launch { runScan() }
    }

    suspend fun runScan() {
        if (isScanning) return
        isScanning = true
        log.info("Starting scan...")

        try {
            withContext(Dispatchers.IO) {
                // Fetch recent episodes and find unique serial UUIDs
                val recentEpisodes = api.getRecentEpisodes(200)
                val serialUuids = recentEpisodes
                    .mapNotNull { it.serialUuid }
                    .distinct()
                log.info("Fetched ${recentEpisodes.size} recent episodes from ${serialUuids.size} serials")

                for (serialUuid in serialUuids) {
                    try {
                        processSerial(serialUuid)
                    } catch (e: Exception) {
                        log.error("Error processing serial $serialUuid: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            log.error("Scan failed: ${e.message}", e)
        } finally {
            isScanning = false
            log.info("Scan complete")
        }
    }

    private fun processSerial(uuid: String) {
        val serial = api.getSerial(uuid)

        val storedSerial = transaction {
            Serials.selectAll().where { Serials.uuid eq uuid }.firstOrNull()
        }

        val storedLastEpisodeSince = storedSerial?.get(Serials.lastEpisodeSince)
        val isSubscribed = storedSerial?.get(Serials.subscribed) ?: false
        val isNew = storedLastEpisodeSince == null
        val hasChanged = isNew || storedLastEpisodeSince != serial.lastEpisodeSince

        // Upsert serial
        transaction {
            if (isNew) {
                Serials.insert {
                    it[Serials.uuid] = uuid
                    it[Serials.title] = serial.title
                    it[Serials.totalParts] = serial.totalParts
                    it[Serials.lastEpisodeSince] = serial.lastEpisodeSince
                    it[Serials.imageUrl] = serial.imageUrl
                    it[Serials.lastScanned] = Instant.now()
                }
            } else {
                Serials.update({ Serials.uuid eq uuid }) {
                    it[Serials.title] = serial.title
                    it[Serials.totalParts] = serial.totalParts
                    it[Serials.lastEpisodeSince] = serial.lastEpisodeSince
                    it[Serials.imageUrl] = serial.imageUrl
                    it[Serials.lastScanned] = Instant.now()
                }
            }
        }

        if (!hasChanged) return

        // Fetch episodes and diff
        val apiEpisodes = api.getSerialEpisodes(uuid)
        val knownUuids = transaction {
            Episodes.selectAll().where { Episodes.serialUuid eq uuid }
                .map { it[Episodes.uuid] }
                .toSet()
        }

        val newEpisodes = apiEpisodes.filter { it.uuid !in knownUuids }
        if (newEpisodes.isNotEmpty()) {
            log.info("Serial '${serial.title}': ${newEpisodes.size} new episodes")
            transaction {
                for (ep in newEpisodes) {
                    val hlsLink = ep.audioLinks.firstOrNull { it.variant == "hls" }
                    val duration = hlsLink?.duration ?: 0
                    // For subscribed serials, auto-approve episodes that have audio
                    val initialStatus = if (isSubscribed && duration > 0) EpisodeStatus.APPROVED else EpisodeStatus.PENDING
                    Episodes.insert {
                        it[Episodes.uuid] = ep.uuid
                        it[Episodes.serialUuid] = uuid
                        it[Episodes.title] = ep.title
                        it[Episodes.part] = ep.part
                        it[Episodes.status] = initialStatus
                        it[Episodes.hlsUrl] = hlsLink?.url
                        it[Episodes.duration] = duration
                        it[Episodes.playableTill] = hlsLink?.playableTill
                        it[Episodes.discoveredAt] = Instant.now()
                    }
                    if (initialStatus == EpisodeStatus.APPROVED) {
                        downloadQueue?.enqueue(ep.uuid)
                    }
                }
            }
        }

        // For subscribed serials: check if any PENDING episodes with duration=0 now have audio
        if (isSubscribed && downloadQueue != null) {
            val pendingNoAudio = transaction {
                Episodes.selectAll()
                    .where { (Episodes.serialUuid eq uuid) and (Episodes.status eq EpisodeStatus.PENDING) and (Episodes.duration eq 0) }
                    .map { it[Episodes.uuid] }
                    .toSet()
            }

            if (pendingNoAudio.isNotEmpty()) {
                val apiByUuid = apiEpisodes.associateBy { it.uuid }
                val nowAvailable = pendingNoAudio.filter { epUuid ->
                    val apiEp = apiByUuid[epUuid] ?: return@filter false
                    val hlsLink = apiEp.audioLinks.firstOrNull { it.variant == "hls" }
                    hlsLink != null && hlsLink.duration > 0
                }

                if (nowAvailable.isNotEmpty()) {
                    log.info("Serial '${serial.title}': ${nowAvailable.size} episodes now available")
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
    }
}
