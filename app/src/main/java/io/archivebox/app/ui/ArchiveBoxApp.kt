package io.archivebox.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import io.archivebox.app.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import io.archivebox.app.data.*
import kotlinx.coroutines.launch

internal data class ServerRoute(val name: String, val path: String, val icon: ImageVector = Icons.Outlined.Folder)
internal val collectionRoutes = listOf(
    ServerRoute("Snapshots", "admin/core/snapshot/grid/", Icons.Outlined.CollectionsBookmark),
    ServerRoute("AI Agent", "admin/agent/", Icons.Outlined.AutoAwesome),
    ServerRoute("Crawls", "admin/crawls/crawl/", Icons.Outlined.TravelExplore),
    ServerRoute("Scheduled Crawls", "admin/crawls/crawlschedule/", Icons.Outlined.CalendarMonth),
    ServerRoute("Archive Results", "admin/core/archiveresult/", Icons.Outlined.Description),
    ServerRoute("Tags", "admin/core/tag/", Icons.Outlined.Sell),
    ServerRoute("Domains", "admin/domains/", Icons.Outlined.TravelExplore),
    ServerRoute("Importers", "admin/importers/", Icons.Outlined.Downloading),
)
internal val adminRoutes = listOf(
    ServerRoute("Admin", "admin/", Icons.Outlined.Dashboard),
    ServerRoute("Users", "admin/auth/user/", Icons.Outlined.Group),
    ServerRoute("Personas", "admin/personas/persona/", Icons.Outlined.AccountCircle),
    ServerRoute("API Keys", "admin/api/apitoken/", Icons.Outlined.Key),
    ServerRoute("Rules Engine", "admin/rules/", Icons.Outlined.Webhook),
    ServerRoute("Processes", "admin/machine/process/", Icons.Outlined.Memory),
    ServerRoute("Machines", "admin/machine/machine/", Icons.Outlined.Computer),
    ServerRoute("Network Interfaces", "admin/machine/networkinterface/", Icons.Outlined.Lan),
    ServerRoute("Binaries", "admin/machine/binary/", Icons.Outlined.Terminal),
    ServerRoute("Plugins", "admin/environment/plugins/", Icons.Outlined.Extension),
    ServerRoute("Workers", "admin/environment/workers/", Icons.Outlined.Settings),
    ServerRoute("Logs", "admin/environment/logs/", Icons.Outlined.ReceiptLong),
)

internal class NavigationState : ViewModel() {
    val shareRequest = mutableStateOf<IncomingRequest?>(null)
    val connectionRequest = mutableStateOf<IncomingRequest?>(null)
    var userNavigationVersion = 0
        private set

