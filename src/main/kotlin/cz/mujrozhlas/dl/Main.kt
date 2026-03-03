package cz.mujrozhlas.dl

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import java.io.File

class MujRozhlasDl : CliktCommand(
    name = "mujrozhlas-dl",
) {
    private val url by argument(help = "URL from mujrozhlas.cz (serial or episode)")
    private val outputDir by option("-o", "--output", help = "Output directory").default(".")
    private val dryRun by option("--dry-run", help = "Show what would be downloaded").flag()

    override fun run() {
        val api = Api()
        val resolver = Resolver(api)
        val downloader = Downloader()

        if (!dryRun) {
            downloader.checkFfmpeg()
        }

        val outDir = File(outputDir)
        if (!dryRun) {
            outDir.mkdirs()
        }

        when (val result = resolver.resolve(url)) {
            is ResolvedResult.SerialResult -> {
                downloader.downloadSerial(result.serial, outDir, dryRun)
            }
            is ResolvedResult.EpisodeResult -> {
                val episode = result.episode
                // If episode belongs to a serial, resolve the full serial
                if (episode.serialUuid != null) {
                    println("Episode belongs to a serial, fetching all episodes...")
                    val serial = api.getSerial(episode.serialUuid)
                    val episodes = api.getSerialEpisodes(episode.serialUuid)
                    downloader.downloadSerial(serial.copy(episodes = episodes), outDir, dryRun)
                } else {
                    downloader.downloadSingleEpisode(episode, outDir, dryRun)
                }
            }
        }
    }
}

fun main(args: Array<String>) = MujRozhlasDl().main(args)
