@file:OptIn(com.lagradost.cloudstream3.Prerelease::class, com.lagradost.cloudstream3.UnsafeSSL::class)

package com.lagradost.cloudstream3.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import coil3.compose.setSingletonImageLoaderFactory
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.desktop.repo.DesktopRepositoryManager
import com.lagradost.cloudstream3.desktop.ui.CloudstreamApp
import com.lagradost.cloudstream3.desktop.utils.appScope
import com.lagradost.cloudstream3.metaproviders.CrossTmdbProvider
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.metaproviders.TraktProvider
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.player.impl.proxy.LocalStreamProxy
import com.lagradost.runtime.loader.ExtensionLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.Path.Companion.toOkioPath
import java.io.File

/**
 * Single unified entry point for CloudStream Desktop Client.
 *
 * Initialization order (matches Android startup semantics):
 *   1. OkHttp global clients (timeouts + HTTP/1.1)
 *   2. Uncaught exception handler
 *   3. BouncyCastle security provider (Android AES-GCM compat)
 *   4. Built-in meta-providers (TMDB, Trakt, CrossTMDB)
 *   5. Load installed plugins (dex2jar conversion)
 *   6. Background auto-updater
 *   7. Compose UI window
 */
fun main() {
    AppLogger.i("Launching CloudStream Desktop Client...")
    AppLogger.i("Platform: ${PlatformPaths.currentOS}")
    AppLogger.i("App data directory: ${PlatformPaths.appDataDir.absolutePath}")

    // ── 2. Initialize Proxy Server ──────────────────────────
    LocalStreamProxy.start()

    // ── 2. Uncaught exception handler ──────────────────────────────────
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        DesktopErrorReporter.report("Unhandled exception in ${thread.name}", throwable)
    }

    // ── 3. Security Providers ──
    java.security.Security.insertProviderAt(org.bouncycastle.jce.provider.BouncyCastleProvider(), 1)
    AppLogger.i("Registered BouncyCastle Security Provider")
    // Conscrypt removed to prevent SocketException on JVM/Windows

    // ── 3.5 Pre-initialize DataStore ──────────────────────────────────
    // Force initialization of DataStore BEFORE plugins are loaded.
    // This prevents plugins from triggering <clinit> which causes the SecurityManager to block File.mkdirs()
    com.lagradost.common.storage.DesktopDataStore.init()

    // ── 3.6 Initialize global NiceHttp clients ────────────────────────
    com.lagradost.cloudstream3.desktop.network.NetworkConfig.updateGlobalNetworkClients()

    // ── 3.7 Patch Jackson mapper for dex2jar Kotlin reflection bugs ───
    val mapper = com.lagradost.cloudstream3.mapper

    // We register a custom deserializer for VerifiedRepo to bypass Jackson's
    // KotlinReflectionInternalError on dex2jar'd inner data classes.
    val fallbackModule = object : com.fasterxml.jackson.databind.module.SimpleModule() {
        override fun setupModule(context: SetupContext) {
            super.setupModule(context)
            context.addDeserializers(object : com.fasterxml.jackson.databind.deser.Deserializers.Base() {
                override fun findBeanDeserializer(
                    type: com.fasterxml.jackson.databind.JavaType,
                    config: com.fasterxml.jackson.databind.DeserializationConfig,
                    beanDesc: com.fasterxml.jackson.databind.BeanDescription,
                ): com.fasterxml.jackson.databind.JsonDeserializer<*>? {
                    if (type.rawClass.name.contains("VerifiedRepo")) {
                        return object : com.fasterxml.jackson.databind.JsonDeserializer<Any>() {
                            override fun deserialize(
                                p: com.fasterxml.jackson.core.JsonParser,
                                ctxt: com.fasterxml.jackson.databind.DeserializationContext,
                            ): Any? {
                                val node = p.codec.readTree<com.fasterxml.jackson.databind.JsonNode>(p)
                                val name = node.get("name")?.asText() ?: ""
                                val url = node.get("url")?.asText() ?: ""

                                try {
                                    val clazz = type.rawClass
                                    var instance: Any? = null

                                    val constructors = clazz.constructors
                                    for (c in constructors) {
                                        val params = c.parameterTypes
                                        val args = arrayOfNulls<Any>(params.size)
                                        for (i in params.indices) {
                                            if (params[i] == String::class.java) {
                                                if (i == 0) args[i] = url else args[i] = name
                                            } else {
                                                args[i] = when (params[i]) {
                                                    Int::class.java -> 0
                                                    Boolean::class.java -> false
                                                    else -> null
                                                }
                                            }
                                        }
                                        instance = try {
                                            c.newInstance(*args)
                                        } catch (e: Exception) {
                                            null
                                        }
                                        if (instance != null) break
                                    }

                                    if (instance == null) {
                                        println("FATAL: Could not instantiate VerifiedRepo using constructors! Count: ${constructors.size}")
                                    }
                                    return instance
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    return null
                                }
                            }
                        }
                    }
                    return null
                }
            })
        }
    }
    mapper.registerModule(fallbackModule)

    // ── 4. Built-in meta-providers ─────────────────────────────────────
    registerBuiltInProviders()

    // ── 4.5 Initialize WebViewResolver ──────────────────────────────────
    com.lagradost.cloudstream3.network.WebViewResolver.webViewHandler = { request, callback ->
        com.lagradost.cloudstream3.desktop.network.PlaywrightResolverImpl.resolve(request, callback)
    }

    // Bind the raw WebView stub to Playwright
    android.webkit.WebView.loadUrlHandler = java.util.function.Consumer { url ->
        appScope.launch {
            com.lagradost.cloudstream3.network.WebViewResolver.webViewHandler?.invoke(
                okhttp3.Request.Builder().url(url).build(),
            ) { true }
        }
    }

    // Bind the CookieManager stub to OkHttp CookieJar
    android.webkit.CookieManager.setCookieHandler = { url, value ->
        val httpUrl = url.toHttpUrlOrNull()
        if (httpUrl != null) {
            val cookie = okhttp3.Cookie.parse(httpUrl, value)
            if (cookie != null) {
                com.lagradost.cloudstream3.app.baseClient.cookieJar.saveFromResponse(httpUrl, listOf(cookie))
            }
        }
    }

    android.webkit.CookieManager.getCookieHandler = { url ->
        val httpUrl = url.toHttpUrlOrNull()
        if (httpUrl != null) {
            val cookies = com.lagradost.cloudstream3.app.baseClient.cookieJar.loadForRequest(httpUrl)
            if (cookies.isNotEmpty()) cookies.joinToString("; ") { "${it.name}=${it.value}" } else null
        } else {
            null
        }
    }

    // ── 4.6 Initialize Plugin Settings Store ───────────────────────────
    // Removed because CloudStreamApp directly calls DesktopKVStore

    // ── 5. Load installed plugins (dex2jar) ────────────────────────────
    loadInstalledPlugins()
    
    // ── 5.1 Load cloned sites / custom URLs ────────────────────────────
    loadClonedSites()

    // ── 6. Background auto-updater ─────────────────────────────────────
    appScope.launch(Dispatchers.IO) {
        try {
            DesktopRepositoryManager.syncAll()
        } catch (e: Exception) {
            println("Startup sync failed: ${e.message}")
            e.printStackTrace()
        }
    }

    application {
        setSingletonImageLoaderFactory { context ->
            coil3.ImageLoader.Builder(context)
                .diskCache {
                    coil3.disk.DiskCache.Builder()
                        .directory(File(PlatformPaths.appDataDir, "image_cache").also { it.mkdirs() }.toOkioPath())
                        .maxSizeBytes(512L * 1024 * 1024) // 512MB
                        .build()
                }
                .build()
        }

        val screenSize = java.awt.Toolkit.getDefaultToolkit().screenSize
        val windowWidth = (screenSize.width * 0.7).toInt().dp
        val windowHeight = (screenSize.height * 0.7).toInt().dp
        val state = androidx.compose.ui.window.rememberWindowState(
            width = windowWidth,
            height = windowHeight,
            position = androidx.compose.ui.window.WindowPosition.Aligned(androidx.compose.ui.Alignment.Center),
        )
        Window(
            onCloseRequest = ::exitApplication,
            title = "CloudStream - Unofficial Desktop Client (Pre-Alpha)",
            state = state,
            icon = androidx.compose.ui.res.painterResource("logo_ui.png.png"),
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                com.lagradost.cloudstream3.desktop.ui.LocalWindowState provides state,
            ) {
                CloudstreamApp()
            }
        }
    }
}

