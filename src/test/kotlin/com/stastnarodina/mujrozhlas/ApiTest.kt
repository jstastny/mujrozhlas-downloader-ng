package com.stastnarodina.mujrozhlas

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: Api

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = Api(baseUrl = server.url("/").toString().trimEnd('/'))
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `searchSerials parses JSON API response`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "data": [
                        {
                          "type": "serial",
                          "id": "86006a3c-b4ec-38f6-b754-565cc18cbe17",
                          "attributes": {
                            "title": "Umberto Eco: Foucaultovo kyvadlo",
                            "totalParts": 5
                          },
                          "relationships": {}
                        }
                      ],
                      "meta": { "count": 1 }
                    }
                    """.trimIndent()
                )
        )

        val serials = api.searchSerials("foucaultovo kyvadlo")
        assertEquals(1, serials.size)
        assertEquals("Umberto Eco: Foucaultovo kyvadlo", serials[0].title)
        assertEquals("86006a3c-b4ec-38f6-b754-565cc18cbe17", serials[0].uuid)
        assertEquals(5, serials[0].totalParts)
    }

    @Test
    fun `getSerialEpisodes parses episodes with audio links`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "data": [
                        {
                          "type": "episode",
                          "id": "ff1cf4d9-7606-300b-bd31-98b8631f59af",
                          "attributes": {
                            "title": "Umberto Eco: Foucaultovo kyvadlo",
                            "part": 1,
                            "audioLinks": [
                              {
                                "url": "https://croaod.cz/stream/test.m4a/playlist.m3u8",
                                "variant": "hls",
                                "duration": 3205,
                                "bitrate": 128,
                                "playableTill": "2026-04-11T23:59:00+02:00"
                              }
                            ],
                            "mirroredSerial": {
                              "title": "Umberto Eco: Foucaultovo kyvadlo",
                              "totalParts": 5
                            }
                          },
                          "relationships": {
                            "serial": {
                              "data": {
                                "type": "serial",
                                "id": "86006a3c-b4ec-38f6-b754-565cc18cbe17"
                              }
                            }
                          }
                        }
                      ],
                      "meta": { "count": 1 }
                    }
                    """.trimIndent()
                )
        )

        val episodes = api.getSerialEpisodes("86006a3c-b4ec-38f6-b754-565cc18cbe17")
        assertEquals(1, episodes.size)

        val ep = episodes[0]
        assertEquals(1, ep.part)
        assertEquals(1, ep.audioLinks.size)
        assertEquals("hls", ep.audioLinks[0].variant)
        assertEquals(3205, ep.audioLinks[0].duration)
        assertEquals("86006a3c-b4ec-38f6-b754-565cc18cbe17", ep.serialUuid)
    }
}