    fun noteUserNavigation() { userNavigationVersion++ }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ArchiveBoxApp(repository: ArchiveRepository, incoming: IncomingRequest?, onConsumed: () -> Unit, onFinishShare: () -> Unit) {
    val registry by repository.registry.collectAsStateWithLifecycle()
    val connection = registry.active_server
    val setupDismissed by repository.setupDismissed.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var screen by rememberSaveable { mutableStateOf("Archive") }
    var showGuide by rememberSaveable { mutableStateOf(false) }
    var discoverOnOpen by rememberSaveable { mutableStateOf(false) }
    var browserPath by rememberSaveable { mutableStateOf<String?>(null) }
    var browserTitle by rememberSaveable { mutableStateOf("") }
    var searchText by rememberSaveable { mutableStateOf("") }
    var searchMode by rememberSaveable { mutableStateOf("") }
    var searchModes by remember { mutableStateOf(listOf("meta", "contents", "deep")) }
    var previousServerId by rememberSaveable { mutableStateOf(connection?.id) }
    var menuServerId by rememberSaveable { mutableStateOf(connection?.id) }
    LaunchedEffect(connection?.id) {
        if (previousServerId == connection?.id) return@LaunchedEffect
        previousServerId = connection?.id
        browserPath = null
        searchText = ""
        searchMode = ""
        searchModes = listOf("meta", "contents", "deep")
    }
    val navigation: NavigationState = viewModel()
    var shareRequest by navigation.shareRequest
    var connectionRequest by navigation.connectionRequest
    var preventShareDismiss by remember { mutableStateOf(false) }
    var routeError by remember { mutableStateOf<String?>(null) }
    var recovery by remember { mutableStateOf<ServerConfiguration?>(null) }
    var settingsDraftVersion by rememberSaveable { mutableIntStateOf(0) }
    val latestIncoming by rememberUpdatedState(incoming)
    fun noteUserNavigation() {
        navigation.noteUserNavigation()
        recovery = null
    }
    fun navigateTo(destination: String, closeBrowser: Boolean = false) {
        noteUserNavigation()
        screen = destination
        if (closeBrowser) browserPath = null
    }
    // Keep the dialog's coroutine alive through the same connection's key
    // replacement, but never leave it attached to a different server.
    recovery?.takeIf { it.id == connection?.id && it.server == connection.server }?.let { saved ->
        RecoverySignIn(repository, saved, onDismiss = { recovery = null }, onRecovered = {
            if (recovery == saved) {
                // The fresh Settings draft reads the verified, durably saved key.
                settingsDraftVersion++
                recovery = null
            }
        })
    }
    LaunchedEffect(connection?.id, connection?.token) {
        val saved = connection ?: return@LaunchedEffect
        val launchNavigation = navigation.userNavigationVersion
        // Only offer automatic recovery while the original cold launch still
        // owns navigation. A restored Settings draft is already owned by the user.
        if (launchNavigation != 0 || screen != "Archive" || browserPath != null ||
            latestIncoming != null || saved.token.isBlank()) return@LaunchedEffect
        try {
            repository.api.testToken(saved)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: io.archivebox.app.data.ApiKeyRejectedException) {
            if (navigation.userNavigationVersion != launchNavigation || latestIncoming != null ||
                repository.registry.value.active_server != saved) return@LaunchedEffect
            browserPath = null
            screen = "Settings"
            // Clear only the editable draft. Registry credentials remain intact
            // until Test & save verifies and durably commits a replacement.
            connectionRequest = IncomingRequest("connect", server = saved.server)
            recovery = saved
        } catch (_: Exception) {
            // Network, HTTP authorization, and malformed-response errors are
            // not proof that the saved key was revoked. Keep the normal UI.
        }
    }
    LaunchedEffect(incoming?.nonce) {
        incoming ?: return@LaunchedEffect
        noteUserNavigation()
        repository.dismissSetup()
        when (incoming.action) {
            "add" -> { shareRequest = incoming; screen = "Add" }
            "search" -> { searchText = incoming.text; searchMode = ""; screen = "Search" }
            "connect" -> { connectionRequest = incoming; screen = "Settings" }
            "snapshot" -> {
                val c = connection
                if (c == null) { routeError = "Connect to your server before opening an archived page."; screen = "Settings" }
                else if (incoming.server != null && runCatching { normalizeServer(incoming.server) }.getOrNull() != c.server) {
                    routeError = "This page belongs to a different server. Connect to that server in Settings first."
                } else if (incoming.snapshot?.let(ArchiveApi::validId) == true) {
                    browserPath = "snapshot/${incoming.snapshot}/index.html"; browserTitle = "Archived page"
                } else routeError = "This archived-page link has no valid snapshot ID."
            }
        }
        onConsumed()
    }
    if ((!setupDismissed && connection == null && incoming == null && shareRequest == null && connectionRequest == null) || showGuide) {
        SetupGuide(onConnect = { scope.launch { repository.dismissSetup(); showGuide = false; screen = "Settings" } }, onDiscover = {
            repository.dismissSetup(); showGuide = false; discoverOnOpen = true; screen = "Settings"
        }, onDismiss = {
            scope.launch { repository.dismissSetup(); showGuide = false }
        })
        return
    }
    val tabs = listOf("Archive" to Icons.Outlined.Inventory2, "Search" to Icons.Outlined.Search, "Add" to Icons.Outlined.AddCircleOutline, "Activity" to Icons.Outlined.Downloading, "Settings" to Icons.Outlined.Settings)
    BackHandler(browserPath != null || screen !in listOf("Archive")) {
        navigateTo(if (browserPath != null) screen else "Archive", closeBrowser = true)
    }
    BoxWithConstraints {
        val wide = maxWidth >= 700.dp
        val phoneHome = !wide && screen == "Archive" && browserPath == null
        val menuState = rememberLazyListState()
        LaunchedEffect(phoneHome, connection?.id) {
            if (phoneHome && menuServerId != connection?.id) {
                menuState.scrollToItem(0)
                menuServerId = connection?.id
            }
        }
        val savedServers = registry.servers.filter { it.token.isNotBlank() }
        val progress = rememberHomeProgress(repository, connection, phoneHome)
        val submitSearch: (String, String) -> Unit = { query, mode ->
            searchText = query; searchMode = mode; navigateTo("Search")
        }
        val switchServer: (ServerConfiguration) -> Unit = { selected ->
            noteUserNavigation()
            scope.launch {
                try { repository.selectConnection(selected.id) }
                catch (e: Exception) { routeError = e.message ?: "Couldn't switch servers." }
            }
        }
        Row(Modifier.fillMaxSize()) {
            if (wide) NavigationRail(Modifier.fillMaxHeight().testTag("navigation.rail"), header = { BrandMark(Modifier.padding(vertical = 24.dp)) }) {
                tabs.forEach { (name, icon) -> NavigationRailItem(selected = screen == name, onClick = { navigateTo(name, closeBrowser = true) }, icon = { Icon(icon, name) }, label = { Text(name) }, modifier = Modifier.testTag("tab.$name")) }
            }
            Scaffold(
                modifier = Modifier.weight(1f),
                topBar = {
                    TopAppBar(title = { Text(browserPath?.let { browserTitle } ?: if (screen == "Archive") "ArchiveBox" else if (screen == "Settings") "Connection Settings" else if (screen == "Search") "Search Archive" else if (screen == "Add") "Add URLs" else screen, fontWeight = FontWeight.SemiBold) },
                        navigationIcon = { if (browserPath != null) IconButton(onClick = { navigateTo(screen, closeBrowser = true) }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to archive") } else if (phoneHome) IconButton(onClick = { scope.launch { menuState.scrollToItem(0) } }, modifier = Modifier.testTag("tab.Archive")) {
                            BrandMark()
                        } else if (!wide) BrandMark(Modifier.padding(start = 16.dp, end = 8.dp)) },
                        actions = {
                            if (phoneHome) {
                                if (savedServers.size > 1) ServerSwitcher(connection, savedServers, switchServer, compact = true)
                                IconButton(onClick = { navigateTo("Settings") }, modifier = Modifier.testTag("tab.Settings")) {
                                    Icon(Icons.Outlined.Settings, "Connection Settings")
                                }
                            }
                            if (screen == "Archive") IconButton(onClick = { noteUserNavigation(); showGuide = true }, modifier = Modifier.testTag("setup.reopen")) { Icon(Icons.Outlined.HelpOutline, "Setup guide") }
                        })
                },
                bottomBar = {
                    if (phoneHome) HomeActions(connection, searchText, searchMode, searchModes, submitSearch) { navigateTo("Add") }
                    else if (!wide) NavigationBar { tabs.forEach { (name, icon) -> NavigationBarItem(selected = screen == name && browserPath == null, onClick = { navigateTo(name, closeBrowser = true) }, icon = { Icon(icon, name) }, label = { Text(name) }, modifier = Modifier.testTag("tab.$name")) } }
                }
            ) { padding ->
                Box(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
                    val c = connection
                    when {
                        browserPath != null && c != null -> ServerBrowser(repository, c, browserPath!!)
                        screen == "Settings" -> key(settingsDraftVersion) {
                            ConnectionSettings(repository, connectionRequest, onRequestConsumed = { connectionRequest = null }, onGuide = { showGuide = true }, discoverOnOpen = discoverOnOpen, onDiscoveryStarted = { discoverOnOpen = false })
                        }
                        screen == "Add" -> AddScreen(repository, c, "", onConnect = { navigateTo("Settings") })
                        screen == "Search" && c != null -> ServerBrowser(repository, c, archiveSearchPath(searchText, searchMode), onSearchModes = { searchModes = it })
                        screen == "Search" -> ConnectPrompt { navigateTo("Settings") }
                        screen == "Activity" && c != null -> ServerBrowser(repository, c, "admin/")
                        screen == "Activity" -> ConnectPrompt { navigateTo("Settings") }
                        else -> HomeScreen(c, savedServers, searchText, searchMode, searchModes,
                            phoneHome = phoneHome, progress = progress, listState = menuState, onSwitch = switchServer,
                            onAdd = { navigateTo("Add") }, onSearch = submitSearch,
                            onActivity = { navigateTo("Activity") }, onConnect = { navigateTo("Settings") },
                            onRoute = { noteUserNavigation(); browserTitle = it.name; browserPath = it.path })
                    }
                }
            }
        }
    }
    shareRequest?.let { request ->
        ModalBottomSheet(onDismissRequest = { if (!preventShareDismiss) { shareRequest = null; if (request.externalShare) onFinishShare() } }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || !preventShareDismiss })) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(.91f).testTag("share.sheet")) {
                Text("Save to ArchiveBox", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                AddScreen(repository, registry.default_servers.firstOrNull(), request.text, onConnect = { shareRequest = null; navigateTo("Settings") }, onDone = { preventShareDismiss = false; shareRequest = null; if (request.externalShare) onFinishShare() }, requestId = request.nonce, onPreventDismiss = { preventShareDismiss = it })
            }
        }
    }
    routeError?.let { error -> AlertDialog(onDismissRequest = { routeError = null }, title = { Text("Couldn't open link") }, text = { Text(error) }, confirmButton = { TextButton(onClick = { routeError = null }) { Text("OK") } }) }
}

