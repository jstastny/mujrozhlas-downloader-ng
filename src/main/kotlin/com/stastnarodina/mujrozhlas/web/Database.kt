package com.stastnarodina.mujrozhlas.web

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.javatime.timestamp
import java.io.File

enum class EpisodeStatus {
    PENDING, APPROVED, DOWNLOADING, DOWNLOADED, SKIPPED, ERROR
}

object Shows : Table("shows") {
    val uuid = varchar("uuid", 64)
    val title = varchar("title", 512)
    val imageUrl = varchar("image_url", 2048).nullable()
    val subscribed = bool("subscribed").default(false)
    val hidden = bool("hidden").default(false)
    val lastScanned = timestamp("last_scanned").nullable()

    override val primaryKey = PrimaryKey(uuid)
}

object Serials : Table("serials") {
    val uuid = varchar("uuid", 64)
    val showUuid = varchar("show_uuid", 64).references(Shows.uuid)
    val title = varchar("title", 512)
    val totalParts = integer("total_parts").default(0)
    val lastEpisodeSince = varchar("last_episode_since", 64).nullable()
    val imageUrl = varchar("image_url", 2048).nullable()
    val m4bPath = varchar("m4b_path", 1024).nullable()

    override val primaryKey = PrimaryKey(uuid)
}

object Episodes : Table("episodes") {
    val uuid = varchar("uuid", 64)
    val showUuid = varchar("show_uuid", 64).references(Shows.uuid)
    val serialUuid = varchar("serial_uuid", 64).references(Serials.uuid).nullable()
    val title = varchar("title", 512)
    val part = integer("part").nullable()
    val seriesEpisodeNumber = integer("series_episode_number").nullable()
    val status = enumerationByName<EpisodeStatus>("status", 20).default(EpisodeStatus.PENDING)
    val hlsUrl = varchar("hls_url", 2048).nullable()
    val duration = integer("duration").default(0)
    val playableTill = varchar("playable_till", 64).nullable()
    val discoveredAt = timestamp("discovered_at")
    val downloadedAt = timestamp("downloaded_at").nullable()
    val filePath = varchar("file_path", 1024).nullable()
    val errorMessage = varchar("error_message", 2048).nullable()

    override val primaryKey = PrimaryKey(uuid)
}

fun initDatabase(dbPath: String) {
    val dbFile = File(dbPath)
    dbFile.parentFile?.mkdirs()

    Database.connect("jdbc:sqlite:$dbPath", driver = "org.sqlite.JDBC")

    transaction {
        SchemaUtils.create(Shows, Serials, Episodes)
    }
}
