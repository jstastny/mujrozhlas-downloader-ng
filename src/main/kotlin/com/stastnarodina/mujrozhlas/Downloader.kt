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

    /** Download all episodes of a serial, then combine into M4B. */
    fun downloadSerial(serial: Serial, outputDir: File, dryRun: Boolean) {
        val serialDir = File(outputDir, sanitizeFilename(serial.title))
        if (!dryRun) serialDir.mkdirs()

        println("Serial: ${serial.title} (${serial.episodes.size} episodes)")
        println("Output: ${serialDir.absolutePath}")
        println()

        val padWidth = digitCount(serial.episodes.size.coerceAtLeast(serial.totalParts))
        var downloaded = 0
        var skipped = 0
        val downloadedEpisodes = mutableListOf<DownloadedEpisode>()

        for (episode in serial.episodes) {
            val num = episode.orderNumber
            val baseName = "%0${padWidth}d - %s".format(num, sanitizeFilename(serial.title))
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
                    number = num,
                    duration = hlsLink.duration,
                    audioFile = m4aFile,
                ))
                downloaded++
            } catch (e: Exception) {
                System.err.println("  SKIP ${episode.title} (part ${num}): ${e.message}")
                skipped++
            }
        }

        if (!dryRun && downloadedEpisodes.isNotEmpty()) {
            val coverFile = downloadCover(serial.imageUrl, serialDir)

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

    /** Download all direct episodes of a show (no serial grouping). */
    fun downloadShowEpisodes(show: Show, outputDir: File, dryRun: Boolean) {
        val showDir = File(outputDir, sanitizeFilename(show.title))
        if (!dryRun) showDir.mkdirs()

        println("Show: ${show.title} (${show.episodes.size} episodes)")
        println("Output: ${showDir.absolutePath}")
        println()

        val padWidth = digitCount(show.episodes.size)
        var downloaded = 0
        var skipped = 0

        for (episode in show.episodes) {
            val num = episode.orderNumber
            val baseName = "%0${padWidth}d - %s".format(num, sanitizeFilename(show.title))
            val m4aFile = File(showDir, "$baseName.m4a")

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
                downloaded++
            } catch (e: Exception) {
                System.err.println("  SKIP ${episode.title} (#$num): ${e.message}")
                skipped++
            }
        }

        if (!dryRun) {
            println()
            println("Done: $downloaded downloaded, $skipped skipped")
        }
    }

    fun downloadSingleEpisode(episode: Episode, outputDir: File, dryRun: Boolean) {
        val title = episode.serialTitle ?: episode.showTitle ?: episode.title
        val num = episode.orderNumber
        val filename = if (num > 0) {
            "%03d - %s.m4a".format(num, sanitizeFilename(title))
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
        val sorted = episodes.sortedBy { it.number }
        val parentDir = outputFile.parentFile

        val concatFile = File(parentDir, ".concat.txt")
        concatFile.writeText(sorted.joinToString("\n") { ep ->
            "file '${ep.audioFile.absolutePath.replace("'", "'\\''")}'"
        })

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

            // If any source file is not AAC (e.g. MP3), we need to transcode
            val needsTranscode = sorted.any { !it.audioFile.name.endsWith(".m4a") }
            val audioCodec = if (needsTranscode) listOf("-c:a", "aac", "-b:a", "128k") else listOf("-c:a", "copy")

            if (coverFile != null && coverFile.exists()) {
                args.addAll(listOf("-i", coverFile.absolutePath))
                args.addAll(listOf("-map", "0:a", "-map", "2:v"))
                args.addAll(audioCodec)
                args.addAll(listOf("-c:v", "mjpeg", "-disposition:v:0", "attached_pic"))
            } else {
                args.addAll(listOf("-map", "0:a"))
                args.addAll(audioCodec)
            }

            args.addAll(listOf("-map_metadata", "1", outputFile.absolutePath))

            runFfmpeg(*args.toTypedArray())
        } finally {
            concatFile.delete()
            metadataFile.delete()
        }
    }

    private fun downloadCover(imageUrl: String?, targetDir: File): File? {
        if (imageUrl == null) return null
        val file = File(targetDir, "cover.jpg")
        return try {
            downloadFile(imageUrl, file)
            println("  Cover image saved")
            file
        } catch (e: Exception) {
            System.err.println("  Cover image download failed: ${e.message}")
            null
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
            true
        }
    }

    private fun escapeMetadata(value: String): String {
        return value.replace("\\", "\\\\").replace("=", "\\=").replace(";", "\\;").replace("#", "\\#").replace("\n", " ")
    }

    companion object {
        fun sanitizeFilename(name: String): String {
            return name.replace(Regex("[/\\\\<>:\"|?*]"), "_").trim()
        }

        /** Number of digits needed to represent [n] (minimum 2). */
        fun digitCount(n: Int): Int = maxOf(2, n.toString().length)
    }
}

/** Represents a successfully downloaded episode, used for M4B combining. */
data class DownloadedEpisode(
    val title: String,
    val number: Int,
    val duration: Int,
    val audioFile: File,
)
