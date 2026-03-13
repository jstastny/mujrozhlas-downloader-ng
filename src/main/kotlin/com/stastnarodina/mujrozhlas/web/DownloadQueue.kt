package com.stastnarodina.mujrozhlas.web

import com.stastnarodina.mujrozhlas.DownloadedEpisode
import com.stastnarodina.mujrozhlas.Downloader
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant

/** Maximum number of episodes for automatic M4B audiobook creation. */
const val MAX_M4B_EPISODES = 100

class DownloadQueue(
    private val downloader: Downloader,
    private val outputDir: File,
    private val scope: CoroutineScope,
) {
    private val log = LoggerFactory.getLogger(DownloadQueue::class.java)
    private val channel = Channel<String>(Channel.UNLIMITED)
    private var processingJob: Job? = null

    @Volatile
    var currentDownload: String? = null
        private set

    fun start() {
        outputDir.mkdirs()
        recoverApproved()

        processingJob = scope.launch {
            for (episodeUuid in channel) {
                processDownload(episodeUuid)
            }
        }
    }

    fun enqueue(episodeUuid: String) {
        channel.trySend(episodeUuid)
    }

    /**
     * Graceful shutdown: stop accepting new work and wait for the
     * current download to finish.
     */
    suspend fun shutdown() {
        log.info("Shutting down download queue...")
        channel.close()
        processingJob?.join()
        log.info("Download queue stopped")
    }

    private fun recoverApproved() {
        val subscribedShows = transaction {
            Shows.selectAll().where { Shows.subscribed eq true }
                .map { it[Shows.uuid] }.toSet()
        }

        // Reset stuck DOWNLOADING episodes: re-approve only if parent show is subscribed
        val stuck = transaction {
            Episodes.selectAll().where { Episodes.status eq EpisodeStatus.DOWNLOADING }
                .map { it[Episodes.uuid] to it[Episodes.showUuid] }
        }

        if (stuck.isNotEmpty()) {
            var requeued = 0
            var reverted = 0
            transaction {
                for ((uuid, showUuid) in stuck) {
                    if (showUuid in subscribedShows) {
                        Episodes.update({ Episodes.uuid eq uuid }) { it[status] = EpisodeStatus.APPROVED }
                        requeued++
                    } else {
                        Episodes.update({ Episodes.uuid eq uuid }) { it[status] = EpisodeStatus.PENDING }
                        reverted++
                    }
                }
            }
            if (requeued > 0) log.info("Reset $requeued stuck DOWNLOADING episode(s) to APPROVED (subscribed)")
            if (reverted > 0) log.info("Reset $reverted stuck DOWNLOADING episode(s) to PENDING (not subscribed)")
        }

        // Re-enqueue APPROVED episodes only if parent show is still subscribed
        val approved = transaction {
            Episodes.selectAll().where { Episodes.status eq EpisodeStatus.APPROVED }
                .map { it[Episodes.uuid] to it[Episodes.showUuid] }
        }

        var enqueued = 0
        var stale = 0
        for ((uuid, showUuid) in approved) {
            if (showUuid in subscribedShows) {
                channel.trySend(uuid)
                enqueued++
            } else {
                transaction {
                    Episodes.update({ Episodes.uuid eq uuid }) { it[status] = EpisodeStatus.PENDING }
                }
                stale++
            }
        }
        if (enqueued > 0) log.info("Re-enqueued $enqueued approved episode(s)")
        if (stale > 0) log.info("Reverted $stale stale APPROVED episode(s) to PENDING (show unsubscribed)")
    }

    private suspend fun processDownload(episodeUuid: String) {
        currentDownload = episodeUuid

        val episodeData = transaction {
            Episodes.selectAll().where { Episodes.uuid eq episodeUuid }.firstOrNull()
        }

        if (episodeData == null) {
            log.warn("Episode $episodeUuid not found in DB")
            currentDownload = null
            return
        }

        // Skip if the episode was reverted (e.g. unsubscribe) while queued
        if (episodeData[Episodes.status] != EpisodeStatus.APPROVED) {
            log.info("Skipping episode $episodeUuid (status: ${episodeData[Episodes.status]})")
            currentDownload = null
            return
        }

        val audioUrl = episodeData[Episodes.hlsUrl]
        val audioVariant = episodeData[Episodes.audioVariant]
        if (audioUrl == null) {
            transaction {
                Episodes.update({ Episodes.uuid eq episodeUuid }) {
                    it[status] = EpisodeStatus.ERROR
                    it[errorMessage] = "No audio URL available"
                }
            }
            currentDownload = null
            return
        }

        // Determine container title and episode number for filename
        val serialUuid = episodeData[Episodes.serialUuid]
        val containerTitle = if (serialUuid != null) {
            transaction {
                Serials.selectAll().where { Serials.uuid eq serialUuid }
                    .firstOrNull()?.get(Serials.title)
            }
        } else {
            null
        } ?: transaction {
            Shows.selectAll().where { Shows.uuid eq episodeData[Episodes.showUuid] }
                .firstOrNull()?.get(Shows.title)
        } ?: "Unknown"

        val episodeNumber = episodeData[Episodes.part]
            ?: episodeData[Episodes.seriesEpisodeNumber]

        val containerDir = File(outputDir, Downloader.sanitizeFilename(containerTitle))
        containerDir.mkdirs()

        val baseName = if (episodeNumber != null) {
            val padWidth = Downloader.digitCount(episodeNumber)
            "%0${padWidth}d - %s".format(episodeNumber, Downloader.sanitizeFilename(containerTitle))
        } else {
            Downloader.sanitizeFilename(episodeData[Episodes.title])
        }
        val extension = if (audioVariant == "mp3") "mp3" else "m4a"
        val outputFile = File(containerDir, "$baseName.$extension")

        transaction {
            Episodes.update({ Episodes.uuid eq episodeUuid }) {
                it[status] = EpisodeStatus.DOWNLOADING
            }
        }

        try {
            withContext(Dispatchers.IO) {
                log.info("Downloading ($audioVariant): ${episodeData[Episodes.title]} -> $baseName.$extension")
                if (audioVariant == "mp3") {
                    downloader.downloadFile(audioUrl, outputFile)
                } else {
                    downloader.downloadHlsToM4a(audioUrl, outputFile)
                }
            }
            transaction {
                Episodes.update({ Episodes.uuid eq episodeUuid }) {
                    it[status] = EpisodeStatus.DOWNLOADED
                    it[downloadedAt] = Instant.now()
                    it[filePath] = outputFile.absolutePath
                }
            }
            log.info("Downloaded: ${episodeData[Episodes.title]}")

            // Auto-create M4B for subscribed shows (serial episodes only)
            if (serialUuid != null) {
                tryCreateM4b(serialUuid)
            }
        } catch (e: Exception) {
            log.error("Download failed for ${episodeData[Episodes.title]}: ${e.message}")
            transaction {
                Episodes.update({ Episodes.uuid eq episodeUuid }) {
                    it[status] = EpisodeStatus.ERROR
                    it[errorMessage] = e.message?.take(2000)
                }
            }
        } finally {
            currentDownload = null
        }
    }

    private fun tryCreateM4b(serialUuid: String) {
        val serialData = transaction {
            Serials.selectAll().where { Serials.uuid eq serialUuid }.firstOrNull()
        } ?: return

        // Check if the parent show is subscribed
        val showUuid = serialData[Serials.showUuid]
        val isSubscribed = transaction {
            Shows.selectAll().where { Shows.uuid eq showUuid }
                .firstOrNull()?.get(Shows.subscribed) ?: false
        }
        if (!isSubscribed) return

        val totalParts = serialData[Serials.totalParts]
        if (totalParts > MAX_M4B_EPISODES) return

        val serialTitle = serialData[Serials.title]
        val imageUrl = serialData[Serials.imageUrl]
        val serialDir = File(outputDir, Downloader.sanitizeFilename(serialTitle))

        val episodes = transaction {
            Episodes.selectAll()
                .where { (Episodes.serialUuid eq serialUuid) and (Episodes.status eq EpisodeStatus.DOWNLOADED) }
                .orderBy(Episodes.part)
                .mapNotNull { row ->
                    val path = row[Episodes.filePath] ?: return@mapNotNull null
                    DownloadedEpisode(
                        title = row[Episodes.title],
                        number = row[Episodes.part] ?: row[Episodes.seriesEpisodeNumber] ?: 0,
                        duration = row[Episodes.duration],
                        audioFile = File(path),
                    )
                }
        }

        if (episodes.isEmpty()) return

        val missing = episodes.filter { !it.audioFile.exists() }
        if (missing.isNotEmpty()) {
            log.warn("Skipping M4B creation for '$serialTitle': missing ${missing.size} audio files")
            return
        }

        try {
            val coverFile = if (imageUrl != null) {
                val file = File(serialDir, "cover.jpg")
                try {
                    downloader.downloadFile(imageUrl, file)
                    file
                } catch (e: Exception) {
                    log.warn("Cover image download failed for '$serialTitle': ${e.message}")
                    null
                }
            } else null

            val m4bFile = File(serialDir, "${Downloader.sanitizeFilename(serialTitle)}.m4b")
            downloader.combineToM4b(episodes, serialTitle, coverFile, m4bFile)
            transaction {
                Serials.update({ Serials.uuid eq serialUuid }) {
                    it[Serials.m4bPath] = m4bFile.absolutePath
                }
            }
            log.info("M4B created: ${m4bFile.name} (${episodes.size} chapters)")
        } catch (e: Exception) {
            log.error("M4B creation failed for '$serialTitle': ${e.message}")
        }
    }
}
