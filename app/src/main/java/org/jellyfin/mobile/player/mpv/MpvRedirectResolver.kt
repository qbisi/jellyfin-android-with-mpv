package org.jellyfin.mobile.player.mpv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import timber.log.Timber
import java.io.IOException

class MpvRedirectResolver(
    httpClient: OkHttpClient,
    private val serverUrlProvider: () -> String?,
    private val authorizationHeaderProvider: () -> String?,
) {
    private companion object {
        const val MAX_REDIRECTS = 5
        const val NANOSECONDS_PER_MILLISECOND = 1_000_000L
        val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
    }

    constructor(httpClient: OkHttpClient, apiClient: ApiClient) : this(
        httpClient = httpClient,
        serverUrlProvider = { apiClient.baseUrl },
        authorizationHeaderProvider = {
            AuthorizationHeaderBuilder.buildHeader(
                clientName = apiClient.clientInfo.name,
                clientVersion = apiClient.clientInfo.version,
                deviceId = apiClient.deviceInfo.id,
                deviceName = apiClient.deviceInfo.name,
                accessToken = apiClient.accessToken,
            )
        },
    )

    private val redirectClient =
        httpClient
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

    suspend fun resolve(url: String): String =
        withContext(Dispatchers.IO) {
            resolveBlocking(url)
        }

    @Suppress("ReturnCount")
    private fun resolveBlocking(url: String): String {
        val startedAtMs = monotonicTimeMs()
        val originalUrl = url.toHttpUrlOrNull() ?: return url
        val serverUrl = serverUrlProvider()?.toHttpUrlOrNull() ?: return url
        if (!originalUrl.hasSameOrigin(serverUrl)) return url

        var currentUrl = originalUrl
        try {
            repeat(MAX_REDIRECTS) { redirectIndex ->
                val request =
                    Request
                        .Builder()
                        .url(currentUrl)
                        .header("Range", "bytes=0-0")
                        .apply {
                            authorizationHeaderProvider()?.let { header("Authorization", it) }
                        }.build()

                redirectClient.newCall(request).execute().use { response ->
                    if (response.code !in REDIRECT_STATUS_CODES) return url
                    val location = response.header("Location") ?: return url
                    val nextUrl = currentUrl.resolve(location) ?: return url
                    if (!nextUrl.isSafeRedirectFrom(currentUrl)) return url

                    if (!nextUrl.hasSameOrigin(originalUrl)) {
                        Timber.i(
                            "MPV redirect-resolved redirects=%d elapsedMs=%d",
                            redirectIndex + 1,
                            monotonicTimeMs() - startedAtMs,
                        )
                        return nextUrl.toString()
                    }
                    currentUrl = nextUrl
                }
            }
        } catch (error: IOException) {
            Timber.w("MPV redirect resolution failed reason=%s", error::class.simpleName)
        }
        return url
    }

    private fun HttpUrl.hasSameOrigin(other: HttpUrl): Boolean =
        scheme == other.scheme && host == other.host && port == other.port

    private fun HttpUrl.isSafeRedirectFrom(source: HttpUrl): Boolean =
        !(source.scheme == "https" && scheme != "https") && username.isEmpty() && password.isEmpty()

    private fun monotonicTimeMs(): Long = System.nanoTime() / NANOSECONDS_PER_MILLISECOND
}
