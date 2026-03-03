package com.stastnarodina.mujrozhlas

import kotlin.test.Test
import kotlin.test.assertEquals

class DownloaderTest {

    @Test
    fun `sanitizeFilename removes unsafe characters`() {
        assertEquals(
            "Umberto Eco_ Foucaultovo kyvadlo",
            Downloader.sanitizeFilename("Umberto Eco: Foucaultovo kyvadlo")
        )
    }

    @Test
    fun `sanitizeFilename handles slashes and special chars`() {
        assertEquals(
            "a_b_c_d",
            Downloader.sanitizeFilename("a/b\\c|d")
        )
    }

    @Test
    fun `sanitizeFilename preserves normal characters`() {
        assertEquals(
            "Normal Title 123",
            Downloader.sanitizeFilename("Normal Title 123")
        )
    }
}
