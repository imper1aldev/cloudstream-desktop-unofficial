package com.lagradost.cloudstream3.desktop.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.desktop.repo.DesktopRepositoryManager
import com.lagradost.cloudstream3.desktop.repo.SitePlugin
import com.lagradost.cloudstream3.desktop.ui.NavController
import com.lagradost.runtime.loader.ExtensionLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

fun getCountryCode(languageCode: String?): String? {
    if (languageCode.isNullOrBlank() || languageCode == "none" || languageCode == "any") return null
    return when (languageCode.lowercase()) {
        "en" -> "gb"
        "hi" -> "in"
        "ar" -> "sa"
        "zh" -> "cn"
        "ja" -> "jp"
        "ko" -> "kr"
        "vi" -> "vn"
        "ur" -> "pk"
        "es" -> "es"
        "pt" -> "pt"
        "fr" -> "fr"
        "de" -> "de"
        "it" -> "it"
        "tr" -> "tr"
        "ru" -> "ru"
        "th" -> "th"
        "id" -> "id"
        "uk" -> "ua"
        "bn" -> "bd"
        "tl", "fil" -> "ph"
        "ro" -> "ro"
        "ml", "ta", "te", "kn", "mr", "gu", "pa" -> "in"
        "az" -> "az"
        "mx" -> "mx"
        else -> {
            val code = languageCode.lowercase()
            if (code.length == 2) code else null
        }
    }
}

@Composable
fun FlagImage(languageCode: String?, modifier: Modifier = Modifier) {
    val countryCode = getCountryCode(languageCode)
    if (countryCode != null) {
        coil3.compose.AsyncImage(
            model = "https://flagcdn.com/w40/$countryCode.png",
            contentDescription = "Flag",
            modifier = modifier.height(16.dp).widthIn(max = 24.dp).clip(RoundedCornerShape(2.dp)),
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
        )
    } else {
        Icon(
            imageVector = Icons.Default.Public,
            contentDescription = "Globe",
            modifier = modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun ComposeExtensionScreen(navController: NavController) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Browse", "Installed", "Repositories")
    val syncGen by DesktopRepositoryManager.syncGeneration.collectAsState()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            DesktopRepositoryManager.syncAll()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TabRow(
                selectedTabIndex = selectedTab,
                modifier = Modifier.widthIn(max = 400.dp),
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) },
                    )
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Box(modifier = Modifier.widthIn(max = 1000.dp).fillMaxHeight()) {
                when (selectedTab) {
                    0 -> BrowseTab(syncGeneration = syncGen)
                    1 -> InstalledTab(syncGeneration = syncGen)
                    2 -> RepositoriesTab()
                }
            }
        }
    }
}

