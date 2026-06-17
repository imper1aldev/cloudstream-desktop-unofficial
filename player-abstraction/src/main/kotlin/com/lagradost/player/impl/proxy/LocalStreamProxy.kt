package com.lagradost.player.impl.proxy

import com.lagradost.cloudstream3.app
import com.lagradost.common.logging.AppLogger
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import java.io.IOException
import java.net.URI
import java.util.Base64
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response)
        }
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isCancelled) return
            continuation.resumeWithException(e)
        }
    })
    continuation.invokeOnCancellation {
        try {
            cancel()
        } catch (ex: Throwable) {}
    }
}

data class ProxyTrack(
    val url: String,
    val name: String,
    val language: String,
)

private val BW_REGEX = Regex("""BANDWIDTH=(\d+)""")
private val RES_REGEX = Regex("""RESOLUTION=(\d+)x(\d+)""")
private val URI_REGEX = Regex("""URI="([^"]+)"""")
private val NAME_REGEX = Regex("""NAME="([^"]+)"""")
private val LANG_REGEX = Regex("""LANGUAGE="([^"]+)"""")

object LocalStreamProxyState {
    val loadingStatus = MutableStateFlow<String?>(null)
    val lazyAudioTracks = MutableStateFlow<List<ProxyTrack>>(emptyList())
    val lazySubtitleTracks = MutableStateFlow<List<ProxyTrack>>(emptyList())
    val lazyVideoTracks = MutableStateFlow<List<ProxyTrack>>(emptyList())
}

object LocalStreamProxy {
    // Use Kotlin's dynamically scaling IO dispatcher instead of hoarding 500 OS threads
    private val ProxyIoDispatcher = java.util.concurrent.Executors.newCachedThreadPool().asCoroutineDispatcher()

    private var server: io.ktor.server.engine.EmbeddedServer<*, *>? = null
    var port: Int = 0
        private set

    data class ProxySession(
        val headers: Map<String, String>,
        val m3u8Cache: java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<String?>> = java.util.concurrent.ConcurrentHashMap(),
    )

    // Capped LRU cache to prevent memory leaks from abandoned video sessions
    private val sessions = java.util.Collections.synchronizedMap(
        object : java.util.LinkedHashMap<String, ProxySession>(100, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, ProxySession>): Boolean {
                return size > 100
            }
        },
    )

    private val proxyClient by lazy {
        app.baseClient.newBuilder()
            .connectTimeout(5000L, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(15000L, java.util.concurrent.TimeUnit.MILLISECONDS)
            .dispatcher(
                okhttp3.Dispatcher().apply {
                    maxRequests = 64
                    // With HLS stripping, MPV only sees 2 streams (1 video + 1 audio),
                    // so we can safely allow more concurrent CDN connections for faster buffering.
                    maxRequestsPerHost = 8
                },
            )
            .build()
    }

    fun start() {
        if (server != null) return
        server = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
            routing {
                get("/proxy") {
                    handleRequest(call)
                }
            }
        }.start(wait = false)

        port = kotlinx.coroutines.runBlocking {
            server?.engine?.resolvedConnectors()?.firstOrNull()?.port ?: 0
        }
        AppLogger.i("LocalStreamProxy started on port $port")
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
        sessions.clear()
    }

    fun registerSession(headers: Map<String, String>): String {
        val sessionId = UUID.randomUUID().toString()
        sessions[sessionId] = ProxySession(headers)

        // Clear previous session tracks to prevent ghost subtitles from showing in the UI for the new stream
        LocalStreamProxyState.lazyAudioTracks.value = emptyList()
        LocalStreamProxyState.lazySubtitleTracks.value = emptyList()

        return sessionId
    }

    fun buildProxyUrl(sessionId: String, url: String): String {
        val encodedUrl = Base64.getUrlEncoder().withoutPadding().encodeToString(url.toByteArray(Charsets.UTF_8))
        return "http://127.0.0.1:$port/proxy?s=$sessionId&u=$encodedUrl"
    }

    private fun prefetchM3u8(url: String, session: ProxySession, sessionId: String): kotlinx.coroutines.Deferred<String?> {
        return session.m3u8Cache.computeIfAbsent(url) {
            GlobalScope.async(ProxyIoDispatcher) {
                try {
                    val requestBuilder = okhttp3.Request.Builder().url(url)
                    session.headers.forEach { (k, v) ->
                        if (!k.equals("Accept-Encoding", ignoreCase = true)) {
                            requestBuilder.header(k, v)
                        }
                    }

                    var response: okhttp3.Response? = null
                    for (attempt in 1..4) {
                        try {
                            response = proxyClient.newCall(requestBuilder.build()).await()
                            if (response.isSuccessful) break
                        } catch (e: Exception) {
                            if (attempt == 4) return@async null
                        }
                        if (response != null && !response.isSuccessful && attempt < 4) {
                            response.body?.close()
                            kotlinx.coroutines.delay(200L * attempt)
                        } else if (response != null && !response.isSuccessful) {
                            response.body?.close()
                            return@async null
                        }
                    }

                    if (response != null && response.isSuccessful) {
                        val content = response.body?.string() ?: ""
                        val finalUrl = response.request.url.toString()
                        rewriteM3u8(content, finalUrl, session, sessionId)
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    private suspend fun handleRequest(call: io.ktor.server.application.ApplicationCall) {
        try {
            val sessionId = call.request.queryParameters["s"]
            val encodedUrl = call.request.queryParameters["u"]

            if (sessionId == null || encodedUrl == null) {
                call.respond(HttpStatusCode.NotFound)
                return
            }

            val url = String(Base64.getUrlDecoder().decode(encodedUrl), Charsets.UTF_8)
            val session = sessions[sessionId]

            if (session == null) {
                call.respond(HttpStatusCode.NotFound)
                return
            }

            val cachedDeferred = session.m3u8Cache.remove(url)
            if (cachedDeferred != null) {
                val cachedM3u8 = cachedDeferred.await()
                if (cachedM3u8 != null) {
                    val bytes = cachedM3u8.toByteArray(Charsets.UTF_8)
                    call.response.header("Content-Type", "application/vnd.apple.mpegurl")
                    call.respondBytes(bytes, status = HttpStatusCode.OK)
                    return
                }
            }

            val mergedHeaders = session.headers.toMutableMap()

            val keysToRemove = mergedHeaders.keys.filter { it.equals("Accept-Encoding", ignoreCase = true) }
            keysToRemove.forEach { mergedHeaders.remove(it) }
            
            // Explicitly request identity encoding to prevent OkHttp from transparently adding Accept-Encoding: gzip.
            // If OkHttp uses gzip, it strips the Content-Length header, forcing Ktor to use Chunked Transfer Encoding,
            // which breaks FFmpeg's HLS keep-alive parsing and corrupts the video sync!
            mergedHeaders["Accept-Encoding"] = "identity"

            call.request.headers["Range"]?.let {
                mergedHeaders["Range"] = it
            }

            val requestBuilder = okhttp3.Request.Builder().url(url)
            mergedHeaders.forEach { (k, v) -> requestBuilder.header(k, v) }

            // Use completely async OkHttp fetch with internal retries to prevent ThreadPool exhaustion
            // and handle CDN connection drops smoothly without breaking FFmpeg.
            var response: okhttp3.Response? = null
            var lastError: Exception? = null
            for (attempt in 1..4) {
                try {
                    response = proxyClient.newCall(requestBuilder.build()).await()
                    if (response.isSuccessful) break
                } catch (e: Exception) {
                    lastError = e
                }
                if (attempt < 4) {
                    response?.body?.close()
                    kotlinx.coroutines.delay(200L * attempt)
                }
            }

            if (response == null) {
                AppLogger.e("LocalStreamProxy Request Failed after 4 attempts! URL: $url Error: ${lastError?.message}")
                call.respond(HttpStatusCode.InternalServerError)
                return
            }

            if (!response.isSuccessful) {
                AppLogger.e("LocalStreamProxy Request Failed! Code: ${response.code} URL: $url")
                response.body?.close()
                call.respond(HttpStatusCode.fromValue(response.code))
                return
            }

            val upstreamContentType = response.header("Content-Type") ?: ""
            // CDNs disguise MPEG-TS/AAC segments as .jpg, .js, etc. to evade hotlink protection.
            // FFmpeg's HLS demuxer checks the MIME type and rejects non-media types like
            // 'application/javascript' or 'image/jpeg' even if the binary content is valid TS.
            // Normalize any non-media, non-m3u8 type to application/octet-stream so FFmpeg
            // always tries to decode the actual binary content.
            val rawContentType = upstreamContentType.ifBlank { "application/octet-stream" }
            val isNonMediaType = rawContentType.contains("javascript", ignoreCase = true) ||
                rawContentType.contains("text/", ignoreCase = true) ||
                (rawContentType.contains("image/", ignoreCase = true) && !rawContentType.contains("mpegurl", ignoreCase = true))
            val contentTypeStr = if (isNonMediaType) "application/octet-stream" else rawContentType
            val isM3u8 = url.contains(".m3u8", ignoreCase = true) ||
                url.contains(".m3u", ignoreCase = true) ||
                rawContentType.contains("mpegurl", ignoreCase = true) ||
                rawContentType.contains("x-mpegURL", ignoreCase = true) ||
                withContext(ProxyIoDispatcher) {
                    try {
                        val s = response.body?.source()
                        s != null && s.request(7) && s.peek().readUtf8(7) == "#EXTM3U"
                    } catch (e: Exception) {
                        false
                    }
                }

            if (isM3u8) {
                val m3u8Content = withContext(ProxyIoDispatcher) {
                    response.body?.string() ?: ""
                }
                val finalUrl = response.request.url.toString()

                val rewritten = rewriteM3u8(m3u8Content, finalUrl, session, sessionId)

                val bytes = rewritten.toByteArray(Charsets.UTF_8)

                call.response.header("Content-Type", "application/vnd.apple.mpegurl")
                call.respondBytes(bytes, status = HttpStatusCode.OK)
            } else {
                response.header("Content-Range")?.let { call.response.header("Content-Range", it) }
                response.header("Accept-Ranges")?.let { call.response.header("Accept-Ranges", it) }

                val cl = response.body?.contentLength() ?: -1L
                val contentLengthParam = if (cl >= 0) cl else null

                val parsedContentType = try {
                    ContentType.parse(contentTypeStr)
                } catch (e: Exception) {
                    ContentType.Application.OctetStream
                }

                // We stream the data exactly as-is. Since we forced Accept-Encoding: identity, 
                // the CDN provides the exact Content-Length. Passing it to Ktor prevents 
                // Chunked Transfer Encoding entirely, while maintaining instant Time-To-First-Byte streaming!
                //
                // CRITICAL ASYNCHRONOUS BUFFERING:
                // We MUST decouple the CDN download speed from FFmpeg's consumption speed.
                // If we stream directly (using a simple loop), and FFmpeg's buffer fills up, FFmpeg stops reading.
                // This stalls Ktor, which stalls OkHttp, which stalls the CDN TCP connection.
                // CDNs aggressively drop idle connections, causing playback to fail!
                // By using an unlimited Channel, we download the segment into RAM at maximum CDN speed 
                // in the background, keeping the CDN socket blazing fast so it never gets dropped!
                call.respondBytesWriter(
                    contentType = parsedContentType,
                    status = HttpStatusCode.fromValue(response.code),
                    contentLength = contentLengthParam,
                ) {
                    val streamSource = response.body?.source() ?: return@respondBytesWriter
                    val ktorChannel = this

                    val buffer = ByteArray(65536) // 64KB chunks
                    try {
                        while (!ktorChannel.isClosedForWrite) {
                            val bytesRead = withContext(ProxyIoDispatcher) {
                                streamSource.read(buffer)
                            }
                            if (bytesRead == -1) break
                            
                            // Write directly to Ktor. Ktor's internal buffer will automatically suspend this coroutine 
                            // if it's full (i.e. MPV isn't reading fast enough), applying natural backpressure to the network!
                            ktorChannel.writeFully(buffer, 0, bytesRead)
                        }
                    } catch (e: Exception) {
                        // Ignored (Client disconnected, e.g. user seeking)
                    } finally {
                        withContext(ProxyIoDispatcher) {
                            response.body?.close()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e("LocalStreamProxy error: ${e.message}")
            try {
                call.respond(HttpStatusCode.InternalServerError)
            } catch (_: Exception) {}
        }
    }

    private fun rewriteM3u8(content: String, baseUrl: String, session: ProxySession, sessionId: String): String {
        val lines = content.split("\n")
        val isMaster = content.contains("#EXT-X-STREAM-INF")

        var nextLineIsVariantUrl = false

        if (isMaster) {
            val lazyAudios = mutableListOf<ProxyTrack>()
            val lazySubs = mutableListOf<ProxyTrack>()
            val lazyVideoTracks = mutableListOf<ProxyTrack>()
            var hasKeptAudio = false

            // Pass 1: Find best video variant and default audio variant
            var maxScore = -1
            var bestVariantUrl: String? = null
            var currentVariantLine: String? = null
            var bestAudioUrl: String? = null
            var firstAudioUrl: String? = null

            for (line in lines) {
                val trim = line.trim()
                if (trim.startsWith("#EXT-X-STREAM-INF")) {
                    currentVariantLine = trim
                } else if (currentVariantLine != null && !trim.startsWith("#")) {
                    val bwMatch = BW_REGEX.find(currentVariantLine)
                    val resMatch = RES_REGEX.find(currentVariantLine)
                    val bw = bwMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val res = resMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val score = res * 1000000 + bw
                    if (score > maxScore) {
                        maxScore = score
                        bestVariantUrl = trim
                    }
                    currentVariantLine = null
                } else if (trim.startsWith("#EXT-X-MEDIA:TYPE=AUDIO")) {
                    val uriMatch = URI_REGEX.find(trim)
                    if (uriMatch != null) {
                        val uri = uriMatch.groupValues[1]
                        if (firstAudioUrl == null) firstAudioUrl = uri
                        if (trim.contains("DEFAULT=YES", ignoreCase = true)) {
                            bestAudioUrl = uri
                        }
                    }
                }
            }

            if (bestAudioUrl == null) bestAudioUrl = firstAudioUrl

            // Pass 2: Reconstruct playlist keeping only best video variant, ONE audio variant, and ALL subtitles
            val rewritten = buildString {
                var pendingVariantLine: String? = null

                for (line in lines) {
                    val trim = line.trim()
                    if (trim.isEmpty()) continue

                    if (trim.startsWith("#EXT-X-MEDIA:TYPE=SUBTITLES")) {
                        // Strip ALL subtitles — FFmpeg aggressively probes every single one at startup!
                        val name = NAME_REGEX.find(trim)?.groupValues?.get(1) ?: "Unknown Sub"
                        val lang = LANG_REGEX.find(trim)?.groupValues?.get(1) ?: "unk"
                        val uriMatch = URI_REGEX.find(trim)
                        if (uriMatch != null) {
                            val absolute = resolveUrl(baseUrl, uriMatch.groupValues[1])
                            val proxyUrl = buildProxyUrl(sessionId, absolute)
                            lazySubs.add(ProxyTrack(proxyUrl, name, lang))
                        }
                        continue
                    }

                    if (trim.startsWith("#EXT-X-MEDIA:TYPE=AUDIO")) {
                        val name = NAME_REGEX.find(trim)?.groupValues?.get(1) ?: "Unknown Audio"
                        val lang = LANG_REGEX.find(trim)?.groupValues?.get(1) ?: "unk"
                        val uriMatch = URI_REGEX.find(trim)

                        if (uriMatch != null) {
                            val uri = uriMatch.groupValues[1]
                            val absolute = resolveUrl(baseUrl, uri)
                            val proxyUrl = buildProxyUrl(sessionId, absolute)

                            if (uri == bestAudioUrl) {
                                val newLine = trim.replace(uriMatch.groupValues[0], "URI=\"$proxyUrl\"")
                                appendLine(newLine)
                            } else {
                                lazyAudios.add(ProxyTrack(proxyUrl, name, lang))
                            }
                        } else {
                            // If there is no URI, it's embedded in the video stream, keep it
                            appendLine(trim)
                        }
                        continue
                    }

                    if (trim.startsWith("#EXT-X-STREAM-INF")) {
                        pendingVariantLine = trim
                        continue
                    }

                    if (trim.startsWith("#")) {
                        if (trim.contains("URI=\"")) {
                            val newLine = trim.replace(URI_REGEX) { result ->
                                val uri = result.groupValues[1]
                                val absolute = resolveUrl(baseUrl, uri)
                                "URI=\"${buildProxyUrl(sessionId, absolute)}\""
                            }
                            appendLine(newLine)
                        } else {
                            appendLine(trim)
                        }
                    } else {
                        // URL line
                        if (pendingVariantLine != null) {
                            val absolute = resolveUrl(baseUrl, trim)
                            val proxyUrl = buildProxyUrl(sessionId, absolute)

                            if (trim == bestVariantUrl) {
                                // Keep this variant in the proxy M3U8
                                appendLine(pendingVariantLine)
                                appendLine(proxyUrl)
                            } else {
                                // Strip it from the M3U8, but expose it via lazyVideoTracks!
                                val bwMatch = BW_REGEX.find(pendingVariantLine)
                                val resMatch = RES_REGEX.find(pendingVariantLine)
                                val res = resMatch?.groupValues?.get(1) ?: "Unknown"
                                val name = if (res != "Unknown") "${res}p" else "Variant"
                                lazyVideoTracks.add(ProxyTrack(proxyUrl, name, "eng"))
                            }
                            pendingVariantLine = null
                        } else {
                            // Non-variant URL line (rare in Master playlist, but just in case)
                            val absolute = resolveUrl(baseUrl, trim)
                            appendLine(buildProxyUrl(sessionId, absolute))
                        }
                    }
                }
            }

            LocalStreamProxyState.lazyAudioTracks.value = lazyAudios
            LocalStreamProxyState.lazySubtitleTracks.value = lazySubs
            LocalStreamProxyState.lazyVideoTracks.value = lazyVideoTracks
            LocalStreamProxyState.loadingStatus.value = null // Hide loading toast since we stripped them!

            return rewritten
        }

        // --- Non-Master Playlist Processing ---
        val rewritten = buildString {
            for (line in lines) {
                val trim = line.trim()
                if (trim.isEmpty()) continue

                if (trim.startsWith("#")) {
                    nextLineIsVariantUrl = trim.startsWith("#EXT-X-STREAM-INF")

                    if (trim.contains("URI=\"")) {
                        val uriRegex = Regex("""URI="([^"]+)"""")
                        val newLine = trim.replace(uriRegex) { result ->
                            val uri = result.groupValues[1]
                            val absolute = resolveUrl(baseUrl, uri)
                            "URI=\"${buildProxyUrl(sessionId, absolute)}\""
                        }
                        appendLine(newLine)
                    } else {
                        appendLine(trim)
                    }
                } else {
                    if (nextLineIsVariantUrl) {
                        val absolute = resolveUrl(baseUrl, trim)
                        appendLine(buildProxyUrl(sessionId, absolute))
                        nextLineIsVariantUrl = false
                    } else {
                        val absolute = resolveUrl(baseUrl, trim)
                        appendLine(buildProxyUrl(sessionId, absolute))
                    }
                }
            }
        }
        return rewritten
    }

    private fun resolveUrl(base: String, uri: String): String {
        if (uri.startsWith("http://", ignoreCase = true) || uri.startsWith("https://", ignoreCase = true)) {
            return uri
        }

        val baseUrl = base.toHttpUrlOrNull()
        if (baseUrl != null) {
            val resolved = baseUrl.resolve(uri)
            if (resolved != null) {
                val resolvedStr = resolved.toString()
                if (baseUrl.query != null && resolved.query == null) {
                    return "$resolvedStr?${baseUrl.query}"
                }
                return resolvedStr
            }
        }

        return try {
            val baseUri = URI(base)
            val resolved = baseUri.resolve(uri).toString()

            // Inherit query parameters from the base URL (for auth tokens like md5/expires)
            if (baseUri.query != null && !resolved.contains("?")) {
                "$resolved?${baseUri.query}"
            } else {
                resolved
            }
        } catch (e: Exception) {
            if (base.contains("/")) {
                base.substringBeforeLast('/') + "/" + uri
            } else {
                uri
            }
        }
    }
}