/**
 * Register the built-in meta-providers that ship with the client.
 */
private fun registerBuiltInProviders() {
    val builtIns = listOf(
        TmdbProvider(),
        TraktProvider(),
        CrossTmdbProvider(),
    )
    synchronized(APIHolder.allProviders) {
        builtIns.forEach { provider ->
            provider.sourcePlugin = "built-in"
            APIHolder.allProviders.add(provider)
            APIHolder.addPluginMapping(provider)
        }
    }
    println("Registered ${builtIns.size} built-in meta-providers")
}

/**
 * Scan the Extensions directory and load all installed plugin JARs.
 */
private fun loadInstalledPlugins() {
    val extensionsDir = PlatformPaths.extensionsDir
    if (!extensionsDir.exists()) {
        println("No extensions directory found at ${extensionsDir.absolutePath}")
        return
    }

    val jarFiles = extensionsDir.walkTopDown()
        .filter { it.isFile && (it.extension == "jar" || it.extension == "cs3") }
        .filter { !it.name.endsWith("-jvm.jar") }
        .toList()

    if (jarFiles.isEmpty()) {
        println("No plugins found in ${extensionsDir.absolutePath}")
        return
    }

    var loaded = 0
    var failed = 0
    for (jarFile in jarFiles) {
        try {
            ExtensionLoader.loadAndInit(jarFile)
            loaded++
        } catch (e: Throwable) {
            failed++
            println("Failed to load plugin ${jarFile.name}: ${e.message}")
        }
    }
    println("Plugin loading complete: $loaded loaded, $failed failed (of ${jarFiles.size} total)")
}

