package io.archivebox.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
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
    ServerRoute("Snapshots", SNAPSHOT_GRID_PATH, Icons.Outlined.CollectionsBookmark),
    ServerRoute("AI Agent", "admin/agent/", Icons.Outlined.AutoAwesome),
    ServerRoute("Crawl Jobs", "admin/crawls/crawl/", Icons.Outlined.TravelExplore),
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
    var transferMenu by rememberSaveable { mutableStateOf<SnapshotTransferMenu?>(null) }
    var focusViewSearch by remember(connection?.id) { mutableStateOf<((Boolean) -> Unit)?>(null) }
    var searchText by rememberSaveable(connection?.id) { mutableStateOf("") }
    var activityPath by rememberSaveable(connection?.id) { mutableStateOf("admin/") }
    var searchPath by rememberSaveable(connection?.id) { mutableStateOf(searchEditorPath("")) }
    var previousServerId by rememberSaveable { mutableStateOf(connection?.id) }
    var menuServerId by rememberSaveable { mutableStateOf(connection?.id) }
    LaunchedEffect(connection?.id) {
        if (previousServerId == connection?.id) return@LaunchedEffect
        previousServerId = connection?.id
        browserPath = null
        transferMenu = null
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
        focusViewSearch = null
        navigation.noteUserNavigation()
        recovery = null
    }
    fun navigateTo(destination: String, closeBrowser: Boolean = false) {
        noteUserNavigation()
        if (destination == "Search") searchPath = searchEditorPath(searchText)
        if (destination == "Activity") activityPath = "admin/"
        screen = destination
        if (closeBrowser) { browserPath = null; transferMenu = null }
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
            "search" -> {
                searchText = incoming.text
                searchPath = if (searchText.isBlank()) searchEditorPath("") else archiveSearchPath(searchText)
                browserPath = null
                screen = "Search"
            }
            "connect" -> { connectionRequest = incoming; screen = "Settings" }
            "snapshot" -> {
                val c = connection
                if (c == null) { routeError = "Connect to your server before opening an archived page."; screen = "Settings" }
                else if (incoming.server != null && runCatching { normalizeServer(incoming.server) }.getOrNull() != c.server) {
                    routeError = "This page belongs to a different server. Connect to that server in Settings first."
                } else if (incoming.snapshot?.let(ArchiveApi::validId) == true) {
                    transferMenu = null
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
        fun selectTab(destination: String) {
            if (destination != "Search" || connection == null ||
                (browserPath == null && screen !in listOf("Search", "Activity"))) {
                navigateTo(destination, closeBrowser = true)
                return
            }
            noteUserNavigation()
            val revision = navigation.userNavigationVersion
            // A late WebView response must not change tabs after the user has moved on.
            focusViewSearch = { found ->
                if (navigation.userNavigationVersion == revision) {
                    focusViewSearch = null
                    if (!found) navigateTo("Search", closeBrowser = true)
                }
            }
        }
        val openSearch = { selectTab("Search") }
        val switchServer: (ServerConfiguration) -> Unit = { selected ->
            noteUserNavigation()
            scope.launch {
                try { repository.selectConnection(selected.id) }
                catch (e: Exception) { routeError = e.message ?: "Couldn't switch servers." }
            }
        }
        Row(Modifier.fillMaxSize()) {
            if (wide) NavigationRail(Modifier.fillMaxHeight().testTag("navigation.rail"), header = { BrandMark(Modifier.padding(vertical = 24.dp)) }) {
                tabs.forEach { (name, icon) -> NavigationRailItem(selected = screen == name, onClick = { selectTab(name) }, icon = { Icon(icon, name) }, label = { Text(name) }, modifier = Modifier.testTag("tab.$name")) }
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
                    if (phoneHome) HomeActions(openSearch) { navigateTo("Add") }
                    else if (!wide) NavigationBar { tabs.forEach { (name, icon) -> NavigationBarItem(selected = screen == name && browserPath == null, onClick = { selectTab(name) }, icon = { Icon(icon, name) }, label = { Text(name) }, modifier = Modifier.testTag("tab.$name")) } }
                }
            ) { padding ->
                Box(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
                    val c = connection
                    when {
                        browserPath != null && c != null -> ServerBrowser(repository, c, browserPath!!, transferMenu = transferMenu,
                            focusSearch = focusViewSearch, onSearchPage = { path, _ -> browserPath = path })
                        screen == "Settings" -> key(settingsDraftVersion) {
                            ConnectionSettings(repository, connectionRequest, onRequestConsumed = { connectionRequest = null }, onGuide = { showGuide = true }, discoverOnOpen = discoverOnOpen, onDiscoveryStarted = { discoverOnOpen = false })
                        }
                        screen == "Add" -> AddScreen(repository, c, "", onConnect = { navigateTo("Settings") })
                        screen == "Search" && c != null -> ServerBrowser(repository, c, searchPath, focusSearch = focusViewSearch, onSearchPage = { path, query ->
                            searchPath = path
                            if (query != null) searchText = query
                        })
                        screen == "Search" -> ConnectPrompt { navigateTo("Settings") }
                        screen == "Activity" && c != null -> ServerBrowser(repository, c, activityPath,
                            focusSearch = focusViewSearch, onSearchPage = { path, _ -> activityPath = path })
                        screen == "Activity" -> ConnectPrompt { navigateTo("Settings") }
                        else -> HomeScreen(c, savedServers,
                            phoneHome = phoneHome, progress = progress, listState = menuState, onSwitch = switchServer,
                            onAdd = { navigateTo("Add") }, onSearch = openSearch,
                            onActivity = { navigateTo("Activity") }, onConnect = { navigateTo("Settings") },
                            onTransfer = { menu ->
                                noteUserNavigation()
                                transferMenu = menu
                                browserTitle = "Snapshots"
                                browserPath = SNAPSHOT_GRID_PATH
                            },
                            onRoute = { noteUserNavigation(); transferMenu = null; browserTitle = it.name; browserPath = it.path })
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
    connection: ServerConfiguration?, servers: List<ServerConfiguration>, phoneHome: Boolean, progress: HomeProgressState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onSwitch: (ServerConfiguration) -> Unit, onAdd: () -> Unit, onSearch: () -> Unit,
    onActivity: () -> Unit, onConnect: () -> Unit, onTransfer: (SnapshotTransferMenu) -> Unit, onRoute: (ServerRoute) -> Unit,
) {
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize().testTag("home"), state = listState, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (!phoneHome) item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAdd, modifier = Modifier.testTag("home.add")) {
                    Icon(Icons.Outlined.Add, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Add")
                }
                OutlinedButton(onClick = { onTransfer(SnapshotTransferMenu.Import) }, enabled = connection != null, modifier = Modifier.testTag("home.import")) {
                    Text("Import")
                    Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
                }
                OutlinedButton(onClick = { onTransfer(SnapshotTransferMenu.Export) }, enabled = connection != null, modifier = Modifier.testTag("home.export")) {
                    Text("Export")
                    Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
                }
            }
        }
        if (connection == null || !phoneHome) item { Surface(onClick = onConnect, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) { ListItem(headlineContent = { Text(if (connection == null) "Connect your server" else "Your ArchiveBox server") }, supportingContent = { Text(connection?.server ?: "Your data. Your devices. Your archive.", maxLines = 1, overflow = TextOverflow.Ellipsis) }, leadingContent = { Icon(if (connection == null) Icons.Outlined.Link else Icons.Outlined.Dns, null) }, trailingContent = { Icon(Icons.Outlined.ChevronRight, null) }) } }
        if (!phoneHome && servers.size > 1) item { ServerSwitcher(connection, servers, onSwitch) }
        if (!phoneHome) item { SearchEntry(onSearch, Modifier.fillMaxWidth()) }
        if (phoneHome && connection != null) item { HomeProgressStrip(progress) }
        item { SectionTitle("Collection") }
        (if (phoneHome) phoneCollectionRoutes else collectionRoutes).forEach { route ->
            if (phoneHome && route.name == "Scheduled Crawls") item(key = "collection-divider") {
                HorizontalDivider(Modifier.testTag("home.collectionDivider"))
            }
            item(key = "collection:${route.name}") {
                RouteRow(route, connection != null, tag = if (route.name == "Crawl Jobs") "route.Crawls" else "route.${route.name}") { onRoute(route) }
            }
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

internal const val SEARCH_EDITOR_PATH = "public/search-field/"

internal fun searchEditorPath(query: String): String = Uri.Builder().path(SEARCH_EDITOR_PATH)
    .appendQueryParameter("target", "admin")
    .apply { if (query.isNotBlank()) appendQueryParameter("q", query) }.build().toString()

internal fun archiveSearchPath(query: String): String = Uri.Builder().path("admin/core/snapshot/")
    .apply { if (query.isNotBlank()) appendQueryParameter("q", query) }.build().toString()

@Composable private fun SearchEntry(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    // Give the server editor the full screen for a keyboard, nested expressions and its suggestion list.
    OutlinedButton(onClick = onOpen, modifier = modifier.testTag("sidebar.searchField")) {
        Icon(Icons.Outlined.Search, contentDescription = null)
        Text("Search archive", Modifier.padding(start = 8.dp))
    }
}

private val phoneCollectionRoutes = listOf(
    collectionRoutes.first { it.name == "Crawl Jobs" },
    collectionRoutes.first { it.name == "Snapshots" },
    collectionRoutes.first { it.name == "Tags" },
) + collectionRoutes.filterNot { it.name in setOf("Crawl Jobs", "Snapshots", "Tags") }

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
    onSearch: () -> Unit, onAdd: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .height(IntrinsicSize.Min).testTag("home.actions"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchEntry(onSearch, Modifier.weight(1f))
            Button(onClick = onAdd, modifier = Modifier.fillMaxHeight().testTag("tab.Add")) { Icon(Icons.Outlined.Add, null); Text("Add") }
        }
    }
}
