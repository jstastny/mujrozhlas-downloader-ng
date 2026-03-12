package com.stastnarodina.mujrozhlas

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class Downloader {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun checkFfmpeg() {
        try {
            val process = ProcessBuilder("ffmpeg", "-version")
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().readLine()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw RuntimeException("ffmpeg exited with code $exitCode")
            }
        } catch (e: Exception) {
            throw RuntimeException("ffmpeg not found in PATH. Please install ffmpeg.", e)
        }
    }

    fun downloadSerial(serial: Serial, outputDir: File, dryRun: Boolean) {
        val serialDir = File(outputDir, sanitizeFilename(serial.title))
        if (!dryRun) {
            serialDir.mkdirs()
        }

        println("Serial: ${serial.title} (${serial.episodes.size} episodes)")
        println("Output: ${serialDir.absolutePath}")
        println()

        var downloaded = 0
        var skipped = 0
        val downloadedEpisodes = mutableListOf<DownloadedEpisode>()

        for (episode in serial.episodes) {
            val baseName = "%02d - %s".format(episode.part, sanitizeFilename(serial.title))
            val m4aFile = File(serialDir, "$baseName.m4a")

            if (dryRun) {
                val hlsLink = episode.audioLinks.firstOrNull { it.variant == "hls" }
                val status = if (hlsLink != null && isPlayable(hlsLink)) "OK" else "UNAVAILABLE"
                println("  [$status] $baseName")
                continue
            }

            try {
                val hlsLink = episode.audioLinks.firstOrNull { it.variant == "hls" }
                    ?: throw RuntimeException("No HLS audio link for episode: ${episode.title}")
                if (!isPlayable(hlsLink)) {
                    throw RuntimeException("Episode expired (playable till: ${hlsLink.playableTill})")
                }

                println("  Downloading: $baseName (${hlsLink.duration}s)")
                downloadHlsToM4a(hlsLink.url, m4aFile)

                downloadedEpisodes.add(DownloadedEpisode(
                    title = episode.title,
                    part = episode.part,
                    duration = hlsLink.duration,
                    m4aFile = m4aFile,
                ))
                downloaded++
            } catch (e: Exception) {
                System.err.println("  SKIP ${episode.title} (part ${episode.part}): ${e.message}")
                skipped++
            }
        }

        if (!dryRun && downloadedEpisodes.isNotEmpty()) {
            // Download cover image
            val coverFile = if (serial.imageUrl != null) {
                val file = File(serialDir, "cover.jpg")
                try {
                    downloadFile(serial.imageUrl, file)
                    println("  Cover image saved")
                    file
                } catch (e: Exception) {
                    System.err.println("  Cover image download failed: ${e.message}")
                    null
                }
            } else null

            // Combine into M4B
            try {
                val m4bFile = File(serialDir, "${sanitizeFilename(serial.title)}.m4b")
                combineToM4b(downloadedEpisodes, serial.title, coverFile, m4bFile)
                println("  M4B created: ${m4bFile.name}")
            } catch (e: Exception) {
                System.err.println("  M4B creation failed: ${e.message}")
            }

            println()
            println("Done: $downloaded downloaded, $skipped skipped")
        }
    }

    fun downloadSingleEpisode(episode: Episode, outputDir: File, dryRun: Boolean) {
        val title = episode.serialTitle ?: episode.title
        val filename = if (episode.part > 0) {
            "%02d - %s.m4a".format(episode.part, sanitizeFilename(title))
        } else {
            "${sanitizeFilename(episode.title)}.m4a"
        }
        val outputFile = File(outputDir, filename)

        if (dryRun) {
            val hlsLink = episode.audioLinks.firstOrNull { it.variant == "hls" }
            val status = if (hlsLink != null && isPlayable(hlsLink)) "OK" else "UNAVAILABLE"
            println("[$status] $filename")
            return
        }

        val hlsLink = episode.audioLinks.firstOrNull { it.variant == "hls" }
            ?: throw RuntimeException("No HLS audio link for episode: ${episode.title}")
        if (!isPlayable(hlsLink)) {
            throw RuntimeException("Episode expired (playable till: ${hlsLink.playableTill})")
        }

        println("  Downloading: ${outputFile.name} (${hlsLink.duration}s)")
        downloadHlsToM4a(hlsLink.url, outputFile)
        println("Done: ${outputFile.absolutePath}")
    }

    /** Download HLS stream to M4A container (AAC stream copy, no transcoding). */
    fun downloadHlsToM4a(hlsUrl: String, outputFile: File) {
        runFfmpeg(
            "-i", hlsUrl,
            "-c", "copy",
            "-movflags", "+faststart",
            outputFile.absolutePath,
        )
    }

    /** Download a file from a URL using OkHttp. */
    fun downloadFile(url: String, outputFile: File) {
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("Download failed: ${response.code} for $url")
            }
            response.body.byteStream().use { input ->
                outputFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    /**
     * Combine multiple M4A episode files into a single M4B audiobook
     * with chapter markers and optional cover image.
     */
    fun combineToM4b(
        episodes: List<DownloadedEpisode>,
        bookTitle: String,
        coverFile: File?,
        outputFile: File,
    ) {
        val sorted = episodes.sortedBy { it.part }
        val parentDir = outputFile.parentFile

        // Create ffmpeg concat list
        val concatFile = File(parentDir, ".concat.txt")
        concatFile.writeText(sorted.joinToString("\n") { ep ->
            "file '${ep.m4aFile.absolutePath.replace("'", "'\\''")}'"
        })

        // Create FFMETADATA with chapters
        val metadataFile = File(parentDir, ".metadata.txt")
        val metadataBuilder = StringBuilder()
        metadataBuilder.appendLine(";FFMETADATA1")
        metadataBuilder.appendLine("title=${escapeMetadata(bookTitle)}")

        var startMs: Long = 0
        for (ep in sorted) {
            val endMs = startMs + ep.duration * 1000L
            metadataBuilder.appendLine()
            metadataBuilder.appendLine("[CHAPTER]")
            metadataBuilder.appendLine("TIMEBASE=1/1000")
            metadataBuilder.appendLine("START=$startMs")
            metadataBuilder.appendLine("END=$endMs")
            metadataBuilder.appendLine("title=${escapeMetadata(ep.title)}")
            startMs = endMs
        }
        metadataFile.writeText(metadataBuilder.toString())

        try {
            val args = mutableListOf(
                "-f", "concat", "-safe", "0", "-i", concatFile.absolutePath,
                "-f", "ffmetadata", "-i", metadataFile.absolutePath,
            )

            if (coverFile != null && coverFile.exists()) {
                args.addAll(listOf("-i", coverFile.absolutePath))
                args.addAll(listOf(
                    "-map", "0:a", "-map", "2:v",
                    "-c:a", "copy", "-c:v", "mjpeg",
                    "-disposition:v:0", "attached_pic",
                ))
            } else {
                args.addAll(listOf("-map", "0:a", "-c:a", "copy"))
            }

            args.addAll(listOf("-map_metadata", "1", outputFile.absolutePath))

            runFfmpeg(*args.toTypedArray())
        } finally {
            concatFile.delete()
            metadataFile.delete()
        }
    }

    private fun runFfmpeg(vararg args: String) {
        val command = listOf("ffmpeg", "-y", "-loglevel", "error") + args.toList()
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        val stderr = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            // Clean up output file (last argument) on failure
            val outputPath = args.lastOrNull()
            if (outputPath != null) File(outputPath).delete()
            throw RuntimeException("ffmpeg failed (exit $exitCode): $stderr")
        }
    }

    private fun isPlayable(link: AudioLink): Boolean {
        val till = link.playableTill ?: return true
        return try {
            val expiry = OffsetDateTime.parse(till, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            OffsetDateTime.now().isBefore(expiry)
        } catch (e: Exception) {
            true // If we can't parse the date, try anyway
        }
    }

    private fun escapeMetadata(value: String): String {
        return value.replace("\\", "\\\\").replace("=", "\\=").replace(";", "\\;").replace("#", "\\#").replace("\n", " ")
    }

    companion object {
        fun sanitizeFilename(name: String): String {
            return name.replace(Regex("[/\\\\<>:\"|?*]"), "_").trim()
        }
    }
}

/** Represents a successfully downloaded episode, used for M4B combining. */
data class DownloadedEpisode(
    val title: String,
    val part: Int,
    val duration: Int,
    val m4aFile: File,
)
