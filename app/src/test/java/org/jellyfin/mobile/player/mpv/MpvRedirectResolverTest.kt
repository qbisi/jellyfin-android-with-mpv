package org.jellyfin.mobile.player.mpv

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class MpvRedirectResolverTest {
    private companion object {
        const val AUTHORIZATION = "MediaBrowser Token=source-secret"
        const val MAX_REDIRECTS = 5
    }

    private lateinit var sourceServer: MockWebServer
    private lateinit var targetServer: MockWebServer

    @BeforeEach
    fun setUp() {
        sourceServer = MockWebServer()
        targetServer = MockWebServer()
        sourceServer.start()
        targetServer.start()
    }

    @AfterEach
    fun tearDown() {
        sourceServer.shutdown()
        targetServer.shutdown()
    }

    @Test
    fun `returns cross-origin location without contacting target`() {
        runBlocking {
            val targetUrl = targetServer.url("/media/video.mkv?signature=target-secret")
            sourceServer.enqueue(redirectResponse(targetUrl.toString()))
            val sourceUrl = sourceServer.url("/Videos/item/stream").toString()

            resolver().resolve(sourceUrl) shouldBe targetUrl.toString()

            val request = sourceServer.takeRequest()
            request.getHeader("Authorization") shouldBe AUTHORIZATION
            request.getHeader("Range") shouldBe "bytes=0-0"
            targetServer.requestCount shouldBe 0
        }
    }

    @Test
    fun `keeps authorization on same-origin redirects only`() {
        runBlocking {
            val targetUrl = targetServer.url("/media/video.mkv")
            sourceServer.enqueue(redirectResponse("/internal/stream"))
            sourceServer.enqueue(redirectResponse(targetUrl.toString()))
            val sourceUrl = sourceServer.url("/Videos/item/stream").toString()

            resolver().resolve(sourceUrl) shouldBe targetUrl.toString()

            repeat(2) {
                sourceServer.takeRequest().getHeader("Authorization") shouldBe AUTHORIZATION
            }
            targetServer.requestCount shouldBe 0
        }
    }

    @Test
    fun `retains original url when source does not redirect`() {
        runBlocking {
            sourceServer.enqueue(MockResponse().setResponseCode(206).setBody("x"))
            val sourceUrl = sourceServer.url("/Videos/item/stream").toString()

            resolver().resolve(sourceUrl) shouldBe sourceUrl
            sourceServer.requestCount shouldBe 1
            targetServer.requestCount shouldBe 0
        }
    }

    @Test
    fun `rejects cross-origin location containing user info`() {
        runBlocking {
            val targetUrl =
                targetServer
                    .url("/media/video.mkv")
                    .newBuilder()
                    .username("target-user")
                    .password("target-password")
                    .build()
            sourceServer.enqueue(redirectResponse(targetUrl.toString()))
            val sourceUrl = sourceServer.url("/Videos/item/stream").toString()

            resolver().resolve(sourceUrl) shouldBe sourceUrl
            sourceServer.requestCount shouldBe 1
            targetServer.requestCount shouldBe 0
        }
    }

    @Test
    fun `retains original url after redirect limit`() {
        runBlocking {
            repeat(MAX_REDIRECTS) { index ->
                sourceServer.enqueue(redirectResponse("/redirect/$index"))
            }
            val sourceUrl = sourceServer.url("/Videos/item/stream").toString()

            resolver().resolve(sourceUrl) shouldBe sourceUrl
            sourceServer.requestCount shouldBe MAX_REDIRECTS
            targetServer.requestCount shouldBe 0
        }
    }

    private fun resolver() =
        MpvRedirectResolver(
            httpClient = OkHttpClient
                .Builder()
                .callTimeout(2, TimeUnit.SECONDS)
                .build(),
            serverUrlProvider = { sourceServer.url("/").toString() },
            authorizationHeaderProvider = { AUTHORIZATION },
        )

    private fun redirectResponse(location: String) =
        MockResponse()
            .setResponseCode(302)
            .addHeader("Location", location)
}
