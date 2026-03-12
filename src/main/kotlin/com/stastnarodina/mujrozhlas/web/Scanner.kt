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

        val storedLastEpisodeSince = transaction {
            Serials.selectAll().where { Serials.uuid eq uuid }.firstOrNull()?.get(Serials.lastEpisodeSince)
        }

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
                    Episodes.insert {
                        it[Episodes.uuid] = ep.uuid
                        it[Episodes.serialUuid] = uuid
                        it[Episodes.title] = ep.title
                        it[Episodes.part] = ep.part
                        it[Episodes.status] = EpisodeStatus.PENDING
                        it[Episodes.hlsUrl] = hlsLink?.url
                        it[Episodes.duration] = hlsLink?.duration ?: 0
                        it[Episodes.playableTill] = hlsLink?.playableTill
                        it[Episodes.discoveredAt] = Instant.now()
                    }
                }
            }
        }
    }
}