@Composable
fun BrowseTab(syncGeneration: Int) {
    var searchQuery by remember { mutableStateOf("") }
    var languageFilter by remember { mutableStateOf("All") }
    var categoryFilter by remember { mutableStateOf("All") }
    var repoFilter by remember { mutableStateOf("All") }

    var plugins by remember { mutableStateOf<List<Pair<String, SitePlugin>>>(emptyList()) }
    var isFetching by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Press Sync (sidebar) or Fetch below to load plugins from your repositories.") }
    val coroutineScope = rememberCoroutineScope()

    val languages = remember(plugins) {
        listOf("All") + plugins.mapNotNull { it.second.language?.takeIf { l -> l.isNotBlank() } }.distinct().sorted()
    }
    val categories = remember(plugins) {
        listOf("All") + plugins.flatMap { it.second.tvTypes ?: emptyList() }.distinct().sorted()
    }
    val reposList = remember(plugins) {
        listOf("All") + plugins.map { it.first }.distinct().sorted()
    }

    var showLangDropdown by remember { mutableStateOf(false) }
    var showCatDropdown by remember { mutableStateOf(false) }
    var showRepoDropdown by remember { mutableStateOf(false) }
    var pluginRequiringBypass by remember { mutableStateOf<Pair<String, SitePlugin>?>(null) }

    fun fetchPlugins() {
        isFetching = true
        statusText = "Fetching plugins from repositories..."
        coroutineScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    DesktopRepositoryManager.syncAll()
                }
                plugins = DesktopRepositoryManager.getAllPlugins()
                statusText = "Fetched ${plugins.size} plugins from ${DesktopRepositoryManager.getSavedRepositories().size} repositories."
            } catch (e: Throwable) {
                statusText = "Error: ${e.message}"
            } finally {
                isFetching = false
            }
        }
    }

    LaunchedEffect(syncGeneration) {
        if (syncGeneration > 0) {
            plugins = DesktopRepositoryManager.getAllPlugins()
            statusText = "Showing ${plugins.size} plugins from ${DesktopRepositoryManager.getSavedRepositories().size} repositories."
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Search plugins...") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
            )

            Spacer(modifier = Modifier.width(12.dp))

            Box {
                FilledTonalButton(
                    onClick = { showLangDropdown = true },
                    modifier = Modifier.height(56.dp),
                ) {
                    if (languageFilter == "All") {
                        Text("All Languages")
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FlagImage(languageFilter, modifier = Modifier.padding(end = 6.dp))
                            Text(languageFilter.uppercase())
                        }
                    }
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = showLangDropdown, onDismissRequest = { showLangDropdown = false }) {
                    languages.forEach { lang ->
                        DropdownMenuItem(
                            text = {
                                if (lang == "All") {
                                    Text("All Languages")
                                } else {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        FlagImage(lang, modifier = Modifier.padding(end = 8.dp))
                                        Text(lang.uppercase())
                                    }
                                }
                            },
                            onClick = {
                                languageFilter = lang
                                showLangDropdown = false
                            },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Box {
                FilledTonalButton(
                    onClick = { showCatDropdown = true },
                    modifier = Modifier.height(56.dp),
                ) {
                    Text(if (categoryFilter == "All") "All Categories" else categoryFilter)
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = showCatDropdown, onDismissRequest = { showCatDropdown = false }) {
                    categories.forEach { cat ->
                        DropdownMenuItem(
                            text = { Text(if (cat == "All") "All Categories" else cat) },
                            onClick = {
                                categoryFilter = cat
                                showCatDropdown = false
                            },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Box {
                FilledTonalButton(
                    onClick = { showRepoDropdown = true },
                    modifier = Modifier.height(56.dp),
                ) {
                    Text(if (repoFilter == "All") "All Repos" else repoFilter)
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = showRepoDropdown, onDismissRequest = { showRepoDropdown = false }) {
                    reposList.forEach { r ->
                        DropdownMenuItem(
                            text = { Text(if (r == "All") "All Repos" else r) },
                            onClick = {
                                repoFilter = r
                                showRepoDropdown = false
                            },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))
            Button(
                onClick = { fetchPlugins() },
                enabled = !isFetching,
                modifier = Modifier.height(56.dp),
            ) {
                if (isFetching) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Fetch Repos")
                }
            }
        }

        Text(statusText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(8.dp))

        val filteredPlugins = plugins.filter {
            val matchesSearch = it.second.name.contains(searchQuery, ignoreCase = true) ||
                it.second.internalName.contains(searchQuery, ignoreCase = true)
            val matchesLang = languageFilter == "All" || it.second.language == languageFilter
            val matchesCat = categoryFilter == "All" || (it.second.tvTypes?.contains(categoryFilter) == true)
            val matchesRepo = repoFilter == "All" || it.first == repoFilter
            matchesSearch && matchesLang && matchesCat && matchesRepo
        }

        val gridScale by com.lagradost.cloudstream3.desktop.ui.screens.settings.AppearanceConfig.gridScale.collectAsState()
        val extMinSize = when (gridScale) {
            "Compact" -> 280.dp
            "Large" -> 400.dp
            else -> 340.dp
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = extMinSize),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(filteredPlugins, key = { "${it.first}-${it.second.internalName}" }) { (repoName, plugin) ->
                Card(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val iconUrl = plugin.iconUrl
                            ?: DesktopRepositoryManager.remotePluginIcons.value[plugin.internalName]
                            ?: DesktopRepositoryManager.remotePluginIcons.value[plugin.name]

                        if (!iconUrl.isNullOrEmpty()) {
                            coil3.compose.AsyncImage(
                                model = iconUrl,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).padding(end = 16.dp),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            )
                        } else {
                            Box(
                                modifier = Modifier.size(56.dp).padding(end = 16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Default.Extension, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(plugin.name, fontWeight = FontWeight.ExtraBold, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                                Spacer(modifier = Modifier.width(8.dp))
                                FlagImage(plugin.language)
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                "v${plugin.version} • $repoName",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            if (!plugin.tvTypes.isNullOrEmpty()) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    plugin.tvTypes.take(3).forEach { type ->
                                        androidx.compose.material3.AssistChip(
                                            onClick = {},
                                            label = { Text(type, style = MaterialTheme.typography.labelSmall) },
                                            modifier = Modifier.height(24.dp),
                                            colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surface),
                                        )
                                    }
                                }
                            }
                        }

                        var isInstalling by remember { mutableStateOf(false) }
                        val isPluginInstalled = remember(plugin, syncGeneration) {
                            val ext = DesktopRepositoryManager.getExtensionsDir()
                            val subDir = File(ext, repoName.replace(Regex("[^a-zA-Z0-9.-]"), "_"))
                            File(subDir, "${plugin.internalName}.jar").exists()
                        }
                        var installStatus by remember(plugin, syncGeneration) {
                            mutableStateOf(if (isPluginInstalled) "Installed" else "")
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            FilledTonalButton(
                                onClick = {
                                    isInstalling = true
                                    installStatus = "Installing..."
                                    coroutineScope.launch {
                                        try {
                                            val jarFile = withContext(Dispatchers.IO) {
                                                DesktopRepositoryManager.downloadPlugin(repoName, plugin)
                                            }
                                            if (jarFile != null) {
                                                withContext(Dispatchers.IO) {
                                                    ExtensionLoader.unloadPlugin(jarFile.absolutePath)
                                                    ExtensionLoader.loadAndInit(jarFile)
                                                }
                                                installStatus = "Installed"
                                            } else {
                                                installStatus = "Failed"
                                            }
                                        } catch (e: java.lang.SecurityException) {
                                            installStatus = "Blocked (Security)"
                                            e.printStackTrace()
                                            pluginRequiringBypass = Pair(repoName, plugin)
                                        } catch (e: Throwable) {
                                            installStatus = "Error"
                                            e.printStackTrace()
                                        } finally {
                                            isInstalling = false
                                        }
                                    }
                                },
                                enabled = !isInstalling && installStatus != "Installed",
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                                modifier = Modifier.height(36.dp),
                            ) {
                                if (isInstalling) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    Text(if (installStatus == "Installed") "Installed" else "Install", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            if (installStatus.isNotEmpty() && installStatus != "Installed") {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    installStatus,
                                    color = MaterialTheme.colorScheme.secondary,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }
        }

        pluginRequiringBypass?.let { (bypassRepo, bypassPlugin) ->
            AlertDialog(
                onDismissRequest = { pluginRequiringBypass = null },
                title = { Text("Security Sandbox Warning") },
                text = { Text("Potentially unsafe code was detected in ${bypassPlugin.name}.\n\nThis usually means the plugin uses dangerous operations (e.g., executing system commands or untracked network requests) that could compromise the app's stability or your privacy. Installing this is NOT recommended.\n\nAre you sure you want to bypass the sandbox and install it anyway?") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val p = pluginRequiringBypass
                            pluginRequiringBypass = null
                            if (p != null) {
                                coroutineScope.launch {
                                    try {
                                        val jarFile = withContext(Dispatchers.IO) {
                                            DesktopRepositoryManager.downloadPlugin(p.first, p.second)
                                        }
                                        if (jarFile != null) {
                                            withContext(Dispatchers.IO) {
                                                ExtensionLoader.unloadPlugin(jarFile.absolutePath)
                                                ExtensionLoader.loadAndInit(jarFile, forceBypassSecurity = true)
                                            }
                                            // The installStatus is local to the list item, so it won't magically update here.
                                            // But a repo sync or refresh would fetch the new state.
                                            // For now, let's just trigger a re-render of the list.
                                            val currentSync = DesktopRepositoryManager.syncGeneration.value
                                            DesktopRepositoryManager.syncGeneration.value = currentSync + 1
                                        }
                                    } catch (e: Throwable) {
                                        e.printStackTrace()
                                    }
                                }
                            }
                        },
                    ) {
                        Text("Install Anyway", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pluginRequiringBypass = null }) {
                        Text("Cancel")
                    }
                },
            )
        }
    }
}

@Composable
fun InstalledTab(syncGeneration: Int) {
    data class LocalPlugin(
        val file: File,
        val name: String,
        val internalName: String,
        val version: Int,
        val iconUrl: String?,
        val repoName: String,
        val language: String?,
        val tvTypes: List<String>?,
    )

    var installedPlugins by remember { mutableStateOf<List<LocalPlugin>>(emptyList()) }
    var selectedPlugins by remember { mutableStateOf(setOf<LocalPlugin>()) }
    val coroutineScope = rememberCoroutineScope()
    val remoteIcons by DesktopRepositoryManager.remotePluginIcons.collectAsState()

    fun refreshInstalled() {
        val list = mutableListOf<LocalPlugin>()
        val extensionsDir = DesktopRepositoryManager.getExtensionsDir()
        val allRemote = DesktopRepositoryManager.getAllPlugins()
        if (extensionsDir.exists()) {
            extensionsDir.walkTopDown()
                .filter { it.isFile && (it.extension == "jar" || it.extension == "cs3") }
                .filter { !it.name.endsWith("-jvm.jar") }
                .forEach { jar ->
                    val manifest = DesktopRepositoryManager.readPluginManifest(jar)
                    val name = manifest?.get("name") as? String ?: jar.nameWithoutExtension
                    val internalName = manifest?.get("internalName") as? String ?: name
                    val version = manifest?.get("version")?.toString()?.toIntOrNull() ?: 0
                    val iconUrl = manifest?.get("iconUrl") as? String

                    val remoteMatch = allRemote.find { it.second.internalName == internalName }
                    val repoName = remoteMatch?.first ?: jar.parentFile.name.replace("_", " ")

                    val rawTvTypes = manifest?.get("tvTypes")
                    val tvTypes = when (rawTvTypes) {
                        is List<*> -> rawTvTypes.filterIsInstance<String>()
                        is String -> listOf(rawTvTypes)
                        else -> remoteMatch?.second?.tvTypes ?: emptyList()
                    }
                    val language = manifest?.get("language") as? String ?: remoteMatch?.second?.language

                    list.add(LocalPlugin(jar, name, internalName, version, iconUrl, repoName, language, tvTypes))
                }
        }
        installedPlugins = list
        selectedPlugins = emptySet()
    }

    LaunchedEffect(Unit, syncGeneration) {
        withContext(Dispatchers.IO) { refreshInstalled() }
    }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showUnsupportedWarning by remember { mutableStateOf(false) }

    if (showUnsupportedWarning) {
        AlertDialog(
            onDismissRequest = { showUnsupportedWarning = false },
            title = { Text("Unsupported Feature") },
            text = { Text("Custom Android settings UI (Layer 3) is not supported on Desktop.\n\nPlease go to Settings -> Plugins from the sidebar to configure this plugin.") },
            confirmButton = {
                TextButton(onClick = { showUnsupportedWarning = false }) {
                    Text("OK")
                }
            },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Confirm Uninstall") },
            text = { Text("Are you sure you want to uninstall ${selectedPlugins.size} plugins? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        val toDelete = selectedPlugins.toList()
                        if (toDelete.isNotEmpty()) {
                            coroutineScope.launch(Dispatchers.IO) {
                                for (plugin in toDelete) {
                                    try {
                                        ExtensionLoader.unloadPlugin(plugin.file.absolutePath)
                                        val parentDir = plugin.file.parentFile
                                        plugin.file.delete()
                                        java.io.File(parentDir, plugin.file.nameWithoutExtension + "-jvm.jar").delete()
                                        if (parentDir != null && parentDir.listFiles()?.isEmpty() == true) {
                                            parentDir.delete()
                                        }
                                    } catch (e: Throwable) {
                                        e.printStackTrace()
                                    }
                                }
                                refreshInstalled()
                            }
                        }
                    },
                ) {
                    Text("Uninstall", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Installed Plugins (${installedPlugins.size})", style = MaterialTheme.typography.titleMedium)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        coroutineScope.launch(Dispatchers.IO) {
                            val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Load Local Plugin (.cs3 / .jar)", java.awt.FileDialog.LOAD)
                            dialog.file = "*.cs3;*.jar"
                            dialog.isVisible = true
                            if (dialog.file != null) {
                                val sourceFile = File(dialog.directory, dialog.file)
                                val targetDir = File(DesktopRepositoryManager.getExtensionsDir(), "Local_Sandbox")
                                targetDir.mkdirs()
                                val targetFile = File(targetDir, sourceFile.name)
                                sourceFile.copyTo(targetFile, overwrite = true)
                                try {
                                    ExtensionLoader.loadAndInit(targetFile)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                                refreshInstalled()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                ) {
                    Text("Load Local Plugin")
                }

                Button(
                    onClick = { showDeleteConfirm = true },
                    enabled = selectedPlugins.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Uninstall Selected (${selectedPlugins.size})")
                }
            }
        }

        val gridScale by com.lagradost.cloudstream3.desktop.ui.screens.settings.AppearanceConfig.gridScale.collectAsState()
        val extMinSize = when (gridScale) {
            "Compact" -> 280.dp
            "Large" -> 400.dp
            else -> 340.dp
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = extMinSize),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(installedPlugins, key = { it.file.absolutePath }) { plugin ->
                Card(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                    elevation = CardDefaults.cardElevation(2.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = selectedPlugins.contains(plugin),
                            onCheckedChange = { isChecked ->
                                selectedPlugins = if (isChecked) {
                                    selectedPlugins + plugin
                                } else {
                                    selectedPlugins - plugin
                                }
                            },
                        )
                        Spacer(modifier = Modifier.width(4.dp))

                        val finalIcon = plugin.iconUrl
                            ?: remoteIcons[plugin.internalName]
                            ?: remoteIcons[plugin.name]
                        if (!finalIcon.isNullOrEmpty()) {
                            coil3.compose.AsyncImage(
                                model = finalIcon,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).padding(end = 16.dp),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            )
                        } else {
                            Box(
                                modifier = Modifier.size(56.dp).padding(end = 16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Default.Extension, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(plugin.name, fontWeight = FontWeight.Bold, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                                Spacer(modifier = Modifier.width(8.dp))
                                FlagImage(plugin.language)
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                "v${plugin.version} • ${plugin.repoName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            if (!plugin.tvTypes.isNullOrEmpty()) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    plugin.tvTypes.take(3).forEach { type ->
                                        androidx.compose.material3.AssistChip(
                                            onClick = {},
                                            label = { Text(type, style = MaterialTheme.typography.labelSmall) },
                                            modifier = Modifier.height(24.dp),
                                            colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surface),
                                        )
                                    }
                                }
                            }
                        }

                        val prefName = plugin.internalName + "_"
                        var showDynamicSettings by remember { mutableStateOf(false) }

                        val instance = remember(plugin) {
                            val pluginInstance = ExtensionLoader.getPlugin(plugin.file.absolutePath) as? com.lagradost.cloudstream3.plugins.Plugin

                            pluginInstance
                        }

                        val hasSchemaSettings = com.lagradost.common.storage.PluginSettingsSchemaRegistry.hasSettings(prefName)

                        if (instance?.openSettings != null || hasSchemaSettings) {
                            IconButton(onClick = {
                                if (hasSchemaSettings) {
                                    showDynamicSettings = true
                                } else {
                                    showUnsupportedWarning = true
                                }
                            }) {
                                Icon(Icons.Default.Settings, contentDescription = "Settings")
                            }
                        }

                        if (showDynamicSettings) {
                            PluginSettingsDialog(
                                pluginName = plugin.name,
                                prefName = prefName,
                                onDismiss = { showDynamicSettings = false },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RepositoriesTab() {
    var repoUrl by remember { mutableStateOf("") }
    val repos by DesktopRepositoryManager.savedRepositories.collectAsState()
    var statusText by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = repoUrl,
                onValueChange = { repoUrl = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Short code or URL") },
                singleLine = true,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = {
                if (repoUrl.isNotBlank()) {
                    coroutineScope.launch {
                        try {
                            val repos = withContext(Dispatchers.IO) {
                                DesktopRepositoryManager.addRepositoryFromInput(repoUrl)
                            }
                            if (repos != null && repos.isNotEmpty()) {
                                repoUrl = ""
                                val repoNames = repos.take(2).joinToString { it.name } + if (repos.size > 2) " and ${repos.size - 2} more" else ""
                                statusText = "Added ${repos.size} repository(s): $repoNames. Syncing..."
                                withContext(Dispatchers.IO) { DesktopRepositoryManager.syncAll() }
                                statusText = "Repositories added and synced successfully."
                            } else {
                                statusText = "Failed to load repository. Check the URL and try again."
                            }
                        } catch (e: Throwable) {
                            statusText = "Error: ${e.message}"
                        }
                    }
                }
            }) {
                Text("Add")
            }
        }

        if (statusText.isNotEmpty()) {
            Text(statusText, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(16.dp))
        }

        Text("Saved Repositories (${repos.size})", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))

        if (repos.isEmpty()) {
            Text(
                "No repositories yet. Add a valid repo.json URL to browse plugins.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val gridScale by com.lagradost.cloudstream3.desktop.ui.screens.settings.AppearanceConfig.gridScale.collectAsState()
        val extMinSize = when (gridScale) {
            "Compact" -> 280.dp
            "Large" -> 400.dp
            else -> 340.dp
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = extMinSize),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(repos, key = { it.url }) { repo ->
                Card(
                    modifier = Modifier.fillMaxWidth().height(90.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (!repo.iconUrl.isNullOrEmpty()) {
                                coil3.compose.AsyncImage(
                                    model = repo.iconUrl,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp).padding(end = 12.dp),
                                )
                            } else {
                                Box(
                                    modifier = Modifier.size(48.dp).padding(end = 12.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("📁", style = MaterialTheme.typography.headlineMedium)
                                }
                            }
                            Column {
                                Text(repo.name, fontWeight = FontWeight.Bold, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    repo.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                        TextButton(onClick = {
                            DesktopRepositoryManager.removeRepository(repo.url)
                        }) {
                            Text("Remove", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}