/**
 * Reads CustomSite configurations from DesktopDataStore and instantiates cloned providers with custom URLs.
 */
fun loadClonedSites() {
    try {
        val clonedSitesJson = com.lagradost.common.storage.DesktopDataStore.getKey<String>("USER_PROVIDER_API")
        if (clonedSitesJson != null) {
            val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
            val list = mapper.readValue<List<com.lagradost.cloudstream3.desktop.models.CustomSite>>(
                clonedSitesJson,
                object : com.fasterxml.jackson.core.type.TypeReference<List<com.lagradost.cloudstream3.desktop.models.CustomSite>>() {}
            )
            if (list.isEmpty()) return

            synchronized(com.lagradost.cloudstream3.APIHolder.allProviders) {
                list.forEach { custom ->
                    com.lagradost.cloudstream3.APIHolder.allProviders.firstOrNull { it.javaClass.simpleName == custom.parentJavaClass }?.let { baseProvider ->
                        val clone = baseProvider.javaClass.getDeclaredConstructor().newInstance()
                        clone.name = custom.name
                        clone.lang = custom.lang
                        clone.mainUrl = custom.url.trimEnd('/')
                        clone.canBeOverridden = false
                        com.lagradost.cloudstream3.APIHolder.allProviders.add(clone)
                        com.lagradost.cloudstream3.APIHolder.addPluginMapping(clone)
                    }
                }
            }
            println("Loaded ${list.size} cloned sites")
        }
    } catch (e: Exception) {
        println("Failed to load cloned sites: ${e.message}")
        e.printStackTrace()
    }
}