@Composable internal fun BrandMark(modifier: Modifier = Modifier, large: Boolean = false) {
    Surface(modifier.size(if (large) 84.dp else 34.dp), color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(if (large) 24.dp else 10.dp)) {
        Image(painterResource(R.drawable.archivebox_logo), "ArchiveBox", modifier = Modifier.fillMaxSize())
    }
}

@Composable private fun HomeScreen(
    connection: ServerConfiguration?, servers: List<ServerConfiguration>, searchText: String, searchMode: String,
    searchModes: List<String>, phoneHome: Boolean, progress: HomeProgressState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onSwitch: (ServerConfiguration) -> Unit, onAdd: () -> Unit, onSearch: (String, String) -> Unit,
    onActivity: () -> Unit, onConnect: () -> Unit, onRoute: (ServerRoute) -> Unit,
) {
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize().testTag("home"), state = listState, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (!phoneHome) item {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("YOUR WEB, PRESERVED.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text("A home for the web\nyou want to keep.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Save a link today. Find it here tomorrow.", style = MaterialTheme.typography.bodyLarge)
                    FilledTonalButton(onClick = onAdd, modifier = Modifier.testTag("home.add")) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("Save something good") }
                }
            }
        }
        if (connection == null || !phoneHome) item { Surface(onClick = onConnect, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) { ListItem(headlineContent = { Text(if (connection == null) "Connect your server" else "Your ArchiveBox server") }, supportingContent = { Text(connection?.server ?: "Your data. Your devices. Your archive.", maxLines = 1, overflow = TextOverflow.Ellipsis) }, leadingContent = { Icon(if (connection == null) Icons.Outlined.Link else Icons.Outlined.Dns, null) }, trailingContent = { Icon(Icons.Outlined.ChevronRight, null) }) } }
        if (!phoneHome && servers.size > 1) item { ServerSwitcher(connection, servers, onSwitch) }
        if (!phoneHome) item { key(connection?.id) { MenuSearchField(searchText, searchMode, searchModes, connection != null, onSearch) } }
        if (phoneHome && connection != null) item { HomeProgressStrip(progress) }
        item { SectionTitle("Collection") }
        items(if (phoneHome) phoneCollectionRoutes else collectionRoutes) { route ->
            RouteRow(route, connection != null, tag = if (route.name == "Crawl Jobs") "route.Crawls" else "route.${route.name}") { onRoute(route) }
        }
        if (phoneHome) item {
            RouteRow(ServerRoute("Activity", "admin/", Icons.Outlined.Downloading), true, "tab.Activity", onActivity)
        }
        item { SectionTitle("Administration") }
        items(adminRoutes) { route -> RouteRow(route, connection != null) { onRoute(route) } }
        item { SectionTitle("Help & community") }
        items(listOf("ArchiveBox documentation" to "https://github.com/ArchiveBox/ArchiveBox/wiki", "Browser extension" to "https://github.com/ArchiveBox/archivebox-browser-extension", "Community forum" to "https://zulip.archivebox.io", "Report a bug" to "https://github.com/ArchiveBox/android-archivebox/issues", "Source code" to "https://github.com/ArchiveBox/android-archivebox")) { (name, url) ->
            RouteRow(ServerRoute(name, url, Icons.Outlined.OpenInNew), true) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }
        item { Text("Your data. Your devices. Your archive.", modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
@Composable internal fun SectionTitle(title: String) { Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)) }
@Composable private fun RouteRow(route: ServerRoute, enabled: Boolean, tag: String = "route.${route.name}", onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(route.icon, null, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            Text(route.name, Modifier.weight(1f), color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
            Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}
@Composable internal fun ConnectPrompt(onConnect: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.Dns, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(20.dp)); Text("Connect your server", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(10.dp)); Text("Your archive lives on your own ArchiveBox server. Add its address and API key to get started.")
        Spacer(Modifier.height(20.dp)); Button(onClick = onConnect, modifier = Modifier.testTag("connect.open")) { Text("Connection Settings") }
    }
}
@Composable internal fun ErrorCard(message: String, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth().testTag("error"), color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { Icon(Icons.Outlined.ErrorOutline, null); Text(message, style = MaterialTheme.typography.bodyMedium) }
    }
}

