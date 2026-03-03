package cz.mujrozhlas.dl

import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

class Downloader {

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

        for (episode in serial.episodes) {
            val filename = "%02d - %s.m4a".format(episode.part, sanitizeFilename(serial.title))
            val outputFile = File(serialDir, filename)

            if (dryRun) {
                val hlsLink = episode.audioLinks.firstOrNull { it.variant == "hls" }
                val status = if (hlsLink != null && isPlayable(hlsLink)) "OK" else "UNAVAILABLE"
                println("  [$status] $filename")
                continue
            }

            try {
                downloadEpisode(episode, outputFile)
                downloaded++
            } catch (e: Exception) {
                System.err.println("  SKIP ${episode.title} (part ${episode.part}): ${e.message}")
                skipped++
            }
        }

        if (!dryRun) {
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

        downloadEpisode(episode, outputFile)
        println("Done: ${outputFile.absolutePath}")
    }

    private fun downloadEpisode(episode: Episode, outputFile: File) {
        val hlsLink = episode.audioLinks.firstOrNull { it.variant == "hls" }
            ?: throw RuntimeException("No HLS audio link for episode: ${episode.title}")

        if (!isPlayable(hlsLink)) {
            throw RuntimeException("Episode expired (playable till: ${hlsLink.playableTill})")
        }

        println("  Downloading: ${outputFile.name} (${hlsLink.duration}s)")
        downloadEpisodeByUrl(hlsLink.url, outputFile)
    }

    fun downloadEpisodeByUrl(hlsUrl: String, outputFile: File) {
        val process = ProcessBuilder(
            "ffmpeg", "-y",
            "-loglevel", "error",
            "-i", hlsUrl,
            "-c", "copy",
            "-movflags", "+faststart",
            outputFile.absolutePath,
        )
            .redirectErrorStream(true)
            .start()

        val stderr = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            outputFile.delete()
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

    companion object {
        fun sanitizeFilename(name: String): String {
            return name.replace(Regex("[/\\\\<>:\"|?*]"), "_").trim()
        }
    }
}
