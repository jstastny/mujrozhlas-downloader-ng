package com.stastnarodina.mujrozhlas.web

import com.stastnarodina.mujrozhlas.Downloader
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant

class DownloadQueue(
    private val downloader: Downloader,
    private val outputDir: File,
    private val scope: CoroutineScope,
) {
    private val log = LoggerFactory.getLogger(DownloadQueue::class.java)
    private val channel = Channel<String>(Channel.UNLIMITED)

    @Volatile
    var currentDownload: String? = null
        private set

    fun start() {
        outputDir.mkdirs()
        recoverApproved()

        scope.launch {
            for (episodeUuid in channel) {
                processDownload(episodeUuid)
            }
        }
    }

    fun enqueue(episodeUuid: String) {
        channel.trySend(episodeUuid)
    }

    private fun recoverApproved() {
        val approved = transaction {
            Episodes.selectAll().where { Episodes.status eq EpisodeStatus.APPROVED }
                .map { it[Episodes.uuid] }
        }
        for (uuid in approved) {
            log.info("Re-enqueuing approved episode: $uuid")
            channel.trySend(uuid)
        }
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

        val hlsUrl = episodeData[Episodes.hlsUrl]
        if (hlsUrl == null) {
            transaction {
                Episodes.update({ Episodes.uuid eq episodeUuid }) {
                    it[status] = EpisodeStatus.ERROR
                    it[errorMessage] = "No HLS URL available"
                }
            }
            currentDownload = null
            return
        }

        // Get serial title for directory
        val serialTitle = transaction {
            Serials.selectAll().where { Serials.uuid eq episodeData[Episodes.serialUuid] }
                .firstOrNull()?.get(Serials.title) ?: "Unknown"
        }

        val serialDir = File(outputDir, Downloader.sanitizeFilename(serialTitle))
        serialDir.mkdirs()

        val baseName = "%02d - %s".format(
            episodeData[Episodes.part],
            Downloader.sanitizeFilename(serialTitle)
        )
        val m4aFile = File(serialDir, "$baseName.m4a")

        transaction {
            Episodes.update({ Episodes.uuid eq episodeUuid }) {
                it[status] = EpisodeStatus.DOWNLOADING
            }
        }

        try {
            withContext(Dispatchers.IO) {
                log.info("Downloading: ${episodeData[Episodes.title]} -> $baseName")
                downloader.downloadHlsToM4a(hlsUrl, m4aFile)
            }
            transaction {
                Episodes.update({ Episodes.uuid eq episodeUuid }) {
                    it[status] = EpisodeStatus.DOWNLOADED
                    it[downloadedAt] = Instant.now()
                    it[filePath] = m4aFile.absolutePath
                }
            }
            log.info("Downloaded: ${episodeData[Episodes.title]}")
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
}