internal fun archiveSearchPath(query: String, mode: String): String = Uri.Builder()
    .path("admin/core/snapshot/")
    .apply {
        if (query.isNotBlank()) appendQueryParameter("q", query.trim())
        if (mode.isNotBlank()) appendQueryParameter("search_mode", mode)
    }.build().toString()

@Composable private fun MenuSearchField(query: String, selectedMode: String, modes: List<String>, enabled: Boolean, onSearch: (String, String) -> Unit, modifier: Modifier = Modifier, rounded: Boolean = false) {
    var text by rememberSaveable(query) { mutableStateOf(query) }
    var mode by rememberSaveable(selectedMode) { mutableStateOf(selectedMode) }
    var showModes by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = text, onValueChange = { text = it }, enabled = enabled,
        placeholder = { Text("Search archive") }, singleLine = true,
        modifier = modifier.fillMaxWidth().testTag("sidebar.searchField"),
        shape = if (rounded) RoundedCornerShape(24.dp) else OutlinedTextFieldDefaults.shape,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); onSearch(text.trim(), mode) }),
        leadingIcon = {
            Box {
                IconButton(onClick = { showModes = true }, enabled = enabled, modifier = Modifier.testTag("sidebar.searchMode")) { Icon(Icons.Outlined.Search, "Search mode") }
                DropdownMenu(expanded = showModes, onDismissRequest = { showModes = false }) {
                    (listOf("") + modes).distinct().forEach { value ->
                        DropdownMenuItem(text = { Text(when (value) { "" -> "Server default"; "meta" -> "Metadata"; "contents" -> "Full text"; else -> value }) },
                            leadingIcon = { if (mode == value) Icon(Icons.Outlined.Check, null) },
                            onClick = { mode = value; showModes = false })
                    }
                }
            }
        },
        trailingIcon = { Row {
            if (text.isNotEmpty()) IconButton(onClick = { text = "" }) { Icon(Icons.Outlined.Close, "Clear search") }
            IconButton(onClick = { focus.clearFocus(); onSearch(text.trim(), mode) }, enabled = enabled, modifier = Modifier.testTag("sidebar.searchSubmit")) { Icon(Icons.Outlined.ArrowForward, "Search archive") }
        } }
    )
}

private val phoneCollectionRoutes = listOf(
    collectionRoutes.first { it.name == "Crawls" }.copy(name = "Crawl Jobs"),
    collectionRoutes.first { it.name == "Snapshots" },
    collectionRoutes.first { it.name == "Tags" },
) + collectionRoutes.filterNot { it.name in setOf("Crawls", "Snapshots", "Tags") }

@Composable private fun ServerSwitcher(
    connection: ServerConfiguration?, servers: List<ServerConfiguration>, onSwitch: (ServerConfiguration) -> Unit,
    compact: Boolean = false,
) {
    var switching by remember { mutableStateOf(false) }
    Box {
        val label = connection?.server?.let { if (compact) Uri.parse(it).authority else Uri.parse(it).host } ?: "Choose server"
        if (compact) TextButton(onClick = { switching = true }, modifier = Modifier.widthIn(max = 128.dp).testTag("sidebar.serverSwitcher")) {
            Text(label, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Outlined.ArrowDropDown, "Switch server")
        } else OutlinedButton(onClick = { switching = true }, modifier = Modifier.fillMaxWidth().testTag("sidebar.serverSwitcher")) {
            Icon(Icons.Outlined.Dns, null)
            Text(label, Modifier.weight(1f).padding(horizontal = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Outlined.ArrowDropDown, "Switch server")
        }
        DropdownMenu(expanded = switching, onDismissRequest = { switching = false }) {
            servers.forEach { server -> DropdownMenuItem(
                text = { Text(server.server) },
                leadingIcon = { Icon(if (server.id == connection?.id) Icons.Outlined.CheckCircle else Icons.Outlined.Dns, null) },
                onClick = { switching = false; if (server.id != connection?.id) onSwitch(server) },
                modifier = Modifier.testTag("sidebar.switchServer.${server.id}")
            ) }
        }
    }
}

@Composable private fun HomeActions(
    connection: ServerConfiguration?, query: String, mode: String, modes: List<String>,
    onSearch: (String, String) -> Unit, onAdd: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .padding(horizontal = 16.dp, vertical = 8.dp).testTag("home.actions"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (connection != null) key(connection.id) {
                MenuSearchField(query, mode, modes, true, onSearch, Modifier.weight(1f), rounded = true)
            } else TextButton(onClick = { onSearch("", "") }, modifier = Modifier.weight(1f).testTag("tab.Search")) {
                Icon(Icons.Outlined.Search, null)
                Text("Search archive", Modifier.padding(start = 8.dp))
            }
            Button(onClick = onAdd, modifier = Modifier.testTag("tab.Add")) { Icon(Icons.Outlined.Add, null); Text("Add") }
        }
    }
}
